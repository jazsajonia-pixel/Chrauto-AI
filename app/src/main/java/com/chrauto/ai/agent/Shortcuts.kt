package com.chrauto.ai.agent

import com.chrauto.ai.voice.WakeWord

/** Local fast paths that skip an AI round-trip. */
object Shortcuts {
    data class OpenApp(val packageName: String, val label: String, val rest: String)

    private val openPattern = Regex("^(?:please\\s+)?(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?(?:\\s*(?:,|\\band\\b|\\bthen\\b)\\s*(.*))?[.!]?$", RegexOption.IGNORE_CASE)

    /** "Open YouTube and search lo-fi" → YouTube's package + "search lo-fi". [apps] is label → package. */
    fun openApp(task: String, apps: Map<String, String>): OpenApp? {
        if (apps.isEmpty()) return null
        val m = openPattern.find(task.trim()) ?: return null
        val query = m.groupValues[1].trim()
        val rest = m.groupValues.getOrNull(2).orEmpty().trim().trimEnd('.', '!', '?').trim()
        val pkg = matchApp(query, apps) ?: return null
        return OpenApp(pkg.second, pkg.first, rest)
    }

    fun matchApp(query: String, apps: Map<String, String>): Pair<String, String>? {
        val q = squash(query)
        if (q.isBlank()) return null
        val entries = apps.entries.map { Triple(it.key, it.value, squash(it.key)) }
        entries.firstOrNull { it.third == q }?.let { return it.first to it.second }
        entries.filter { it.third.startsWith(q) && q.length >= 3 }.minByOrNull { it.third.length }?.let { return it.first to it.second }
        entries.filter { q.length >= 4 && it.third.length >= 4 && WakeWord.levenshtein(it.third, q) <= if (q.length > 6) 2 else 1 }
            .minByOrNull { WakeWord.levenshtein(it.third, q) }?.let { return it.first to it.second }
        return null
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
