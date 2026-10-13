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
    val supportsVision: Boolean get() = false
    suspend fun plan(task: String, observation: UiObservation, history: List<String> = emptyList()): Result<ActionPlan>
    /** Free-form completion used by the Chron voice assistant (expects a JSON reply). */
    suspend fun chat(system: String, user: String): Result<String> = Result.failure(UnsupportedOperationException("chat not supported"))
    /**
     * Streams a plain-text reply for the live voice assistant, calling [onDelta] with each new piece
     * of text (on a background thread). Returns the full text. Falls back to [chat] when a provider
     * can't stream.
     */
    suspend fun chatStream(system: String, user: String, onDelta: (String) -> Unit): Result<String> =
        chat(system, user).onSuccess(onDelta)
    /** Opens the TLS connection early so the first real request is faster. */
    suspend fun warmUp() {}
}

const val DEFAULT_MODEL = "gemini-3.8-flash"
val SUGGESTED_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview", "gemini-2.5-flash")

class GeminiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val installedApps: String = "",
    private val timeoutMs: Int = 35_000
) : AiProvider {
    override val supportsVision: Boolean get() = true
    /** Lower thinking = much faster steps. Dropped automatically if a model rejects it. */
    @Volatile private var thinking: JSONObject? = thinkingFor(model)

    override suspend fun plan(task: String, observation: UiObservation, history: List<String>): Result<ActionPlan> = runCatching {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }
        val text = try { call(task, observation, history) } catch (e: ProviderHttpException) {
            if (thinking != null && e.code == 400 && e.message.orEmpty().contains("thinking", ignoreCase = true)) { thinking = null; call(task, observation, history) } else throw e
        }
        ActionPlanParser.parse(text)
    }

    override suspend fun chat(system: String, user: String): Result<String> = runCatching {
        val modelId = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }
        val request = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.6)
                thinking?.let { put("thinkingConfig", it) }
            })
        }
        extractText(Http.request("Gemini", "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent", mapOf("x-goog-api-key" to apiKey), request, timeoutMs))
    }

    override suspend fun chatStream(system: String, user: String, onDelta: (String) -> Unit): Result<String> {
        val modelId = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }
        fun body() = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 600)
                thinking?.let { put("thinkingConfig", it) }
            })
        }
        return Http.streamWithFallback(
            stream = {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse"
                Http.streamText("Gemini", url, mapOf("x-goog-api-key" to apiKey), body(), Sse::geminiDelta, onDelta)
            },
            fallback = { chat(system, user).getOrThrow().also(onDelta) }
        )
    }

    override suspend fun warmUp() = Http.warm("https://generativelanguage.googleapis.com/")

    private suspend fun call(task: String, observation: UiObservation, history: List<String>): String {
        val modelId = model.trim().removePrefix("models/").ifBlank { DEFAULT_MODEL }
        val request = buildPlanRequest(task, observation, history)
        val body = Http.request("Gemini", "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            mapOf("x-goog-api-key" to apiKey), request, timeoutMs)
        return extractText(body)
    }

    internal fun buildPlanRequest(task: String, observation: UiObservation, history: List<String> = emptyList()): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", PromptBuilder.user(task, observation, history, installedApps)))
        observation.visionFrame?.let { frame ->
            require(frame.jpeg.isNotEmpty()) { "Screen-aware mode produced an empty screenshot" }
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", java.util.Base64.getEncoder().encodeToString(frame.jpeg))))
        }
        return JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PromptBuilder.system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseSchema", schema())
                put("temperature", 0.2)
                thinking?.let { put("thinkingConfig", it) }
            })
        }
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
                put("x", JSONObject().put("type", "number").put("nullable", true).put("description", "Image-pixel x for tap_at"))
                put("y", JSONObject().put("type", "number").put("nullable", true).put("description", "Image-pixel y for tap_at"))
                put("confidence", JSONObject().put("type", "number").put("nullable", true).put("description", "Visual target confidence from 0.80 to 1.0"))
                put("target_label", str("Short label visible on the visual target"))
                put("package_name", str("Android package for open_app"))
                put("text", str("Text for type_text"))
                put("direction", str("Scroll direction", listOf("UP", "DOWN", "LEFT", "RIGHT")))
                put("submit", JSONObject().put("type", "boolean").put("nullable", true).put("description", "For type_text: press Enter/Search right after typing"))
                put("milliseconds", JSONObject().put("type", "integer").put("nullable", true).put("description", "Wait duration"))
                put("summary", str("Summary for finish"))
                put("evidence", str("Exact on-screen text proving completion"))
                put("reason", str("Reason for ask_user"))
                put("message", str("Message for report_error"))
                put("thought", str("One short sentence describing what you are doing, shown to the user"))
            })
            put("required", JSONArray(listOf("action")))
            put("propertyOrdering", JSONArray(listOf("thought", "action", "node_id", "x", "y", "confidence", "target_label", "package_name", "text", "submit", "direction", "milliseconds", "summary", "evidence", "reason", "message")))
        }
    }
}

object ActionPlanParser {
    val ACTIONS = listOf("open_app", "tap", "tap_at", "type_text", "press_enter", "scroll", "press_back", "wait", "ask_user", "finish", "report_error")
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
            "tap_at" -> AgentAction.TapAt(value("x").toFloat(), value("y").toFloat(), value("confidence").toFloat(), opt("target_label").orEmpty())
            "type_text" -> AgentAction.TypeText(value("node_id"), opt("text") ?: "", submit = opt("submit")?.toBooleanStrictOrNull() == true)
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
