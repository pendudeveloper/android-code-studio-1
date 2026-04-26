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
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.projects.builder.BuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Captures Gradle build output and lets the AI Agent attempt to fix build failures.
 *
 * The fixer is intentionally activity-agnostic: callers feed lines via [appendOutput]
 * during a build, call [reset] when a new build starts, and call [onBuildFailed] when
 * the build fails. The fixer reads the
 * [`ai_agent_autofix_build`][AUTOFIX_PREF] preference and only triggers when both the
 * preference is enabled and at least one AI provider has a valid API key.
 *
 * When [`ai_agent_autofix_loop`][AUTOFIX_LOOP_PREF] is enabled, after the AI applies
 * a fix the same Gradle tasks are re-executed automatically — up to [MAX_AUTO_CYCLES]
 * attempts per user-initiated build. The cycle counter resets in [reset].
 */
object BuildErrorAutoFixer {

  private val log = LoggerFactory.getLogger(BuildErrorAutoFixer::class.java)

  /** Cap the captured build output. We don't need megabytes of Gradle progress noise. */
  private const val MAX_LINES = 400
  private const val AUTOFIX_PREF = "ai_agent_autofix_build"
  private const val AUTOFIX_LOOP_PREF = "ai_agent_autofix_loop"
  private const val MAX_AUTO_CYCLES = 3

  private val recentLines = ArrayDeque<String>(MAX_LINES)
  @Volatile private var lastTasks: List<String> = emptyList()
  @Volatile private var cyclesUsed: Int = 0

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
    cyclesUsed = 0
    lastTasks = emptyList()
  }

  /** Snapshot of the captured output, oldest first. */
  @Synchronized
  fun snapshot(): String = recentLines.joinToString("\n")

  /** Whether auto-fix is enabled in user preferences. */
  fun isEnabled(): Boolean = prefManager.getBoolean(AUTOFIX_PREF, true)

  /** Whether the auto-rebuild loop is enabled. Defaults to off until the user opts in. */
  fun isLoopEnabled(): Boolean = prefManager.getBoolean(AUTOFIX_LOOP_PREF, false)

  /**
   * Called by [com.tom.rv2ide.handlers.EditorBuildEventListener] when the build fails.
   *
   * - First failure of a build session: shows the user-facing dialog asking whether
   *   to invoke the AI agent.
   * - Subsequent failures while we are still under [MAX_AUTO_CYCLES]: silently
   *   re-runs the AI fix and re-triggers the build (only when loop mode is enabled).
   */
  fun onBuildFailed(
    activity: Activity,
    projectRoot: String?,
    failedTasks: List<String?> = emptyList()
  ) {
    if (!isEnabled()) return
    if (!ApiKey.hasAnyApiKey()) {
      log.info("AI auto-fix skipped: no API key configured")
      return
    }
    if (activity.isFinishing || activity.isDestroyed) return

    val output = snapshot()
    if (output.isBlank()) return

    val owner = activity as? LifecycleOwner ?: return

    val tasks = failedTasks.filterNotNull().filter { it.isNotBlank() }
    if (tasks.isNotEmpty()) lastTasks = tasks

    if (cyclesUsed > 0 && isLoopEnabled() && cyclesUsed < MAX_AUTO_CYCLES) {
      // Still inside the auto-loop budget — skip the prompt and try another fix.
      Toast.makeText(
        activity,
        "AI auto-fix retry ${cyclesUsed + 1}/$MAX_AUTO_CYCLES",
        Toast.LENGTH_SHORT
      ).show()
      runFix(activity, owner, projectRoot, output)
      return
    }

    if (cyclesUsed >= MAX_AUTO_CYCLES) {
      log.info("AI auto-fix loop budget exhausted ($cyclesUsed cycles)")
      Toast.makeText(
        activity,
        "AI auto-fix gave up after $MAX_AUTO_CYCLES attempts",
        Toast.LENGTH_LONG
      ).show()
      return
    }

    val message = if (isLoopEnabled()) {
      "${activity.getString(R.string.ai_agent_autofix_build_summary)}\n\n" +
          "Auto-rebuild loop is on (max $MAX_AUTO_CYCLES attempts)."
    } else {
      activity.getString(R.string.ai_agent_autofix_build_summary)
    }

    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.ai_agent_autofix_build)
      .setMessage(message)
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
    cyclesUsed += 1

    val manager = AIAgentManager(context)
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

    val providerLabel = buildString {
      val providerName = manager.getCurrentProviderName()
      if (providerName.isNotBlank()) append(providerName)
      val modelName = manager.getCurrentModelName()
      if (modelName.isNotBlank()) {
        if (isNotEmpty()) append(" / ")
        append(modelName)
      }
    }

    val live = AIFixLiveProgress(context)
    val callback = live.callback { success, applied, _ ->
      if (!success) return@callback
      if (applied == 0) return@callback
      if (isLoopEnabled() && cyclesUsed < MAX_AUTO_CYCLES) {
        Toast.makeText(
          context,
          "Re-running build (cycle $cyclesUsed/$MAX_AUTO_CYCLES)…",
          Toast.LENGTH_SHORT,
        ).show()
        owner.lifecycleScope.launch { rerunLastBuild(context) }
      } else {
        Toast.makeText(
          context,
          "AI applied $applied fix(es). Re-run the build.",
          Toast.LENGTH_LONG,
        ).show()
      }
    }

    var requestJob: kotlinx.coroutines.Job? = null
    live.show(
      title = "AI fixing build error",
      errorOutput = output,
      failedTask = lastTasks.firstOrNull(),
      attempt = cyclesUsed,
      maxAttempts = MAX_AUTO_CYCLES,
      providerLabel = providerLabel.takeIf { it.isNotBlank() },
      onCancelRequested = { requestJob?.cancel() },
    )

    requestJob = owner.lifecycleScope.launch {
      try {
        manager.executeRequest(prompt, callback)
      } catch (e: kotlinx.coroutines.CancellationException) {
        // Cancel button already informed the UI.
        throw e
      } catch (e: Exception) {
        log.error("Auto-fix request failed", e)
        callback.onError(e.message ?: e.toString())
      }
    }
  }

  /** Re-run the last failed Gradle tasks. Best-effort — silently no-ops if no service. */
  private suspend fun rerunLastBuild(context: Context) {
    val tasks = lastTasks
    if (tasks.isEmpty()) {
      log.warn("Auto-rebuild skipped: no previous task list captured")
      return
    }
    val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    if (service == null) {
      log.warn("Auto-rebuild: BuildService not available")
      return
    }
    try {
      withContext(Dispatchers.IO) {
        service.executeTasks(*tasks.toTypedArray())
      }
    } catch (e: Throwable) {
      log.warn("Auto-rebuild failed to re-trigger tasks: ${e.message}")
    }
  }

  /** Keep only the last [maxChars] characters; build logs can be huge. */
  private fun truncate(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val tail = text.substring(text.length - maxChars)
    return "...[truncated]\n$tail"
  }
}
