package com.chrauto.ai.provider

import com.chrauto.ai.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface AiProvider {
    suspend fun plan(task: String, observation: UiObservation, history: List<String> = emptyList()): Result<ActionPlan>
}

const val DEFAULT_MODEL = "gemini-3.8-flash"
val SUGGESTED_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview", "gemini-2.5-flash")

class GeminiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val installedApps: String = "",
    private val timeoutMs: Int = 45_000
) : AiProvider {
    override suspend fun plan(task: String, observation: UiObservation, history: List<String>): Result<ActionPlan> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Gemini API key is missing" }
            val modelId = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
            val request = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt(task, observation, history))))))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema())
                    put("temperature", 0.2)
                })
            }
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("x-goog-api-key", apiKey)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini HTTP $code: ${errorMessage(body)}")
                ActionPlanParser.parse(extractText(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        JSONObject(body).getJSONObject("error").getString("message")
    }.getOrElse { body.take(300) }.take(400)

    private fun extractText(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            error(if (reason.isNotBlank()) "Gemini blocked the request ($reason)" else "Gemini returned no candidates")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: error("Gemini returned an empty response")
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought", false) && part.has("text")) return part.getString("text")
        }
        error("Gemini response did not contain text")
    }

    private fun prompt(task: String, observation: UiObservation, history: List<String>) = buildString {
        appendLine("You are Chrauto, a careful Android UI agent operating a real phone through accessibility actions.")
        appendLine("User task: $task")
        appendLine()
        if (installedApps.isNotBlank()) {
            appendLine("Launchable apps (label => package):")
            appendLine(installedApps)
            appendLine()
        }
        if (history.isNotEmpty()) {
            appendLine("Previous steps in this run (oldest first):")
            history.takeLast(12).forEachIndexed { i, h -> appendLine("${i + 1}. $h") }
            appendLine()
        }
        appendLine("Current screen (accessibility tree):")
        appendLine(observation.compactText())
        appendLine()
        appendLine(
            """
            Rules:
            - Return exactly ONE next action as JSON matching the schema. Fill only the fields the action needs.
            - Use node ids exactly as listed (e.g. "0.2.1"). Prefer nodes marked clickable=true for taps and editable=true for typing.
            - open_app needs package_name. Chrome is com.android.chrome. If you are on the Chrauto AI app itself, open the target app first.
            - type_text replaces the field text. After typing a search query, tap the search/go button or a suggestion to submit it.
            - scroll needs direction (UP, DOWN, LEFT, RIGHT); DOWN reveals content further down.
            - wait needs milliseconds between 300 and 5000; use it when the screen is loading.
            - finish only when the task is visibly complete. Put a short exact substring copied from the current screen in "evidence".
            - Use ask_user (with reason) before sending, publishing, purchasing, deleting, changing security settings, or important submissions.
            - Use report_error (with message) if the task is impossible.
            - If a previous step was rejected or failed, choose a different approach.
            """.trimIndent()
        )
    }

    /** Gemini `responseSchema` uses the OpenAPI subset: nullable fields use `nullable: true`, not type arrays. */
    internal fun schema(): JSONObject {
        fun str(description: String, enum: List<String>? = null) = JSONObject().apply {
            put("type", "string")
            put("nullable", true)
            put("description", description)
            if (enum != null) put("enum", JSONArray(enum))
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().put("type", "string").put("enum", JSONArray(ActionPlanParser.ACTIONS)))
                put("node_id", str("Target node id for tap, type_text, or scroll"))
                put("package_name", str("Android package for open_app"))
                put("text", str("Text for type_text"))
                put("direction", str("Scroll direction", listOf("UP", "DOWN", "LEFT", "RIGHT")))
                put("milliseconds", JSONObject().put("type", "integer").put("nullable", true).put("description", "Wait duration"))
                put("summary", str("Summary for finish"))
                put("evidence", str("Exact on-screen text proving completion"))
                put("reason", str("Reason for ask_user"))
                put("message", str("Message for report_error"))
                put("thought", str("One short sentence describing what you are doing, shown to the user"))
            })
            put("required", JSONArray(listOf("action")))
            put("propertyOrdering", JSONArray(listOf("thought", "action", "node_id", "package_name", "text", "direction", "milliseconds", "summary", "evidence", "reason", "message")))
        }
    }
}

object ActionPlanParser {
    val ACTIONS = listOf("open_app", "tap", "type_text", "scroll", "press_back", "wait", "ask_user", "finish", "report_error")

    fun parse(raw: String): ActionPlan {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json: JsonObject = Json.parseToJsonElement(cleaned).jsonObject
        fun opt(name: String): String? {
            val element = json[name] ?: return null
            if (element is JsonNull) return null
            return (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }
        fun value(name: String): String = opt(name) ?: error("Model response is missing \"$name\"")
        val action = when (val type = value("action")) {
            "open_app" -> AgentAction.OpenApp(value("package_name"))
            "tap" -> AgentAction.Tap(value("node_id"))
            "type_text" -> AgentAction.TypeText(value("node_id"), opt("text") ?: "")
            "scroll" -> AgentAction.Scroll(opt("node_id"), Direction.valueOf(value("direction").uppercase()))
            "press_back" -> AgentAction.PressBack
            "wait" -> AgentAction.Wait(opt("milliseconds")?.toDoubleOrNull()?.toLong()?.coerceIn(300, 5_000) ?: 1_000)
            "ask_user" -> AgentAction.AskUser(opt("reason") ?: opt("message") ?: "Confirmation needed")
            "finish" -> AgentAction.Finish(opt("summary") ?: "Task complete", opt("evidence") ?: "")
            "report_error" -> AgentAction.ReportError(opt("message") ?: opt("reason") ?: "The model reported an error")
            else -> error("Unsupported action: $type")
        }
        return ActionPlan(action, opt("thought"))
    }
}
