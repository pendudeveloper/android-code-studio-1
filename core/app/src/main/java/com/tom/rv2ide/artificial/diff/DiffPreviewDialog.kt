/*
 * Diff preview dialog. The user is shown a unified line-level diff of an AI-
 * proposed file change and may Accept (write) or Reject (skip). Backed by a
 * minimal LCS-based diff so we don't need an extra dependency.
 */
package com.tom.rv2ide.artificial.diff

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object DiffPreviewDialog {

    /**
     * Show the diff dialog and suspend until the user picks Accept or Reject.
     * Safe to call from any dispatcher; switches to Main internally.
     */
    suspend fun confirm(
        context: Context,
        filePath: String,
        oldContent: String?,
        newContent: String,
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_diff_preview, null)

            val pathText = view.findViewById<MaterialTextView>(R.id.diffFilePath)
            val summaryText = view.findViewById<MaterialTextView>(R.id.diffSummary)
            val contentText = view.findViewById<MaterialTextView>(R.id.diffContent)

            pathText.text = filePath
            val (added, removed, span) = renderDiff(oldContent.orEmpty(), newContent)
            contentText.text = span
            summaryText.text = if (oldContent == null) {
                "Creating new file • ${newContent.lines().size} lines"
            } else {
                "+$added / -$removed lines"
            }

            val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
                .setTitle("AI proposes a change")
                .setView(view)
                .setPositiveButton("Apply") { d, _ ->
                    d.dismiss()
                    if (cont.isActive) cont.resume(true)
                }
                .setNegativeButton("Skip") { d, _ ->
                    d.dismiss()
                    if (cont.isActive) cont.resume(false)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(false)
                }
                .setCancelable(true)
                .create()

            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    /**
     * Returns Triple<addedLines, removedLines, formattedSpan>. The span is
     * a unified-style diff: lines starting with `+ ` highlighted green, `- `
     * red, ` ` (space) for context.
     *
     * Uses a classic LCS DP. Capped at 4000 lines per side to avoid pathological
     * O(n²) blowups on huge files; beyond that we just show line-by-line replace.
     */
    private fun renderDiff(
        oldText: String,
        newText: String,
    ): Triple<Int, Int, Spanned> {
        val oldLines = oldText.lines()
        val newLines = newText.lines()

        if (oldLines.size > 4000 || newLines.size > 4000) {
            return renderFlatReplace(oldLines, newLines)
        }

        val n = oldLines.size
        val m = newLines.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val ops = ArrayDeque<Pair<Char, String>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (oldLines[i - 1] == newLines[j - 1]) {
                ops.addFirst(' ' to oldLines[i - 1])
                i--; j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                ops.addFirst('-' to oldLines[i - 1])
                i--
            } else {
                ops.addFirst('+' to newLines[j - 1])
                j--
            }
        }
        while (i > 0) { ops.addFirst('-' to oldLines[i - 1]); i-- }
        while (j > 0) { ops.addFirst('+' to newLines[j - 1]); j-- }

        val sb = SpannableStringBuilder()
        var added = 0
        var removed = 0
        for ((tag, line) in ops) {
            val start = sb.length
            sb.append("$tag $line\n")
            val end = sb.length
            when (tag) {
                '+' -> {
                    added++
                    sb.setSpan(BackgroundColorSpan(0x6633C25E.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#1B5E20")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                '-' -> {
                    removed++
                    sb.setSpan(BackgroundColorSpan(0x66E53935.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#B71C1C")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> { /* context */ }
            }
        }
        return Triple(added, removed, sb)
    }

    private fun renderFlatReplace(
        oldLines: List<String>,
        newLines: List<String>,
    ): Triple<Int, Int, Spanned> {
        val sb = SpannableStringBuilder()
        for (line in oldLines) {
            val s = sb.length
            sb.append("- $line\n")
            sb.setSpan(BackgroundColorSpan(0x66E53935.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (line in newLines) {
            val s = sb.length
            sb.append("+ $line\n")
            sb.setSpan(BackgroundColorSpan(0x6633C25E.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Triple(newLines.size, oldLines.size, sb)
    }
}
