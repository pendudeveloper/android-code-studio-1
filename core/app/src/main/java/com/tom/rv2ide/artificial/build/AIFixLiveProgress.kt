/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Reusable live-progress dialog for AI build-error fixing. Mirrors the
 *  AIAgentManager.AIAgentCallback events into a single Material dialog so the
 *  user can SEE which files are being touched, the running status, and the
 *  AI's reply, instead of waiting blindly through a sequence of toasts.
 */

package com.tom.rv2ide.artificial.build

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.text.MarkdownRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AIFixLiveProgress(private val context: Context) {

  companion object {
    private const val WATCHDOG_MS = 30_000L
  }

  private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

  private val view: View =
    LayoutInflater.from(context).inflate(R.layout.dialog_ai_fix_progress, null, false)

  private val statusText: MaterialTextView = view.findViewById(R.id.aiFixStatus)
  private val attemptChip: MaterialTextView = view.findViewById(R.id.aiFixAttemptChip)
  private val subStatus: MaterialTextView = view.findViewById(R.id.aiFixSubStatus)
  private val spinner: CircularProgressIndicator = view.findViewById(R.id.aiFixSpinner)
  private val progressBar: LinearProgressIndicator = view.findViewById(R.id.aiFixProgressBar)
  private val errorExcerpt: MaterialTextView = view.findViewById(R.id.aiFixErrorExcerpt)
  private val emptyFilesHint: MaterialTextView = view.findViewById(R.id.aiFixEmptyFiles)
  private val fileCount: MaterialTextView = view.findViewById(R.id.aiFixFileCount)
  private val fileList: RecyclerView = view.findViewById(R.id.aiFixFileList)
  private val replyText: MaterialTextView = view.findViewById(R.id.aiFixReply)
  private val logText: MaterialTextView = view.findViewById(R.id.aiFixLog)

  private val adapter = FileModificationAdapter()
  private val logBuffer = SpannableStringBuilder()
  private var streamedReply = ""
  private var lastReplyRender = 0L
  private var fileTouches = 0
  private var lastEventAt = 0L
  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private var watchdog: Runnable? = null
  private var onCancel: (() -> Unit)? = null
  private var finished = false

  private var dialog: AlertDialog? = null

  init {
    fileList.layoutManager = LinearLayoutManager(context)
    fileList.adapter = adapter
  }

  /**
   * Present the live dialog. [errorOutput] is the captured Gradle output that
   * is sent to the AI; the relevant tail is shown to the user so they know
   * what is being fixed. [attempt] / [maxAttempts] drive the small badge.
   * [failedTask] is shown right under the status line if provided.
   */
  fun show(
    title: String,
    errorOutput: String? = null,
    failedTask: String? = null,
    attempt: Int = 1,
    maxAttempts: Int = 1,
    providerLabel: String? = null,
    onCancelRequested: (() -> Unit)? = null,
  ) {
    onCancel = onCancelRequested

    if (!errorOutput.isNullOrBlank()) {
      errorExcerpt.text = extractErrorTail(errorOutput)
    } else {
      errorExcerpt.text = "(Build output not captured.)"
    }
    val sub = buildString {
      if (!failedTask.isNullOrBlank()) append("Failed task: ").append(failedTask)
      if (!providerLabel.isNullOrBlank()) {
        if (isNotEmpty()) append("  •  ")
        append("Using ").append(providerLabel)
      }
    }
    if (sub.isNotEmpty()) {
      subStatus.text = sub
      subStatus.visibility = View.VISIBLE
    }
    if (maxAttempts > 1) {
      attemptChip.text = "Attempt $attempt/$maxAttempts"
      attemptChip.visibility = View.VISIBLE
    }

    dialog =
      MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(view)
        .setCancelable(false)
        .setNeutralButton("Cancel", null)
        .setNegativeButton("Hide", null)
        .create()
    dialog?.show()

    // Cancel button — abort the AI request without dismissing the dialog.
    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
      onCancel?.invoke()
      runOnUi {
        statusText.text = "Cancelled by user"
        appendLog("Cancelled by user.", LogTag.WARN)
        finish(success = false)
        showOkButton()
      }
    }

    // Make the dialog tall enough that the user can actually see the live
    // progress without it overlapping the build output behind it.
    dialog?.window?.let { window ->
      val display = context.resources.displayMetrics
      val targetHeight = (display.heightPixels * 0.85f).toInt()
      window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, targetHeight)
    }

    appendLog("Started — capturing build output and asking the AI…", LogTag.INFO)
    if (!providerLabel.isNullOrBlank()) {
      appendLog("Provider: $providerLabel", LogTag.INFO)
    }
    bumpWatchdog()
  }

  /**
   * Reset the watchdog. If we don't receive ANY callback (chunk, file touch,
   * onProcessing) for [WATCHDOG_MS], surface a hint to the user so they don't
   * stare at "Analyzing your request…" forever.
   */
  private fun bumpWatchdog() {
    lastEventAt = System.currentTimeMillis()
    watchdog?.let { mainHandler.removeCallbacks(it) }
    val cb = Runnable {
      if (finished) return@Runnable
      val idle = System.currentTimeMillis() - lastEventAt
      if (idle >= WATCHDOG_MS) {
        appendLog(
          "No progress for ${idle / 1000}s. Provider may be slow / unreachable, or your API key may be invalid. You can tap Cancel and verify the key in Preferences → AI.",
          LogTag.WARN,
        )
        statusText.text = "Still waiting on provider…"
      }
      bumpWatchdog()
    }
    watchdog = cb
    mainHandler.postDelayed(cb, WATCHDOG_MS)
  }

  /** Build the AIAgentCallback that drives this UI. [onComplete] runs on the main thread. */
  fun callback(onComplete: (success: Boolean, applied: Int, response: String?) -> Unit):
    AIAgentManager.AIAgentCallback = object : AIAgentManager.AIAgentCallback {

    override fun onProcessing(message: String) {
      runOnUi {
        statusText.text = message
        appendLog(message, LogTag.INFO)
        bumpWatchdog()
      }
    }

    override fun onFileModifying(filePath: String, fileName: String) {
      runOnUi {
        if (emptyFilesHint.visibility == View.VISIBLE) {
          emptyFilesHint.visibility = View.GONE
        }
        adapter.addItem(fileName)
        fileTouches += 1
        updateFileCount()
        statusText.text = "Modifying $fileName"
        appendLog("Modifying $filePath", LogTag.FILE)
        bumpWatchdog()
      }
    }

    override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
      runOnUi {
        adapter.updateItemStatus(fileName, success)
        appendLog(
          if (success) "Wrote $fileName" else "Failed $fileName",
          if (success) LogTag.SUCCESS else LogTag.ERROR,
        )
        bumpWatchdog()
      }
    }

    override fun onStreamChunk(delta: String, fullSoFar: String) {
      streamedReply = fullSoFar
      val now = System.currentTimeMillis()
      lastEventAt = now
      if (now - lastReplyRender < 80) return
      lastReplyRender = now
      runOnUi {
        statusText.text = "Streaming response…"
        renderReply(streamedReply)
      }
    }

    override fun onSuccess(
      response: String,
      modifications: List<AIAgentManager.ModificationResult>,
      summary: AIAgentManager.ModificationSummary,
    ) {
      val applied = modifications.count { it.success }
      streamedReply = response
      runOnUi {
        finish(success = true)
        statusText.text =
          if (applied > 0) "AI applied $applied fix(es)" else "AI replied (no code changes)"
        appendLog(
          "Done. ${summary.successfulFiles}/${summary.totalFiles} file changes applied.",
          LogTag.SUCCESS,
        )
        renderReply(response)
        showOkButton()
      }
      onComplete(true, applied, response)
    }

    override fun onTextResponse(
      response: String,
      summary: AIAgentManager.ModificationSummary,
    ) {
      streamedReply = response
      runOnUi {
        finish(success = true)
        statusText.text = "AI responded (no code changes)"
        renderReply(response)
        showOkButton()
      }
      onComplete(true, 0, response)
    }

    override fun onError(message: String) {
      runOnUi {
        finish(success = false)
        statusText.text = "AI auto-fix failed"
        appendLog(message, LogTag.ERROR)
        if (streamedReply.isBlank()) {
          renderReply("(no AI reply received)")
        }
        showOkButton()
      }
      onComplete(false, 0, null)
    }

    override fun onRetry(attemptNumber: Int, message: String) {
      runOnUi {
        appendLog("Retry $attemptNumber: $message", LogTag.WARN)
      }
    }
  }

  private fun renderReply(text: String) {
    val trimmed = text.trim()
    if (trimmed.isBlank()) {
      replyText.text = "Waiting for AI…"
      return
    }
    val rendered = try {
      MarkdownRenderer.render(trimmed.take(8000))
    } catch (_: Throwable) {
      trimmed.take(8000)
    }
    replyText.text = rendered
  }

  private fun updateFileCount() {
    fileCount.visibility = View.VISIBLE
    fileCount.text = if (fileTouches == 1) "1 file" else "$fileTouches files"
  }

  private fun finish(success: Boolean) {
    finished = true
    spinner.visibility = View.GONE
    progressBar.visibility = View.GONE
    watchdog?.let { mainHandler.removeCallbacks(it) }
    watchdog = null
    if (!success) {
      attemptChip.visibility = View.GONE
    }
  }

  private fun showOkButton() {
    // After completion the request can no longer be cancelled — hide that
    // button and rename "Hide" to "OK" so the dialog can be dismissed.
    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "OK"
  }

  private enum class LogTag(val label: String, val color: Int) {
    INFO("•", 0xFF6699CC.toInt()),
    FILE("→", 0xFFCBA6F7.toInt()),
    SUCCESS("✓", 0xFF66BB6A.toInt()),
    WARN("!", 0xFFFFB300.toInt()),
    ERROR("✗", 0xFFE57373.toInt()),
  }

  private fun appendLog(line: String, tag: LogTag = LogTag.INFO) {
    val ts = timeFmt.format(Date())
    if (logBuffer.isNotEmpty()) logBuffer.append('\n')

    val prefix = "[$ts] ${tag.label} "
    val start = logBuffer.length
    logBuffer.append(prefix)
    logBuffer.setSpan(
      ForegroundColorSpan(tag.color),
      start,
      start + prefix.length,
      Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    logBuffer.append(line)
    logText.text = logBuffer
    (logText.parent as? NestedScrollView)?.post {
      (logText.parent as? NestedScrollView)?.fullScroll(View.FOCUS_DOWN)
    }
  }

  private fun extractErrorTail(output: String): String {
    val rawLines = output.lines()

    // Skip Gradle's standard "What went wrong / Try / Get more help / Deprecated"
    // boilerplate that appears AFTER the actual compiler errors and dilutes the
    // signal in the dialog.
    fun isNoise(line: String): Boolean {
      val l = line.trim()
      return l.startsWith("> Run with ") ||
        l.startsWith("> Get more help ") ||
        l.startsWith("> Try:") ||
        l.startsWith("Deprecated Gradle features ") ||
        l.startsWith("You can use '--warning-mode") ||
        l.startsWith("For more on this, please refer") ||
        l.startsWith("BUILD FAILED in ") ||
        l.startsWith("[Incubating]") ||
        l.startsWith("* Try:") ||
        l.startsWith("* Get more help") ||
        l.matches(Regex("\\d+ actionable tasks?:.*"))
    }

    fun isError(line: String): Boolean {
      val l = line.trimStart()
      return l.startsWith("e: ") ||
        l.startsWith("error: ") ||
        l.contains("> Compilation error.") ||
        l.startsWith("> What went wrong:") ||
        l.startsWith("> Task ") && l.contains("FAILED")
    }

    val errorLines = rawLines.filter { isError(it) }
    if (errorLines.isNotEmpty()) {
      // Prefer showing the actual e:/error: lines, deduped, max 12.
      return errorLines.distinct().take(12).joinToString("\n").take(2000)
    }

    // Fallback: take a window around the last real "FAILED" task and strip noise.
    val failedTaskIdx = rawLines.indexOfLast { it.contains("> Task ") && it.contains("FAILED") }
    val window = if (failedTaskIdx >= 0) {
      val from = (failedTaskIdx - 4).coerceAtLeast(0)
      val to = (failedTaskIdx + 12).coerceAtMost(rawLines.size)
      rawLines.subList(from, to)
    } else {
      rawLines.takeLast(20)
    }
    val cleaned = window.filterNot { isNoise(it) }
    val final = if (cleaned.isEmpty()) window else cleaned
    return final.joinToString("\n").take(2000)
  }

  private fun runOnUi(block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      block()
    } else {
      view.post(block)
    }
  }
}
