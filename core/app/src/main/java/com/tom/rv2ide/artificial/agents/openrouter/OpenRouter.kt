/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agents.openrouter

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.ApiKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter provider. OpenRouter exposes an OpenAI-compatible chat completions API at
 * https://openrouter.ai/api/v1 and forwards requests to a wide range of upstream models
 * (OpenAI, Anthropic, Google, Mistral, Meta, DeepSeek, ...). Using one OpenRouter API key
 * the user can pick any supported model id (e.g. `openai/gpt-4o-mini`,
 * `anthropic/claude-3.5-sonnet`, `meta-llama/llama-3.1-70b-instruct:free`).
 *
 * The model id is read in this order:
 *   1. The user-overridden custom model id (`ai_agent_openrouter_custom_model` pref).
 *   2. The currently selected model from [Agents] when its provider is `openrouter`.
 *   3. A safe default (`openai/gpt-4o-mini`).
 */
class OpenRouter : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = DEFAULT_MODEL
  override val providerId = "openrouter"
  override val providerName = "OpenRouter"

  companion object {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEFAULT_MODEL = "openai/gpt-4o-mini"
    private const val HTTP_REFERER = "https://github.com/AndroidCSOfficial/android-code-studio"
    private const val X_TITLE = "Android Code Studio"

    fun registerAgent() {
      AIAgentRegistry.register(
        "openrouter",
        object : AIAgentRegistry.AgentFactory {
          override fun create(context: Context): AIAgent {
            return OpenRouter()
          }

          override fun hasValidApiKey(): Boolean {
            val key = ApiKey.getOpenRouterApiKey()
            return key.isNotEmpty()
          }

          override fun getApiKey(): String? {
            val key = ApiKey.getOpenRouterApiKey()
            return key.ifEmpty { null }
          }
        }
      )
    }
  }

  override fun initialize(apiKey: String, context: Context) {
    this.apiKey = apiKey
    agents = Agents(context)
    selectedModel = resolveModel()
  }

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    conversationHistory.clear()
    modificationHistory.clear()
    currentAttemptCount = 0
  }

  override fun recordModification(
    filePath: String,
    oldContent: String?,
    newContent: String,
    success: Boolean
  ) {
    modificationHistory.add(
      ModificationAttempt(
        timestamp = System.currentTimeMillis(),
        filePath = filePath,
        previousContent = oldContent,
        newContent = newContent,
        attemptNumber = currentAttemptCount,
        success = success
      )
    )
  }

  override fun undoLastModification(): Boolean {
    if (modificationHistory.isEmpty()) return false
    val lastMod = modificationHistory.lastOrNull { it.success } ?: return false

    return if (lastMod.previousContent != null) {
      val result = writeFile(lastMod.filePath, lastMod.previousContent)
      if (result is FileWriteResult.Success) {
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        true
      } else false
    } else {
      try {
        File(lastMod.filePath).delete()
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        true
      } catch (e: Exception) {
        false
      }
    }
  }

  override fun getModificationHistory(): List<ModificationAttempt> = modificationHistory.toList()
  override fun resetAttemptCount() { currentAttemptCount = 0 }
  override fun incrementAttemptCount() { currentAttemptCount++ }
  override fun getCurrentAttemptCount(): Int = currentAttemptCount
  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  override suspend fun generateCode(
    prompt: String,
    context: String?,
    language: String,
    projectStructure: String?
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val key = apiKey
        ?: return@withContext Result.failure(
          IllegalStateException("OpenRouter service not initialized")
        )

      val fileContents = readRelevantFiles()
      val needsCorrection = isUserRequestingCorrection(prompt)

      val fullPrompt = buildString {
        append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
        projectTreeResult?.let {
          append(it.tree)
          append("\n\nCRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths.\n\n")
        }

        if (fileContents.isNotEmpty()) {
          append("=== CURRENT FILES CONTENT ===\n")
          fileContents.forEach { (path, content) ->
            append("FILE: $path\nCONTENT:\n$content\n\n")
          }
        }

        if (context != null) {
          append("=== ADDITIONAL CONTEXT ===\n$context\n\n")
        }

        if (conversationHistory.isNotEmpty()) {
          append("=== CONVERSATION HISTORY ===\n")
          conversationHistory.forEach { msg ->
            append("${msg.role.uppercase()}: ${msg.content}\n\n")
          }
        }

        if (needsCorrection && modificationHistory.isNotEmpty()) {
          append("=== CORRECTION REQUIRED ===\n")
          append("The user indicated the previous modification was WRONG. Try a different approach.\n\n")
        }

        if (currentAttemptCount > 0) {
          append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n\n")
        }

        append("=== USER REQUEST ===\n")
        append(prompt)
      }

      val response = callApi(key, fullPrompt)
      if (response.isBlank()) {
        return@withContext Result.failure(Exception("Empty response from OpenRouter"))
      }

      conversationHistory.add(ConversationMessage("user", prompt))
      conversationHistory.add(ConversationMessage("assistant", response))

      // Cap conversation history to prevent unbounded RAM growth.
      while (conversationHistory.size > 20) {
        conversationHistory.removeAt(0)
      }

      Result.success(response)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private fun callApi(apiKey: String, prompt: String): String {
    val url = URL(ENDPOINT)
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      // OpenRouter recommends these headers so requests show up correctly in the dashboard.
      connection.setRequestProperty("HTTP-Referer", HTTP_REFERER)
      connection.setRequestProperty("X-Title", X_TITLE)
      connection.doOutput = true
      connection.connectTimeout = 30000
      connection.readTimeout = 60000

      val messages = JSONArray()
      messages.put(JSONObject().put("role", "system").put("content", writingRules.useThis()))
      messages.put(JSONObject().put("role", "user").put("content", prompt))

      val requestBody = JSONObject()
        .put("model", selectedModel)
        .put("messages", messages)
        .put("temperature", 0.7)
        .put("max_tokens", 4096)

      connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        android.util.Log.e("OpenRouter", "Error response ($responseCode): $errorStream")
        val message = try {
          JSONObject(errorStream).optJSONObject("error")?.optString("message") ?: errorStream
        } catch (_: Exception) { errorStream }

        when {
          responseCode == 401 -> throw InvalidApiKeyException("Invalid OpenRouter API key: $message")
          responseCode == 402 -> throw QuotaExceededException("OpenRouter credit exhausted: $message")
          responseCode == 429 -> throw RateLimitException("OpenRouter rate limit: $message")
          else -> throw Exception("OpenRouter API error ($responseCode): $message")
        }
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      val choices = JSONObject(responseBody).getJSONArray("choices")
      if (choices.length() > 0) {
        return choices.getJSONObject(0).getJSONObject("message").getString("content")
      }
      throw Exception("No response from OpenRouter API")
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("OpenRouter request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Network error - cannot reach OpenRouter: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Resolve the model id to use. The OpenRouter custom-model preference takes precedence
   * because OpenRouter supports hundreds of models that aren't in the static list.
   */
  private fun resolveModel(): String {
    val custom = ApiKey.getOpenRouterCustomModel().trim()
    if (custom.isNotBlank()) return custom

    val current = agents?.getAgent()
    if (!current.isNullOrBlank() && agents?.getProvider() == "openrouter") {
      return current
    }
    return DEFAULT_MODEL
  }

  private fun isUserRequestingCorrection(message: String): Boolean {
    val keywords = listOf(
      "wrong", "not what", "mistake", "error", "incorrect",
      "that's not", "not right", "fix", "undo", "revert",
      "different", "try again", "not working"
    )
    return keywords.any { message.lowercase().contains(it) }
  }

  private fun readRelevantFiles(): Map<String, String> {
    val filesContent = mutableMapOf<String, String>()
    val tree = projectTreeResult?.tree ?: return filesContent
    val filePaths = tree.lines().filter { it.isNotBlank() }

    // Cap the total number of files we read to avoid OOM on huge projects.
    var loaded = 0
    val maxFiles = 40
    val maxBytesPerFile = 64 * 1024 // 64KB per file is plenty for code

    for (filePath in filePaths) {
      if (loaded >= maxFiles) break
      val trimmedPath = filePath.trim()
      val file = File(trimmedPath)
      if (file.isFile &&
        (trimmedPath.endsWith(".kt") ||
          trimmedPath.endsWith(".java") ||
          trimmedPath.endsWith(".xml") ||
          trimmedPath.endsWith(".gradle") ||
          trimmedPath.endsWith(".gradle.kts")) &&
        !trimmedPath.contains("/build/") && !trimmedPath.contains("/.gradle/")
      ) {
        try {
          val len = file.length()
          val content = if (len > maxBytesPerFile) {
            file.readText().take(maxBytesPerFile)
          } else file.readText()
          filesContent[trimmedPath] = content
          loaded++
        } catch (_: Exception) {
          // Skip unreadable files.
        }
      }
    }
    return filesContent
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }

  override fun isInitialized(): Boolean = apiKey != null

  private data class ConversationMessage(val role: String, val content: String)
}
