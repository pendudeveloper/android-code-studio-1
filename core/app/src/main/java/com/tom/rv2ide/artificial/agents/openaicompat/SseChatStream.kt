/*
 * SSE chat-completions streamer for any OpenAI-compatible endpoint.
 *
 * Both OpenRouter and the generic OpenAI-compatible provider speak the same
 * server-sent-event protocol (`data: {json}\n\n` lines, terminated by a final
 * `data: [DONE]`). This helper performs the POST, parses the deltas, and
 * delivers them to the caller via [onChunk]; it returns the assembled full
 * response. It also surfaces token usage if the upstream emits a `usage` block
 * in the final chunk.
 */
package com.tom.rv2ide.artificial.agents.openaicompat

import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.usage.UsageTracker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal object SseChatStream {

    /**
     * @param endpoint   The fully-resolved /chat/completions URL.
     * @param apiKey     Bearer token.
     * @param model      Model id to send.
     * @param systemPrompt Initial system message.
     * @param userPrompt The user message.
     * @param extraHeaders Optional additional headers (e.g. HTTP-Referer for OpenRouter).
     * @param onChunk    Invoked for every received delta with (delta, full).
     */
    fun call(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        extraHeaders: Map<String, String> = emptyMap(),
        onChunk: (delta: String, full: String) -> Unit,
    ): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 120000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "text/event-stream")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt))
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("max_tokens", 4096)
                .put("stream", true)
                // OpenRouter and a few others honour this flag and include a
                // final usage block in the stream so we can keep token counting.
                .put("stream_options", JSONObject().put("include_usage", true))

            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val errBody =
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                val msg = try {
                    JSONObject(errBody).optJSONObject("error")?.optString("message") ?: errBody
                } catch (_: Exception) { errBody }
                when (code) {
                    401 -> throw InvalidApiKeyException("Invalid API key: $msg")
                    402 -> throw QuotaExceededException("Quota exhausted: $msg")
                    429 -> throw RateLimitException("Rate limit: $msg")
                    else -> throw Exception("API error ($code): $msg")
                }
            }

            val full = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break

                    val obj = try { JSONObject(payload) } catch (_: Exception) { continue }

                    obj.optJSONArray("choices")?.let { choices ->
                        if (choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val piece = delta?.optString("content").orEmpty()
                            if (piece.isNotEmpty()) {
                                full.append(piece)
                                onChunk(piece, full.toString())
                            }
                        }
                    }

                    obj.optJSONObject("usage")?.let { usage ->
                        UsageTracker.record(
                            promptTokens = usage.optInt("prompt_tokens", 0),
                            completionTokens = usage.optInt("completion_tokens", 0),
                            model = model,
                        )
                    }
                }
            }

            return full.toString()
        } finally {
            connection.disconnect()
        }
    }
}
