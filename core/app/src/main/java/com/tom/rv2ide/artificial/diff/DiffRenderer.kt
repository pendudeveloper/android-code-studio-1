/*
 * Standalone unified-style diff renderer. Produces a Spannable with `+ ` lines
 * highlighted green, `- ` lines red, ` ` (space) lines neutral. Used both by
 * the per-file confirmation dialog (DiffPreviewDialog) and the live build-fix
 * dialog so the user can see what the AI proposed without trawling through
 * raw XML / Kotlin in the reply card.
 */
package com.tom.rv2ide.artificial.diff

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

object DiffRenderer {

    data class Result(val added: Int, val removed: Int, val span: Spanned)

    /**
     * Render a unified-style diff between [oldText] and [newText]. Capped at
     * 4000 lines per side; beyond that we fall back to a flat replace so we
     * don't blow the heap on huge files.
     */
    fun render(oldText: String?, newText: String): Result {
        val oldLines = (oldText ?: "").lines()
        val newLines = newText.lines()

        if (oldText == null) {
            // Newly created file — just show the contents prefixed with "+".
            val sb = SpannableStringBuilder()
            for (line in newLines) {
                val s = sb.length
                sb.append("+ ").append(line).append('\n')
                paintAdded(sb, s, sb.length)
            }
            return Result(newLines.size, 0, sb)
        }

        if (oldLines.size > 4000 || newLines.size > 4000) {
            return renderFlatReplace(oldLines, newLines)
        }

        // Standard LCS DP.
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
            sb.append(tag).append(' ').append(line).append('\n')
            val end = sb.length
            when (tag) {
                '+' -> { added++; paintAdded(sb, start, end) }
                '-' -> { removed++; paintRemoved(sb, start, end) }
                else -> { /* context */ }
            }
        }
        return Result(added, removed, sb)
    }

    private fun renderFlatReplace(
        oldLines: List<String>,
        newLines: List<String>,
    ): Result {
        val sb = SpannableStringBuilder()
        for (line in oldLines) {
            val s = sb.length
            sb.append("- ").append(line).append('\n')
            paintRemoved(sb, s, sb.length)
        }
        for (line in newLines) {
            val s = sb.length
            sb.append("+ ").append(line).append('\n')
            paintAdded(sb, s, sb.length)
        }
        return Result(newLines.size, oldLines.size, sb)
    }

    private fun paintAdded(sb: SpannableStringBuilder, start: Int, end: Int) {
        sb.setSpan(BackgroundColorSpan(0x6633C25E.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#A5D6A7")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun paintRemoved(sb: SpannableStringBuilder, start: Int, end: Int) {
        sb.setSpan(BackgroundColorSpan(0x66E53935.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#EF9A9A")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
