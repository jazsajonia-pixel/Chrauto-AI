package com.chrauto.ai.voice

/**
 * Finds the wake word ("Chron" by default) at the start of an utterance and returns what follows.
 * Speech recognisers rarely spell a made-up name the same way twice, so common sound-alikes are accepted,
 * but only at the start of the sentence (optionally after "hey"/"ok") to avoid false triggers.
 */
object WakeWord {
    data class Match(val command: String) { val wakeOnly get() = command.isBlank() }

    private val greetings = setOf("hey", "hi", "ok", "okay", "yo", "hello", "oi", "a", "and", "so", "um", "uh")
    private val chronAliases = setOf(
        "chron", "kron", "cron", "crohn", "crohns", "krohn", "chrone", "crone", "khron", "chrono", "krone",
        "kronn", "chronn", "cronn", "kran", "cronk", "krono", "corn", "crawn", "kraun", "chrom", "chroma", "cronos", "chronos", "kronos"
    )
    /** Only accepted right after a greeting ("hey chrome") because "chrome" is also a real command word. */
    private val greetingOnlyAliases = setOf("chrome", "crown", "clone", "chrom")

    fun normalize(token: String) = token.lowercase().filter { it.isLetterOrDigit() }

    fun aliasesFor(name: String): Set<String> {
        val n = normalize(name).ifBlank { "chron" }
        return if (n == "chron") chronAliases + n else setOf(n)
    }

    fun find(utterance: String, name: String = "Chron"): Match? {
        val raw = utterance.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (raw.isEmpty()) return null
        val aliases = aliasesFor(name)
        val target = normalize(name).ifBlank { "chron" }
        var i = 0
        var sawGreeting = false
        while (i < raw.size && i < 3) {
            val t = normalize(raw[i])
            val isWake = t in aliases || (target.length >= 4 && t.length >= 4 && levenshtein(t, target) <= 1) ||
                (sawGreeting && target == "chron" && t in greetingOnlyAliases)
            if (isWake) {
                val rest = raw.drop(i + 1).joinToString(" ").trim().trimStart(',', '.', '!', '?', ':', ';', '-', ' ')
                return Match(rest.removePrefix("please ").trim())
            }
            if (t in greetings) { sawGreeting = true; i++ } else return null
        }
        return null
    }

    fun findAny(candidates: List<String>, name: String = "Chron"): Match? = candidates.firstNotNullOfOrNull { find(it, name) }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
