package com.chrauto.ai.voice

import com.chrauto.ai.provider.AiConfig
import com.chrauto.ai.provider.ActionPlanParser
import com.chrauto.ai.provider.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Route {
    data class Task(val task: String, val ack: String? = null) : Route
    data class Answer(val reply: String) : Route
    data class Stop(val reply: String = "Okay, stopped.") : Route
}

/** Decides whether a spoken sentence is a phone task, a question to answer out loud, or "stop". */
object CommandRouter {
    private val stop = Regex("^(stop|cancel|never ?mind|quit|abort|halt|enough|pause)( it| that| the task| now| please)*$")
    private val taskStart = Regex(
        "^(please )?(open|launch|start|run|go to|go back|go home|search|google|find|look up|play|pause music|call|dial|text|send|message|reply|" +
            "turn|switch|enable|disable|set|show me|take a|tap|click|press|scroll|navigate|download|install|uninstall|watch|" +
            "listen to|read my|check my|add|create|write|type|post|share|book|order|close|mute|unmute|increase|decrease|lower|raise|" +
            "connect|disconnect|clear|delete|remove|save|copy|paste|select|like|follow|subscribe|record|capture)\\b"
    )

    fun quickRoute(text: String): Route? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        if (t.isBlank()) return null
        if (stop.matches(t)) return Route.Stop()
        if (taskStart.containsMatchIn(t)) return Route.Task(text.trim())
        return null
    }

    data class Context(
        val runningTask: String?,
        val conversation: List<Pair<String, String>>,
        val language: String = "",
        val screenApp: String? = null
    )

    /**
     * Streaming-friendly reply format. The first word tells us what kind of reply it is, so Chron can
     * start speaking an answer while the rest is still arriving.
     */
    fun systemPrompt(ctx: Context) = """
        You are Chron, a friendly, upbeat, concise voice assistant living on the user's Android phone. You can operate apps for them.
        You are in a live spoken conversation: keep replies short and natural, like talking. No markdown, lists, emojis, or URLs.
        Reply in the same language the user speaks${if (ctx.language.isNotBlank()) " (their voice language is ${ctx.language})" else ""}.
        Start your reply with exactly one of these headers, then the content:
        ANSWER: <1-3 short spoken sentences>   — questions, chit-chat, advice, anything you can answer without touching the phone.
        TASK: <clear instruction for the phone agent> || <2-5 word spoken acknowledgement>   — they want something done ON THE PHONE (open/search/play/message/settings…).
        STOP: <very short reply>   — they want the current phone task stopped.
        Current time: ${SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}.
        ${ctx.screenApp?.let { "The phone currently shows the app: $it." } ?: ""}
        ${ctx.runningTask?.let { "A phone task is currently running: \"$it\". If they add details or change their mind, reply with a new full TASK." } ?: "No phone task is running."}
    """.trimIndent()

    fun userPrompt(text: String, ctx: Context) = buildString {
        if (ctx.conversation.isNotEmpty()) {
            appendLine("Conversation so far:")
            ctx.conversation.takeLast(8).forEach { (u, a) -> if (u.isNotBlank()) appendLine("User: $u"); appendLine("Chron: $a") }
        }
        append("User: ").append(text)
    }

    /** Non-streaming routing (kept for callers that don't need live speech). */
    suspend fun route(text: String, config: AiConfig?, ctx: Context, provider: (AiConfig) -> AiProvider): Route {
        quickRoute(text)?.let { return it }
        if (config == null || !config.isUsable) return Route.Task(text)
        val reply = provider(config).chat(systemPrompt(ctx), userPrompt(text, ctx)).getOrElse { return Route.Task(text) }
        return ReplyStream().apply { push(reply) }.finish()
    }

    /** Legacy JSON format: {"type": "task|answer|stop", "task": ..., "reply": ...}. */
    fun parse(raw: String): Route? = runCatching {
        val o = Json.parseToJsonElement(ActionPlanParser.extractJson(raw)).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        when (s("type")?.lowercase()) {
            "task" -> s("task")?.let { Route.Task(it, s("reply")) }
            "answer" -> s("reply")?.let { Route.Answer(it) }
            "stop" -> Route.Stop(s("reply") ?: "Okay, stopped.")
            else -> null
        }
    }.getOrNull()
}

/**
 * Incrementally parses a streamed router reply ("ANSWER: …", "TASK: … || …", "STOP: …").
 * Also accepts the old JSON format and plain text (treated as an answer).
 */
class ReplyStream {
    enum class Kind { ANSWER, TASK, STOP, JSON }

    var kind: Kind? = null
        private set
    private val raw = StringBuilder()
    private var bodyStart = -1
    private var emitted = 0

    /** Spoken answer text received so far (only for [Kind.ANSWER]). */
    val answer: String get() = if (kind == Kind.ANSWER && bodyStart >= 0) raw.substring(bodyStart).let(::stripThink).trimStart() else ""

    /** Feeds streamed text; returns newly available answer text to speak (empty for tasks/JSON). */
    fun push(delta: String): String {
        raw.append(delta)
        if (kind == null) detect(final = false)
        if (kind != Kind.ANSWER) return ""
        val a = answer
        if (a.length <= emitted) return ""
        // Hold back a possibly-unfinished "<think" tag.
        val safeEnd = a.lastIndexOf('<').takeIf { it >= emitted && !a.substring(it).contains('>') } ?: a.length
        val out = a.substring(emitted, safeEnd)
        emitted = safeEnd
        return out
    }

    fun finish(): Route {
        if (kind == null) detect(final = true)
        val text = stripThink(raw.toString()).trim()
        return when (kind) {
            Kind.JSON -> CommandRouter.parse(text) ?: Route.Answer(text.take(400))
            Kind.TASK -> {
                val body = stripThink(raw.substring(bodyStart.coerceAtLeast(0))).trim()
                val parts = body.split("||", limit = 2)
                val task = parts[0].trim().trim('"')
                if (task.isBlank()) Route.Answer("Sorry, what should I do?") else Route.Task(task, parts.getOrNull(1)?.trim()?.trim('"')?.takeIf { it.isNotBlank() })
            }
            Kind.STOP -> Route.Stop(stripThink(raw.substring(bodyStart.coerceAtLeast(0))).trim().ifBlank { "Okay, stopped." })
            else -> Route.Answer(answer.trim().ifBlank { text }.take(600))
        }
    }

    /** Text not yet returned by [push] once the stream ended (for the final spoken piece). */
    fun remaining(): String { val a = answer; return if (a.length > emitted) a.substring(emitted).also { emitted = a.length } else "" }

    private fun detect(final: Boolean) {
        val s = raw.toString()
        // Reasoning models may stream <think>…</think> first: wait until it's closed.
        val thinkOpen = s.indexOf("<think>", ignoreCase = true)
        val thinkClose = s.indexOf("</think>", ignoreCase = true)
        if (thinkOpen >= 0 && thinkClose < 0 && !final) return
        var i = if (thinkClose >= 0) thinkClose + "</think>".length else 0
        fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }
        skipSpace()
        if (s.startsWith("```", i)) {
            val nl = s.indexOf('\n', i)
            if (nl < 0 && !final) return
            i = if (nl < 0) s.length else nl + 1
            skipSpace()
        }
        val rest = s.substring(i)
        if (rest.isEmpty()) { if (final) { kind = Kind.ANSWER; bodyStart = s.length }; return }
        if (rest.startsWith("{")) { kind = Kind.JSON; return }
        val m = HEADER.find(rest)
        if (m != null) {
            kind = when (m.groupValues[1].uppercase()) { "TASK" -> Kind.TASK; "STOP" -> Kind.STOP; else -> Kind.ANSWER }
            bodyStart = i + m.range.last + 1
            return
        }
        // Still might become a header ("ANS…")? Wait for more text unless the stream ended.
        val bare = rest.trimStart('*', ' ').uppercase()
        val couldBeHeader = rest.length < 10 && HEADER_WORDS.any { it.startsWith(bare) || bare.startsWith(it) }
        if (!couldBeHeader || final) { kind = Kind.ANSWER; bodyStart = i }
    }

    private fun stripThink(s: String) = s.replace(THINK, "")

    private companion object {
        val HEADER = Regex("^\\**\\s*(ANSWER|TASK|STOP)\\s*\\**\\s*[:：-]\\s*", RegexOption.IGNORE_CASE)
        val HEADER_WORDS = listOf("ANSWER", "TASK", "STOP")
        val THINK = Regex("<think>[\\s\\S]*?(</think>|$)", RegexOption.IGNORE_CASE)
    }
}
