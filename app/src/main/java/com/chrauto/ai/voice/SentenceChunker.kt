package com.chrauto.ai.voice

/**
 * Splits a streamed AI reply into speakable pieces so Chron can start talking after the first
 * sentence instead of waiting for the whole answer. Very short sentences are merged with the next
 * one so speech doesn't sound choppy; very long ones are cut at a comma or space.
 */
class SentenceChunker(private val minChars: Int = 14, private val maxChars: Int = 180) {
    private val buf = StringBuilder()

    /** Adds streamed text and returns any complete pieces ready to speak. */
    fun push(delta: String): List<String> { buf.append(delta); return drain(final = false) }

    /** Returns whatever is left once the stream has ended. */
    fun flush(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val piece = clean(buf.substring(0, cut))
            buf.delete(0, cut)
            if (piece.isNotEmpty()) out += piece
        }
        if (final) {
            val rest = clean(buf.toString()); buf.clear()
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }

    private fun findCut(): Int? {
        for (i in buf.indices) {
            val c = buf[i]
            val atEnd = i + 1 < buf.length && buf[i + 1].isWhitespace()
            val sentenceEnd = (c in ".!?…。！？" && atEnd) || c == '\n'
            if (sentenceEnd && i + 1 >= minChars) return i + 1
        }
        if (buf.length > maxChars) {
            val comma = buf.lastIndexOf(", ", maxChars)
            if (comma >= minChars) return comma + 1
            val space = buf.lastIndexOf(" ", maxChars)
            return if (space >= minChars) space + 1 else maxChars
        }
        return null
    }

    /** Drops markdown the TTS would read out loud (asterisks, hashes, bullets, backticks). */
    private fun clean(s: String) = s.replace(Regex("[*_#`>]+"), "").replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\s+"), " ").trim()
}
