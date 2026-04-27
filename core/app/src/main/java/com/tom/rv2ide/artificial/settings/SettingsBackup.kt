/*
 *  This file is part of AndroidCodeStudio.
 *
 *  JSON-based import/export of the user's AI-related preferences (provider,
 *  model, api keys, custom base URLs, feature toggles). Lets users move
 *  their setup to a new device or recover after uninstall without
 *  retyping every key by hand.
 */

package com.tom.rv2ide.artificial.settings

import android.content.Context
import android.preference.PreferenceManager
import org.json.JSONObject
import java.io.File

object SettingsBackup {

    /** Keys we consider AI-related and therefore include in the backup. */
    private val KEY_PREFIXES = listOf("ai_agent_", "ai_provider", "ai_auto")

    /** Magic version string so we can migrate old backups if the schema changes. */
    private const val SCHEMA_VERSION = "1"

    /**
     * Build a JSON dump of every AI-related preference currently set.
     * Non-string values are coerced to strings for JSON round-trip safety.
     */
    fun export(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val all = prefs.all
        val body = JSONObject()
        for ((key, value) in all) {
            if (!KEY_PREFIXES.any { key.startsWith(it) }) continue
            when (value) {
                is String -> body.put(key, value)
                is Boolean -> body.put(key, value)
                is Int -> body.put(key, value)
                is Long -> body.put(key, value)
                is Float -> body.put(key, value.toDouble())
                null -> { /* skip */ }
                else -> body.put(key, value.toString())
            }
        }
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("preferences", body)
        return root.toString(2)
    }

    /**
     * Apply a JSON dump previously produced by [export]. Unknown schema
     * versions are rejected with [IllegalArgumentException] so we don't
     * silently corrupt a newer backup.
     */
    fun import(context: Context, json: String): ImportResult {
        val root = JSONObject(json)
        val schema = root.optString("schema")
        if (schema != SCHEMA_VERSION) {
            return ImportResult(0, "Unsupported backup schema: '$schema' (expected '$SCHEMA_VERSION')")
        }
        val body = root.optJSONObject("preferences")
            ?: return ImportResult(0, "Backup does not contain a preferences block.")
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        var count = 0
        val iter = body.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            if (!KEY_PREFIXES.any { key.startsWith(it) }) continue
            when (val v = body.get(key)) {
                is String -> editor.putString(key, v)
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Double -> editor.putFloat(key, v.toFloat())
                else -> editor.putString(key, v.toString())
            }
            count++
        }
        editor.apply()
        return ImportResult(count, null)
    }

    /** Convenience — writes the export to a Downloads file and returns the path. */
    fun exportToDownloads(context: Context): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ),
            "AndroidCodeStudio",
        )
        if (!dir.exists()) dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = File(dir, "android-code-studio-ai-settings_$stamp.json")
        out.writeText(export(context))
        return out
    }

    data class ImportResult(val applied: Int, val error: String?)
}
