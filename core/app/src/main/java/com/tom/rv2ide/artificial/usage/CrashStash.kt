/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Tiny helper that lets the chat fragment ask "did the app crash last run?"
 *  and pull in the saved stack trace as a prompt prefix.
 */

package com.tom.rv2ide.artificial.usage

import android.content.Context
import java.io.File

object CrashStash {

  fun lastCrashFile(context: Context): File =
    File(File(context.filesDir, "ai_crash"), "last.txt")

  fun hasUnreadCrash(context: Context): Boolean = lastCrashFile(context).isFile

  fun consume(context: Context): String? {
    val file = lastCrashFile(context)
    if (!file.isFile) return null
    return try {
      val text = file.readText()
      file.delete()
      text.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
      null
    }
  }
}
