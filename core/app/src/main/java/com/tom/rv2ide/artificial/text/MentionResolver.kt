/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.artificial.text

import java.io.File

/**
 * Resolves `@filename.kt` (and similar) @-mentions inside a chat prompt by
 * scanning the project for matching files and prepending their contents to
 * the prompt as explicit context.
 *
 * Behaviour:
 *  - Patterns matched: `@<token>` where `<token>` is alphanumeric + ` _ . / -`.
 *  - The shortest path that ends with the token (case-sensitive on Linux,
 *    case-insensitive on common case-folded names) is preferred.
 *  - Files larger than [MAX_BYTES] are truncated.
 *  - At most [MAX_FILES] mentions are resolved per prompt to bound payload size.
 *
 * The original `@token` text is left in the prompt so the AI keeps the user's
 * intent, but a `=== ATTACHED FILES ===` section is prepended with the actual
 * resolved file contents.
 */
object MentionResolver {

    private const val MAX_FILES = 8
    private const val MAX_BYTES = 32 * 1024
    private val mentionPattern = Regex("(?:^|[\\s(\\[{])@([A-Za-z0-9_./-]+)")

    /** Result of a resolution pass over a prompt. */
    data class Result(
        /** The user's original prompt, unchanged. */
        val originalPrompt: String,
        /** The prompt with the attached-files header prepended (or original if no mentions). */
        val expandedPrompt: String,
        /** List of resolved files (relative paths). */
        val resolved: List<String>,
        /** List of mention tokens that did not match any file. */
        val unresolved: List<String>,
    )

    fun resolve(prompt: String, projectRoot: File?): Result {
        val mentions = mentionPattern.findAll(prompt)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .map { it.trimEnd('.', ',', ';', ':', ')', ']', '}') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        if (mentions.isEmpty() || projectRoot == null || !projectRoot.exists()) {
            return Result(prompt, prompt, emptyList(), emptyList())
        }

        val resolved = mutableListOf<Pair<String, String>>()
        val unresolved = mutableListOf<String>()

        for (token in mentions) {
            if (resolved.size >= MAX_FILES) break
            val match = findFile(projectRoot, token)
            if (match == null) {
                unresolved.add(token)
                continue
            }
            try {
                val bytes = match.length()
                val relative = match.relativeTo(projectRoot).path
                val content = if (bytes > MAX_BYTES) {
                    match.readText().take(MAX_BYTES) + "\n... [truncated; file is ${bytes / 1024}KB]"
                } else {
                    match.readText()
                }
                resolved.add(relative to content)
            } catch (e: Throwable) {
                unresolved.add(token)
            }
        }

        if (resolved.isEmpty()) {
            return Result(prompt, prompt, emptyList(), unresolved)
        }

        val expanded = buildString {
            append("=== ATTACHED FILES (from @-mentions in user request) ===\n")
            for ((path, content) in resolved) {
                append("FILE: ").append(path).append('\n')
                append("CONTENT:\n").append(content).append("\n\n")
            }
            append("=== USER REQUEST ===\n").append(prompt)
        }

        return Result(prompt, expanded, resolved.map { it.first }, unresolved)
    }

    /**
     * Walk the project tree and find the first file whose path ends with the
     * mention token. Common build-output / vendored / cache directories are
     * skipped to keep the search fast on large Android projects.
     */
    private fun findFile(root: File, token: String): File? {
        val needle = token.trim('/', ' ')
        if (needle.isBlank()) return null

        // Direct hit first — fastest path when the user pasted a relative path.
        val direct = File(root, needle)
        if (direct.isFile) return direct

        var best: File? = null
        var bestDepth = Int.MAX_VALUE
        val skipDirs = setOf(
            "build", ".gradle", ".idea", "node_modules", ".git", "out", "dist",
            "vendor", "third_party", "captures", ".cxx",
        )
        root.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs && !dir.name.startsWith(".tmp") }
            .filter { it.isFile }
            .forEach { f ->
                if (!matchesMention(f, needle)) return@forEach
                val depth = f.absolutePath.count { it == File.separatorChar }
                if (depth < bestDepth) {
                    best = f
                    bestDepth = depth
                }
            }
        return best
    }

    private fun matchesMention(file: File, needle: String): Boolean {
        val path = file.path.replace('\\', '/')
        if (path.endsWith("/$needle") || path.endsWith(needle)) return true
        if (file.name.equals(needle, ignoreCase = true)) return true
        // Allow base-name match when the user types just `MainActivity.kt`.
        if (!needle.contains('/') && file.name == needle) return true
        return false
    }
}
