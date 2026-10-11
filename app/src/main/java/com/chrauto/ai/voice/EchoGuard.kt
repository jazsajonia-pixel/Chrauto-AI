package com.chrauto.ai.voice

/**
 * Barge-in support: while Chron is talking the microphone stays open, so the recogniser also hears
 * Chron's own voice coming out of the speaker. This decides whether a transcript heard during speech
 * is really the user talking over Chron (interrupt) or just an echo of what Chron is saying.
 *
 * Pure logic so it can be unit-tested.
 */
object EchoGuard {
    /** Words that mean "let me talk" when they open an utterance. */
    private val interruptWords = setOf(
        "stop", "wait", "cancel", "hold", "pause", "enough", "quiet", "shush", "shh", "hey", "excuse", "actually",
        "sandali", "teka", "tama", "hinto" // Filipino: wait / hold on / enough / stop
    )

    fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotBlank() }

    /**
     * @param heard   transcript (partial or final) captured while Chron was speaking.
     * @param spoken  everything Chron has said / queued in the current reply.
     * @param headset true when audio goes to headphones, so there is (almost) no echo.
     */
    fun isUser(heard: String, spoken: String, headset: Boolean, wakeName: String = "Chron"): Boolean {
        val h = words(heard)
        if (h.isEmpty()) return false
        val spokenSet = words(spoken).toSet()
        // Saying Chron's name always interrupts.
        if (WakeWord.find(heard, wakeName) != null && WakeWord.normalize(h.first()) !in spokenSet) return true
        val first = h.first()
        if (first in interruptWords && first !in spokenSet) return true
        val novel = h.count { it !in spokenSet }
        return if (headset) {
            novel >= 1 && h.size >= 2
        } else {
            // On the loudspeaker require several words that Chron did not say.
            novel >= 3 && novel.toFloat() / h.size >= 0.6f
        }
    }

    /** Removes leading echo words (things Chron said) and a leading wake word from a barge-in transcript. */
    fun stripEcho(heard: String, spoken: String, wakeName: String = "Chron"): String {
        val raw = heard.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val spokenSet = words(spoken).toSet()
        var i = 0
        while (i < raw.size) {
            val w = words(raw[i])
            if (w.isEmpty() || w.all { it in spokenSet }) i++ else break
        }
        // Only strip a real echo fragment (3+ words); short overlaps are probably the user's own words.
        val rest = if (i >= 3 && i < raw.size) raw.drop(i).joinToString(" ") else heard.trim()
        return WakeWord.find(rest, wakeName)?.command ?: rest
    }
}
