/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Probes common Ollama endpoints (emulator / LAN / localhost) and returns
 *  the first reachable base URL so users running Ollama locally can jump into
 *  the OpenAI-compat provider without hand-entering the URL.
 */

package com.tom.rv2ide.artificial.agents.openaicompat

import java.net.HttpURLConnection
import java.net.URL

object OllamaDetector {

    /** Candidate URLs we probe for an Ollama server in rough preference order. */
    private val CANDIDATES = listOf(
        "http://10.0.2.2:11434",   // Android emulator → host machine
        "http://localhost:11434",  // same device (Termux / rooted)
        "http://127.0.0.1:11434",
        "http://192.168.1.1:11434",
        "http://192.168.0.1:11434",
    )

    data class Detection(val baseUrl: String, val models: List<String>)

    /**
     * Attempts to probe each candidate URL with `GET /api/tags`. Returns the
     * first endpoint that answers with 200, along with the list of models it
     * reports. The caller should run this off the main thread — each probe
     * waits up to [CONNECT_TIMEOUT_MS].
     */
    fun probeBlocking(): Detection? {
        for (url in CANDIDATES) {
            try {
                val conn = URL("$url/api/tags").openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val models = parseModelsList(body)
                    // Ollama speaks OpenAI-compat on /v1 — expose that as the base.
                    return Detection("$url/v1", models)
                }
            } catch (_: Throwable) {
                // Candidate unreachable — continue.
            }
        }
        return null
    }

    /**
     * Parses the `models: [{name: ...}, ...]` array from an Ollama /api/tags
     * response without requiring the full JSON library. Good-enough for UI
     * display — a malformed response just yields an empty list.
     */
    private fun parseModelsList(body: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        for (match in regex.findAll(body)) {
            result.add(match.groupValues[1])
        }
        return result.distinct()
    }

    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 1500
}
