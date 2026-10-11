package com.chrauto.ai.provider

import com.chrauto.ai.agent.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

interface AiProvider {
    suspend fun plan(task: String, observation: UiObservation, history: List<String> = emptyList()): Result<ActionPlan>
}

const val DEFAULT_MODEL = "gemini-3.8-flash"
val SUGGESTED_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview", "gemini-2.5-flash")

class GeminiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val installedApps: String = "",
    private val timeoutMs: Int = 35_000
) : AiProvider {
    /** Lower thinking = much faster steps. Dropped automatically if a model rejects it. */
    @Volatile private var thinking: JSONObject? = thinkingFor(model)

    override suspend fun plan(task: String, observation: UiObservation, history: List<String>): Result<ActionPlan> = runCatching {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }
        val text = try { call(task, observation, history) } catch (e: ProviderHttpException) {
            if (thinking != null && e.code == 400 && e.message.orEmpty().contains("thinking", ignoreCase = true)) { thinking = null; call(task, observation, history) } else throw e
        }
        ActionPlanParser.parse(text)
    }

    private suspend fun call(task: String, observation: UiObservation, history: List<String>): String {
        val modelId = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }
        val request = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PromptBuilder.system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", PromptBuilder.user(task, observation, history, installedApps))))))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseSchema", schema())
                put("temperature", 0.2)
                thinking?.let { put("thinkingConfig", it) }
            })
        }
        val body = Http.request("Gemini", "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            mapOf("x-goog-api-key" to apiKey), request, timeoutMs)
        return extractText(body)
    }

    private fun thinkingFor(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            m.startsWith("gemini-3") -> JSONObject().put("thinkingLevel", "low")
            m.startsWith("gemini-2.5-flash") -> JSONObject().put("thinkingBudget", 0)
            else -> null
        }
    }

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
                put("node_id", str("Target node id for tap, type_text, press_enter, or scroll"))
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
    val ACTIONS = listOf("open_app", "tap", "type_text", "press_enter", "scroll", "press_back", "wait", "ask_user", "finish", "report_error")
    private val thinkTags = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

    /** Pulls the JSON object out of replies that may include markdown fences or <think> blocks. */
    fun extractJson(raw: String): String {
        val text = raw.replace(thinkTags, "").trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("The AI reply wasn't JSON: ${text.take(120)}")
        return text.substring(start, end + 1)
    }

    fun parse(raw: String): ActionPlan {
        val json: JsonObject = Json.parseToJsonElement(extractJson(raw)).jsonObject
        fun opt(name: String): String? {
            val element = json[name] ?: return null
            if (element is JsonNull) return null
            return (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }
        fun value(name: String): String = opt(name) ?: error("Model response is missing \"$name\"")
        val action = when (val type = value("action")) {
            "open_app" -> AgentAction.OpenApp(value("package_name"))
            "tap", "click" -> AgentAction.Tap(value("node_id"))
            "type_text" -> AgentAction.TypeText(value("node_id"), opt("text") ?: "")
            "scroll" -> AgentAction.Scroll(opt("node_id"), Direction.valueOf(value("direction").uppercase()))
            "press_enter", "submit", "enter" -> AgentAction.PressEnter(opt("node_id"))
            "press_back", "back" -> AgentAction.PressBack
            "wait" -> AgentAction.Wait(opt("milliseconds")?.toDoubleOrNull()?.toLong()?.coerceIn(300, 5_000) ?: 1_000)
            "ask_user" -> AgentAction.AskUser(opt("reason") ?: opt("message") ?: "Confirmation needed")
            "finish" -> AgentAction.Finish(opt("summary") ?: "Task complete", opt("evidence") ?: "")
            "report_error" -> AgentAction.ReportError(opt("message") ?: opt("reason") ?: "The model reported an error")
            else -> error("Unsupported action: $type")
        }
        return ActionPlan(action, opt("thought"))
    }
}
