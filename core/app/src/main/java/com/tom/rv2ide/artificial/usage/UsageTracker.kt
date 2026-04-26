/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Tracks per-session token usage for AI providers that report it (OpenRouter,
 *  OpenAI, etc.). Lightweight, in-memory only — counts reset when the process
 *  is recreated. UI surfaces read [last] and [totalTokens] for badges/footers.
 */

package com.tom.rv2ide.artificial.usage

import java.util.concurrent.atomic.AtomicLong

object UsageTracker {

  data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    val timestamp: Long = System.currentTimeMillis(),
  )

  @Volatile var last: Usage? = null
    private set

  private val totalPrompt = AtomicLong(0)
  private val totalCompletion = AtomicLong(0)
  private val requestCount = AtomicLong(0)

  fun record(promptTokens: Int, completionTokens: Int, model: String) {
    val total = promptTokens + completionTokens
    last = Usage(promptTokens, completionTokens, total, model)
    totalPrompt.addAndGet(promptTokens.toLong())
    totalCompletion.addAndGet(completionTokens.toLong())
    requestCount.incrementAndGet()
  }

  fun totalTokens(): Long = totalPrompt.get() + totalCompletion.get()
  fun totalRequests(): Long = requestCount.get()

  fun reset() {
    last = null
    totalPrompt.set(0)
    totalCompletion.set(0)
    requestCount.set(0)
  }
}
