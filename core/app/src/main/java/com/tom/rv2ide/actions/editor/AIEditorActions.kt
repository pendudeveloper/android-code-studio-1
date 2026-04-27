/*
 * AI-powered editor selection actions.
 *
 * Adds an "AI" submenu to the editor text-action bar (the one that appears when
 * code is selected). Each entry sends the selected snippet to the configured AI
 * agent with a focused prompt: explain, refactor, add docs, or generate a unit
 * test. The result is shown in a Material dialog with a Copy button — no file
 * writes happen here, so there's no risk of clobbering the editor.
 */
package com.tom.rv2ide.actions.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.ActionMenu
import com.tom.rv2ide.actions.BaseEditorAction
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.text.MarkdownRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level "AI" menu in the editor text actions bar. */
class AIActionMenu(context: Context, override val order: Int) :
  BaseEditorAction(), ActionMenu {

  override val children: MutableSet<ActionItem> = mutableSetOf()
  override val id: String = "ide.editor.ai.menu"

  init {
    label = "AI"
    icon = ContextCompat.getDrawable(context, R.drawable.ic_ai_agent)
    location = ActionItem.Location.EDITOR_TEXT_ACTIONS

    addAction(AIExplainAction(context, 0))
    addAction(AIRefactorAction(context, 1))
    addAction(AIAddDocsAction(context, 2))
    addAction(AIGenerateTestAction(context, 3))
  }

  override fun prepare(data: ActionData) {
    super<BaseEditorAction>.prepare(data)
    val editor = getEditor(data)
    if (editor == null) {
      markInvisible()
      return
    }
    val cursor = editor.text.cursor
    visible = cursor.isSelected
    enabled = visible
    super<ActionMenu>.prepare(data)
  }
}

private abstract class AIActionBase(
  protected val context: Context,
  override val order: Int,
  private val title: String,
  private val instruction: String,
) : BaseEditorAction() {
  override val id: String = "ide.editor.ai.${title.lowercase().replace(' ', '_')}"
  override var requiresUIThread: Boolean = true

  init {
    label = title
    icon = ContextCompat.getDrawable(context, R.drawable.ic_ai_agent)
    location = ActionItem.Location.EDITOR_TEXT_ACTIONS
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val editor = getEditor(data)
    if (editor == null) {
      markInvisible()
      return
    }
    val cursor = editor.text.cursor
    visible = cursor.isSelected
    enabled = visible
  }

  override suspend fun execAction(data: ActionData): Any {
    val editor = getEditor(data) ?: return false
    val cursor = editor.text.cursor
    if (!cursor.isSelected) return false
    val selected = editor.text.subSequence(cursor.left, cursor.right).toString()
    if (selected.isBlank()) return false
    AIPromptDialog.run(context, title, instruction, selected)
    return true
  }
}

private class AIExplainAction(context: Context, order: Int) :
  AIActionBase(
    context,
    order,
    "Explain",
    "Explain what the following code does in clear terms. Mention edge cases " +
      "and any subtle behaviour. Keep it concise.",
  )

private class AIRefactorAction(context: Context, order: Int) :
  AIActionBase(
    context,
    order,
    "Refactor",
    "Refactor the following code for clarity and idiomatic style. Preserve " +
      "behaviour exactly. Return only the rewritten code in a fenced code block.",
  )

private class AIAddDocsAction(context: Context, order: Int) :
  AIActionBase(
    context,
    order,
    "Add docs",
    "Add doc comments / KDoc / JavaDoc / Javadoc-style documentation to the " +
      "following code, but do not change any behaviour. Return the documented " +
      "code in a fenced code block.",
  )

private class AIGenerateTestAction(context: Context, order: Int) :
  AIActionBase(
    context,
    order,
    "Generate test",
    "Generate a focused unit test for the following code. Use the most natural " +
      "test framework for the surrounding language (JUnit 4/5 for Java/Kotlin, " +
      "etc.). Return only the test class in a fenced code block.",
  )

/** Modal that runs the AI request and renders the response live. */
private object AIPromptDialog {

  fun run(
    context: Context,
    title: String,
    instruction: String,
    selected: String,
  ) {
    val manager = AIAgentManager(context)
    val agents = com.tom.rv2ide.artificial.agents.Agents(context)
    val savedProvider = agents.getProvider()
    if (savedProvider.isNotBlank() && savedProvider != "gemini") {
      manager.setProvider(savedProvider)
    }
    manager.reinitializeWithSelectedModel()
    val agent = manager.getCurrentAgent()
    if (agent == null || !agent.isInitialized()) {
      Toast.makeText(
        context,
        "Configure an AI provider first (Preferences → AI).",
        Toast.LENGTH_LONG,
      ).show()
      return
    }

    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      val pad = (16 * context.resources.displayMetrics.density).toInt()
      setPadding(pad, pad, pad, pad)
    }
    val progress = ProgressBar(context)
    val responseText = MarkdownRenderer
    val responseView = MaterialTextView(context).apply {
      setTextIsSelectable(true)
      typeface = android.graphics.Typeface.MONOSPACE
      textSize = 12f
    }
    val scroll = ScrollView(context).apply { addView(responseView) }
    container.addView(progress)
    container.addView(scroll)

    val dialog = MaterialAlertDialogBuilder(context)
      .setTitle("AI: $title")
      .setView(container)
      .setNeutralButton("Copy") { _, _ ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI $title", responseView.text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
      }
      .setPositiveButton("Close", null)
      .create()
    dialog.show()

    val prompt = buildString {
      append(instruction)
      append("\n\n=== SELECTED CODE ===\n")
      append(selected)
    }

    val full = StringBuilder()
    val job: Job = CoroutineScope(Dispatchers.IO).launch {
      val result = agent.generateCodeStreaming(
        prompt = prompt,
        context = null,
        language = "kotlin",
        projectStructure = null,
        onChunk = { _, fullSoFar ->
          full.setLength(0); full.append(fullSoFar)
          CoroutineScope(Dispatchers.Main).launch {
            responseView.text = MarkdownRenderer.render(fullSoFar)
          }
        },
      )
      withContext(Dispatchers.Main) {
        progress.visibility = android.view.View.GONE
        result.fold(
          onSuccess = { responseView.text = MarkdownRenderer.render(it) },
          onFailure = { responseView.text = "Error: ${it.message}" },
        )
      }
    }
    dialog.setOnDismissListener { job.cancel() }
  }
}
