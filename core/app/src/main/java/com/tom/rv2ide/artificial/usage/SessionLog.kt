/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Lightweight in-memory transcript of the current AI Agent conversation.
 *  Exists so the user can dump the chat to Markdown/JSON without us having
 *  to plumb a getter through every provider.
 */

package com.tom.rv2ide.artificial.usage

import org.json.JSONArray
import org.json.JSONObject

object SessionLog {

  data class Entry(
    val role: String, // "user" | "assistant"
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val model: String? = null,
  )

  private const val MAX_ENTRIES = 200

  private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

  @Synchronized
  fun add(entry: Entry) {
    entries.addLast(entry)
    while (entries.size > MAX_ENTRIES) entries.removeFirst()
  }

  @Synchronized
  fun snapshot(): List<Entry> = entries.toList()

  @Synchronized
  fun clear() = entries.clear()

  @Synchronized
  fun isEmpty(): Boolean = entries.isEmpty()

  fun toMarkdown(): String {
    val out = StringBuilder()
    out.append("# AI Agent conversation\n\n")
    val total = UsageTracker.totalTokens()
    if (total > 0) {
      out.append("_Session totals: ${UsageTracker.totalRequests()} requests, $total tokens._\n\n")
    }
    for (e in snapshot()) {
      out.append("## ").append(e.role.replaceFirstChar { it.uppercase() })
      e.model?.takeIf { it.isNotBlank() }?.let { out.append(" — `").append(it).append("`") }
      out.append("\n\n")
      out.append(e.content.trim()).append("\n\n")
    }
    return out.toString()
  }

  fun toJson(): String {
    val arr = JSONArray()
    for (e in snapshot()) {
      arr.put(
        JSONObject()
          .put("role", e.role)
          .put("content", e.content)
          .put("timestamp_ms", e.timestampMs)
          .put("model", e.model ?: JSONObject.NULL)
      )
    }
    val totals = JSONObject()
      .put("requests", UsageTracker.totalRequests())
      .put("total_tokens", UsageTracker.totalTokens())
    return JSONObject()
      .put("usage_totals", totals)
      .put("messages", arr)
      .toString(2)
  }
}
