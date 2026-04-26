package com.tom.rv2ide.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.text.MarkdownRenderer
import com.tom.rv2ide.artificial.usage.SessionLog
import com.tom.rv2ide.artificial.usage.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AIRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiAgent: AIAgentManager,
    private val statusText: MaterialTextView,
    private val summaryText: MaterialTextView,
    private val progressIndicator: CircularProgressIndicator,
    private val executeBtn: MaterialButton,
    private val fileModificationList: RecyclerView,
    private val fileModificationAdapter: FileModificationAdapter,
    private val summaryCard: LinearLayout,
    private val onFileOpen: (String) -> Unit,
    private val onTypeText: (String, Long) -> Unit,
    private val getCurrentFile: () -> File?,
    private val refreshEditor: () -> Unit
) {
    
    private var executionJob: Job? = null
    
    fun execute(userRequest: String) {
        executionJob?.cancel()
        executionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = false
                    progressIndicator.visibility = View.VISIBLE
                    summaryCard.visibility = View.GONE
                    fileModificationAdapter.clear()
                    fileModificationList.visibility = View.GONE
                }

                SessionLog.add(SessionLog.Entry("user", userRequest))
                executeAIRequest(userRequest)
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = true
                    progressIndicator.visibility = View.GONE
                    statusText.text = "❌ Error: ${e.message}"
                }
            }
        }
    }
    
    private suspend fun executeAIRequest(userRequest: String) {
        aiAgent.executeRequest(userRequest, object : AIAgentManager.AIAgentCallback {
            override fun onProcessing(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = message
                }
            }

            override fun onFileModifying(filePath: String, fileName: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    if (fileModificationList.visibility == View.GONE) {
                        fileModificationList.visibility = View.VISIBLE
                    }
                    fileModificationAdapter.addItem(fileName)
                }
            }

            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileModificationAdapter.updateItemStatus(fileName, success)
                    
                    if (getCurrentFile()?.name == fileName && success) {
                        refreshEditor()
                    }
                }
            }

            override fun onSuccess(
                response: String,
                modifications: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleSuccess(response, modifications, summary)
                }
            }

            override fun onTextResponse(
                response: String,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleTextResponse(response)
                }
            }

            override fun onError(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleError(message)
                }
            }

            override fun onRetry(attemptNumber: Int, message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = "🔄 Retry #$attemptNumber: $message"
                }
            }
        })
    }
    
    private fun handleSuccess(
        response: String,
        modifications: List<AIAgentManager.ModificationResult>,
        summary: AIAgentManager.ModificationSummary
    ) {
        progressIndicator.visibility = View.GONE
        statusText.text = "✅ Operation completed"
        SessionLog.add(SessionLog.Entry("assistant", response, model = UsageTracker.last?.model))
        summaryText.text = buildSummaryText(summary)
        summaryCard.visibility = View.VISIBLE
        
        if (modifications.isNotEmpty()) {
            val firstMod = modifications.first()
            val file = File(firstMod.filePath)
            if (file.exists()) {
                onFileOpen(file.name)
            }
        }
        
        executeBtn.isEnabled = true
    }

    private fun handleTextResponse(response: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        SessionLog.add(SessionLog.Entry("assistant", response, model = UsageTracker.last?.model))
        statusText.text = MarkdownRenderer.render(response)
        statusText.setOnLongClickListener {
            val ctx = statusText.context
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI response", response))
            Toast.makeText(ctx, "Copied AI response", Toast.LENGTH_SHORT).show()
            true
        }
        summaryCard.visibility = View.GONE
        fileModificationList.visibility = View.GONE
    }
    
    private fun handleError(message: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        
        statusText.text = """
❌ ERROR OCCURRED

$message

Please check the error message and try again.
        """.trimIndent()
    }
    
    private fun buildSummaryText(summary: AIAgentManager.ModificationSummary): String {
        val builder = StringBuilder()
        builder.append("📊 Total Files: ${summary.totalFiles}\n")
        builder.append("✅ Successful: ${summary.successfulFiles}\n")
        if (summary.failedFiles > 0) {
            builder.append("❌ Failed: ${summary.failedFiles}\n")
        }
        builder.append("🆕 New Files: ${summary.newFiles}\n")
        builder.append("✏️ Modified Files: ${summary.modifiedFiles}\n")

        UsageTracker.last?.let { usage ->
            builder.append("🔢 Tokens: ${usage.promptTokens} in / ${usage.completionTokens} out")
                .append("  •  session total: ${UsageTracker.totalTokens()}\n")
        }
        builder.append('\n')
        
        builder.append("Files:\n")
        summary.fileDetails.forEach { detail ->
            val icon = if (detail.status == AIAgentManager.FileStatus.SUCCESS) "✅" else "❌"
            val type = if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created" else "Modified"
            builder.append("$icon $type: ${detail.fileName}\n")
        }
        
        return builder.toString()
    }
    
    fun cancel() {
        executionJob?.cancel()
    }
}