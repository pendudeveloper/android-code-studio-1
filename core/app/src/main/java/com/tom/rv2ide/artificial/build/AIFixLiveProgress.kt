/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Reusable live-progress dialog for AI build-error fixing. Mirrors the
 *  AIAgentManager.AIAgentCallback events into a single Material dialog so the
 *  user can SEE which files are being touched, the running status, and the
 *  AI's final reply, instead of waiting blindly through a sequence of toasts.
 */

package com.tom.rv2ide.artificial.build

import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AIFixLiveProgress(private val context: Context) {

  private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

  private val view: View =
    LayoutInflater.from(context).inflate(R.layout.dialog_ai_fix_progress, null, false)

  private val statusText: MaterialTextView = view.findViewById(R.id.aiFixStatus)
  private val spinner: CircularProgressIndicator = view.findViewById(R.id.aiFixSpinner)
  private val progressBar: LinearProgressIndicator = view.findViewById(R.id.aiFixProgressBar)
  private val fileList: RecyclerView = view.findViewById(R.id.aiFixFileList)
  private val logText: MaterialTextView = view.findViewById(R.id.aiFixLog)

  private val adapter = FileModificationAdapter()
  private val logBuffer = StringBuilder()

  private var dialog: AlertDialog? = null

  init {
    fileList.layoutManager = LinearLayoutManager(context)
    fileList.adapter = adapter
  }

  fun show(title: String) {
    dialog =
      MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(view)
        .setCancelable(false)
        .setNegativeButton("Hide", null)
        .create()
    dialog?.show()
    appendLog("Started — capturing build output and asking the AI…")
  }

  /** Build the AIAgentCallback that drives this UI. [onComplete] runs on the main thread. */
  fun callback(onComplete: (success: Boolean, applied: Int, response: String?) -> Unit):
    AIAgentManager.AIAgentCallback = object : AIAgentManager.AIAgentCallback {

    override fun onProcessing(message: String) {
      runOnUi {
        statusText.text = message
        appendLog("• $message")
      }
    }

    override fun onFileModifying(filePath: String, fileName: String) {
      runOnUi {
        adapter.addItem(fileName)
        statusText.text = "Modifying $fileName"
        appendLog("→ Modifying $filePath")
      }
    }

    override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
      runOnUi {
        adapter.updateItemStatus(fileName, success)
        appendLog(if (success) "✓ Wrote $fileName" else "✗ Failed $fileName")
      }
    }

    override fun onSuccess(
      response: String,
      modifications: List<AIAgentManager.ModificationResult>,
      summary: AIAgentManager.ModificationSummary,
    ) {
      val applied = modifications.count { it.success }
      runOnUi {
        finish(success = true)
        statusText.text = "AI applied $applied fix(es)"
        appendLog("Done. ${summary.successfulFiles}/${summary.totalFiles} file changes applied.")
        appendLog("\nAI reply:\n${response.trim().take(2000)}")
        showOkButton()
      }
      onComplete(true, applied, response)
    }

    override fun onTextResponse(
      response: String,
      summary: AIAgentManager.ModificationSummary,
    ) {
      runOnUi {
        finish(success = true)
        statusText.text = "AI responded (no code changes)"
        appendLog("AI reply (no file changes):\n${response.trim().take(2000)}")
        showOkButton()
      }
      onComplete(true, 0, response)
    }

    override fun onError(message: String) {
      runOnUi {
        finish(success = false)
        statusText.text = "AI auto-fix failed"
        appendLog("ERROR: $message")
        showOkButton()
      }
      onComplete(false, 0, null)
    }

    override fun onRetry(attemptNumber: Int, message: String) {
      runOnUi {
        appendLog("Retry $attemptNumber: $message")
      }
    }
  }

  private fun finish(success: Boolean) {
    spinner.visibility = View.GONE
    progressBar.visibility = View.GONE
  }

  private fun showOkButton() {
    // The dialog was created with a "Hide" negative button so the user can dismiss
    // mid-flight. When the AI finishes we just relabel that button to "OK"; the
    // default null listener already dismisses on tap.
    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "OK"
  }

  private fun appendLog(line: String) {
    val ts = timeFmt.format(Date())
    if (logBuffer.isNotEmpty()) logBuffer.append('\n')
    logBuffer.append('[').append(ts).append("] ").append(line)
    logText.text = logBuffer
    (logText.parent as? NestedScrollView)?.post {
      (logText.parent as? NestedScrollView)?.fullScroll(View.FOCUS_DOWN)
    }
  }

  private fun runOnUi(block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      block()
    } else {
      view.post(block)
    }
  }
}
