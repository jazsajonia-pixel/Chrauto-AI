package com.chrauto.ai.voice

import com.chrauto.ai.provider.AiConfig
import com.chrauto.ai.provider.ActionPlanParser
import com.chrauto.ai.provider.ProviderFactory
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

    data class Context(val runningTask: String?, val conversation: List<Pair<String, String>>)

    suspend fun route(text: String, config: AiConfig?, ctx: Context): Route {
        quickRoute(text)?.let { return it }
        if (config == null || !config.isUsable) return Route.Task(text)
        val system = """
            You are Chron, a friendly, upbeat, concise voice assistant living on the user's Android phone. You can operate apps for them.
            Decide what the user wants and reply with ONLY a JSON object:
            {"type": "task" | "answer" | "stop", "task": string, "reply": string}
            - "task": they want something done ON THE PHONE (open/search/play/message/settings…). Put a clear instruction for the phone agent in "task" and a 2-5 word acknowledgement in "reply".
            - "answer": a question, chit-chat, or anything you can answer without touching the phone. "reply" = 1-3 short spoken sentences. No markdown, lists, or emojis.
            - "stop": they want the current task stopped.
            Current time: ${SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}.
            ${ctx.runningTask?.let { "A phone task is currently running: \"$it\". If they add details or change their mind, return a new full \"task\"." } ?: "No phone task is running."}
        """.trimIndent()
        val user = buildString {
            if (ctx.conversation.isNotEmpty()) {
                appendLine("Conversation so far:")
                ctx.conversation.takeLast(6).forEach { (u, a) -> appendLine("User: $u"); appendLine("Chron: $a") }
            }
            append("User: ").append(text)
        }
        val reply = ProviderFactory.create(config).chat(system, user).getOrElse { return Route.Task(text) }
        return parse(reply) ?: Route.Answer(reply.take(300))
    }

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
