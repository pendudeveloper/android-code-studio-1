/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Lightweight, dependency-free Markdown -> Spanned renderer for AI replies.
 *  Handles: fenced code blocks, inline code, bold, italic, ATX headings,
 *  and bullet lists. Keeps output safe for any TextView.
 */

package com.tom.rv2ide.artificial.text

import android.text.Spanned
import androidx.core.text.HtmlCompat

object MarkdownRenderer {

  fun render(markdown: String): Spanned {
    return HtmlCompat.fromHtml(toHtml(markdown), HtmlCompat.FROM_HTML_MODE_LEGACY)
  }

  /** Convert Markdown to a tiny HTML subset HtmlCompat understands. */
  fun toHtml(markdown: String): String {
    if (markdown.isBlank()) return ""

    val out = StringBuilder()
    var i = 0
    val src = markdown.replace("\r\n", "\n")
    while (i < src.length) {
      // Fenced code block ```lang\n...\n```
      if (src.startsWith("```", i)) {
        val end = src.indexOf("```", i + 3)
        if (end > 0) {
          val block = src.substring(i + 3, end).trimStart('\n')
          val firstNl = block.indexOf('\n')
          val body = if (firstNl >= 0 && block.substring(0, firstNl).trim().matches(Regex("[a-zA-Z0-9+_.-]+"))) {
            block.substring(firstNl + 1)
          } else block
          out.append("<pre><code>")
            .append(escape(body.trimEnd()))
            .append("</code></pre>")
          i = end + 3
          // Skip a single trailing newline after the close fence.
          if (i < src.length && src[i] == '\n') i++
          continue
        }
      }

      // Heading lines
      if ((i == 0 || src[i - 1] == '\n') && src[i] == '#') {
        val lineEnd = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val line = src.substring(i, lineEnd)
        val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
        if (level <= line.length && line.length > level && line[level] == ' ') {
          val text = line.substring(level + 1)
          out.append("<h").append(level).append(">")
            .append(inline(text))
            .append("</h").append(level).append(">")
          i = lineEnd
          if (i < src.length && src[i] == '\n') i++
          continue
        }
      }

      // Bullet list item
      if ((i == 0 || src[i - 1] == '\n') &&
          (src.startsWith("- ", i) || src.startsWith("* ", i))) {
        val lineEnd = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val text = src.substring(i + 2, lineEnd)
        out.append("&#8226; ").append(inline(text)).append("<br/>")
        i = lineEnd
        if (i < src.length && src[i] == '\n') i++
        continue
      }

      // Pipe-table block — at start of line, contains `|`, and the next line
      // is a `---|---` separator. Render with HtmlCompat-friendly <table>.
      if ((i == 0 || src[i - 1] == '\n') && src[i] == '|') {
        val lineEnd = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val header = src.substring(i, lineEnd)
        val sepStart = lineEnd + 1
        val sepEnd = if (sepStart >= src.length) -1 else
          src.indexOf('\n', sepStart).let { if (it < 0) src.length else it }
        val sep = if (sepEnd >= 0) src.substring(sepStart, sepEnd) else ""
        if (header.contains('|') && sep.matches(Regex("\\s*\\|?\\s*:?-{2,}:?(\\s*\\|\\s*:?-{2,}:?)*\\s*\\|?\\s*"))) {
          val rows = mutableListOf<String>(header)
          var cursor = sepEnd
          if (cursor < src.length && src[cursor] == '\n') cursor++
          while (cursor < src.length) {
            val rowEnd = src.indexOf('\n', cursor).let { if (it < 0) src.length else it }
            val row = src.substring(cursor, rowEnd)
            if (!row.contains('|')) break
            rows.add(row)
            cursor = rowEnd
            if (cursor < src.length && src[cursor] == '\n') cursor++
            else break
          }
          out.append("<table border='1' cellpadding='4'>")
          rows.forEachIndexed { rowIdx, raw ->
            val cells = raw.trim().trim('|').split('|').map { it.trim() }
            val tag = if (rowIdx == 0) "th" else "td"
            out.append("<tr>")
            cells.forEach { c -> out.append("<").append(tag).append(">").append(inline(c)).append("</").append(tag).append(">") }
            out.append("</tr>")
          }
          out.append("</table><br/>")
          i = cursor
          continue
        }
      }

      // Plain line up to next newline, with inline markup.
      val lineEnd = src.indexOf('\n', i).let { if (it < 0) src.length else it }
      val text = src.substring(i, lineEnd)
      out.append(inline(text))
      if (lineEnd < src.length) out.append("<br/>")
      i = lineEnd
      if (i < src.length && src[i] == '\n') i++
    }
    return out.toString()
  }

  /** Inline markdown: `code`, **bold**, *italic*. Order matters. */
  private fun inline(input: String): String {
    if (input.isEmpty()) return ""

    // Inline code first so we don't process ** inside `...` blocks.
    val codeReplaced = StringBuilder()
    var idx = 0
    while (idx < input.length) {
      val tick = input.indexOf('`', idx)
      if (tick < 0) {
        codeReplaced.append(escape(input.substring(idx)))
        break
      }
      codeReplaced.append(escape(input.substring(idx, tick)))
      val close = input.indexOf('`', tick + 1)
      if (close < 0) {
        codeReplaced.append(escape(input.substring(tick)))
        break
      }
      codeReplaced.append("<code>")
        .append(escape(input.substring(tick + 1, close)))
        .append("</code>")
      idx = close + 1
    }

    var html = codeReplaced.toString()
    html = Regex("\\*\\*([^*]+?)\\*\\*").replace(html) { "<b>${it.groupValues[1]}</b>" }
    html = Regex("(?<![A-Za-z0-9])\\*([^*\n]+?)\\*(?![A-Za-z0-9])")
      .replace(html) { "<i>${it.groupValues[1]}</i>" }
    return html
  }

  private fun escape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
}
