/*
 *  This file is part of AndroidCodeStudio.
 *
 *  Lightweight safety / connectivity helpers used before AI requests.
 */

package com.tom.rv2ide.artificial.safety

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object PromptSafety {

    /**
     * Heuristics for detecting probable secrets in a chat prompt so we can warn
     * the user before shipping their prompt to a third-party model. Errs on the
     * side of false positives — better one extra dialog than a leaked key.
     */
    private val SECRET_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{20,}"),                    // OpenAI / OpenRouter style
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),                   // Google
        Regex("AKIA[0-9A-Z]{16}"),                            // AWS access key id
        Regex("xox[abps]-[0-9A-Za-z\\-]{10,}"),               // Slack
        Regex("ghp_[A-Za-z0-9]{30,}"),                        // GitHub personal access token
        Regex("github_pat_[A-Za-z0-9_]{30,}"),                // GitHub fine-grained PAT
        Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
        Regex("(?i)(?:api[_-]?key|secret|token|password)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}"),
    )

    data class Detection(val matchedPattern: String, val sample: String)

    /** Return any probable secrets found in [prompt]. Empty list = clean. */
    fun findSecrets(prompt: String): List<Detection> {
        if (prompt.isBlank()) return emptyList()
        val hits = mutableListOf<Detection>()
        for (pattern in SECRET_PATTERNS) {
            for (m in pattern.findAll(prompt)) {
                val raw = m.value
                val masked = if (raw.length > 12) raw.take(6) + "…" + raw.takeLast(4) else raw
                hits.add(Detection(matchedPattern = pattern.pattern, sample = masked))
                if (hits.size >= 5) return hits
            }
        }
        return hits
    }

    /** Best-effort online check. Returns false when there's clearly no connectivity. */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            true // Don't block the user if we can't tell.
        }
    }
}
