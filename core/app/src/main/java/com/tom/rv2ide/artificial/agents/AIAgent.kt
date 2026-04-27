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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.FileWriteResult

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

interface AIAgent {
    val providerId: String
    val providerName: String
    
    fun initialize(apiKey: String, context: Context)
    fun reinitializeWithNewModel(apiKey: String, context: Context)
    fun setContext(context: Context)
    fun setProjectData(projectTreeResult: ProjectTreeResult)
    fun clearConversation()
    
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>

    /**
     * Streaming variant. Implementations that support server-sent events should
     * push every incremental delta through [onChunk] as it arrives, and return
     * the full assembled response. The default implementation falls back to the
     * non-streaming [generateCode] (and emits the entire response as a single
     * chunk) so existing providers continue to work unchanged.
     */
    suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        onChunk: (delta: String, full: String) -> Unit
    ): Result<String> {
        val result = generateCode(prompt, context, language, projectStructure)
        result.getOrNull()?.let { onChunk(it, it) }
        return result
    }

    /** Whether this provider supports true streaming (SSE) deltas. */
    fun supportsStreaming(): Boolean = false

    fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean)
    fun undoLastModification(): Boolean
    fun getModificationHistory(): List<ModificationAttempt>
    
    fun resetAttemptCount()
    fun incrementAttemptCount()
    fun getCurrentAttemptCount(): Int
    fun canRetry(): Boolean
    
    fun writeFile(filePath: String, content: String): FileWriteResult
    fun isInitialized(): Boolean
}

data class ModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)