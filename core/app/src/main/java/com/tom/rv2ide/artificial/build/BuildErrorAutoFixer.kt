/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.build

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.preferences.internal.prefManager
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Captures Gradle build output and lets the AI Agent attempt to fix build failures.
 *
 * The fixer is intentionally activity-agnostic: callers feed lines via [appendOutput]
 * during a build, call [reset] when a new build starts, and call [onBuildFailed] when
 * the build fails. The fixer reads the
 * [`ai_agent_autofix_build`][AUTOFIX_PREF] preference and only triggers when both the
 * preference is enabled and at least one AI provider has a valid API key.
 */
object BuildErrorAutoFixer {

  private val log = LoggerFactory.getLogger(BuildErrorAutoFixer::class.java)

  /** Cap the captured build output. We don't need megabytes of Gradle progress noise. */
  private const val MAX_LINES = 400
  private const val AUTOFIX_PREF = "ai_agent_autofix_build"

  private val recentLines = ArrayDeque<String>(MAX_LINES)

  /** Fired by the build event listener for every line of build output. */
  @Synchronized
  fun appendOutput(line: String?) {
    if (line.isNullOrEmpty()) return
    if (recentLines.size >= MAX_LINES) {
      recentLines.removeFirst()
    }
    recentLines.addLast(line)
  }

  /** Drop captured output when a new build begins. */
  @Synchronized
  fun reset() {
    recentLines.clear()
  }

  /** Snapshot of the captured output, oldest first. */
  @Synchronized
  fun snapshot(): String = recentLines.joinToString("\n")

  /** Whether auto-fix is enabled in user preferences. */
  fun isEnabled(): Boolean = prefManager.getBoolean(AUTOFIX_PREF, true)

  /**
   * Called by [com.tom.rv2ide.handlers.EditorBuildEventListener] when the build fails.
   *
   * Shows a dialog with the option to ask the AI Agent to fix the failure. If
   * auto-fix is disabled, this is a no-op so the rest of the existing failure-handling
   * UI remains undisturbed.
   */
  fun onBuildFailed(activity: Activity, projectRoot: String?) {
    if (!isEnabled()) return
    if (!ApiKey.hasAnyApiKey()) {
      log.info("AI auto-fix skipped: no API key configured")
      return
    }
    if (activity.isFinishing || activity.isDestroyed) return

    val output = snapshot()
    if (output.isBlank()) return

    val owner = activity as? LifecycleOwner ?: return

    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.ai_agent_autofix_build)
      .setMessage(R.string.ai_agent_autofix_build_summary)
      .setPositiveButton(R.string.ai_agent_autofix_build) { dialog, _ ->
        dialog.dismiss()
        Toast.makeText(activity, R.string.ai_agent_autofix_started, Toast.LENGTH_SHORT).show()
        runFix(activity, owner, projectRoot, output)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun runFix(
    context: Context,
    owner: LifecycleOwner,
    projectRoot: String?,
    output: String
  ) {
    val manager = AIAgentManager(context)
    // The manager defaults to Gemini in its constructor; if that has no valid key, fall
    // back to the user's currently selected provider, then to whichever provider has a
    // configured API key.
    if (manager.getCurrentAgent() == null) {
      val agents = com.tom.rv2ide.artificial.agents.Agents(context)
      val preferred = agents.getProvider()
      val candidates = buildList {
        add(preferred)
        addAll(listOf("openrouter", "openai", "claude", "gemini", "deepseek", "grok"))
      }.distinct()
      candidates.firstOrNull { manager.setProvider(it) }
    }
    if (manager.getCurrentAgent() == null) {
      Toast.makeText(context, R.string.ai_agent_autofix_unavailable, Toast.LENGTH_LONG).show()
      return
    }
    if (!projectRoot.isNullOrBlank()) {
      manager.setProjectRoot(projectRoot)
    }

    val prompt = context.getString(R.string.ai_agent_autofix_prompt, truncate(output, 12_000))

    owner.lifecycleScope.launch {
      try {
        manager.executeRequest(prompt, object : AIAgentManager.AIAgentCallback {
          override fun onProcessing(message: String) {
            log.debug("AutoFix: {}", message)
          }

          override fun onFileModifying(filePath: String, fileName: String) {
            log.info("AutoFix modifying: {}", filePath)
          }

          override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
            log.info("AutoFix modified {} -> success={}", filePath, success)
          }

          override fun onSuccess(
            response: String,
            modifications: List<AIAgentManager.ModificationResult>,
            summary: AIAgentManager.ModificationSummary
          ) {
            Toast.makeText(
              context,
              "AI applied ${modifications.size} fix(es). Re-run the build.",
              Toast.LENGTH_LONG
            ).show()
          }

          override fun onTextResponse(
            response: String,
            summary: AIAgentManager.ModificationSummary
          ) {
            // Show the explanation but don't claim a fix was applied.
            MaterialAlertDialogBuilder(context)
              .setTitle("AI suggestion")
              .setMessage(response.take(4000))
              .setPositiveButton(android.R.string.ok, null)
              .show()
          }

          override fun onError(message: String) {
            Toast.makeText(context, "AI auto-fix failed: $message", Toast.LENGTH_LONG).show()
          }

          override fun onRetry(attemptNumber: Int, message: String) {
            log.debug("AutoFix retry {}: {}", attemptNumber, message)
          }
        })
      } catch (e: Exception) {
        log.error("Auto-fix request failed", e)
        Toast.makeText(context, "AI auto-fix failed: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  /** Keep only the last [maxChars] characters; build logs can be huge. */
  private fun truncate(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val tail = text.substring(text.length - maxChars)
    return "...[truncated]\n$tail"
  }
}
