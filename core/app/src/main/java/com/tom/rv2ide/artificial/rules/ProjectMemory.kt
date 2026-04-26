/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Loads optional per-project notes the AI Agent should keep in mind across
 *  conversations. Notes live at <projectRoot>/.aistudio/memory.md so they can
 *  be checked into git and shared across devices.
 */

package com.tom.rv2ide.artificial.rules

import java.io.File

object ProjectMemory {

  private const val DIR_NAME = ".aistudio"
  private const val FILE_NAME = "memory.md"
  private const val MAX_BYTES = 16 * 1024 // 16 KB ceiling — the AI doesn't need a novel.

  /** Project file containing agent notes, regardless of whether it currently exists. */
  fun memoryFile(projectRoot: File): File = File(File(projectRoot, DIR_NAME), FILE_NAME)

  /** Returns the memory contents trimmed to [MAX_BYTES], or null if no memory is set. */
  fun read(projectRoot: File?): String? {
    if (projectRoot == null) return null
    val file = memoryFile(projectRoot)
    if (!file.isFile) return null
    return try {
      val text = file.readText().trim()
      if (text.isEmpty()) null else text.take(MAX_BYTES)
    } catch (_: Throwable) {
      null
    }
  }

  /** Persist [content] to the project memory file, creating directories as needed. */
  fun write(projectRoot: File, content: String): Boolean {
    return try {
      val file = memoryFile(projectRoot)
      file.parentFile?.mkdirs()
      file.writeText(content.take(MAX_BYTES))
      true
    } catch (_: Throwable) {
      false
    }
  }
}
