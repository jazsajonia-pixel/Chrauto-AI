package com.chrauto.ai.provider

import com.chrauto.ai.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface AiProvider {
    suspend fun plan(task: String, observation: UiObservation): Result<ActionPlan>
}

class GeminiProvider(
    private val apiKey: String,
    private val model: String = "gemini-3.8-flash",
    private val timeoutMs: Int = 30_000
) : AiProvider {
    override suspend fun plan(task: String, observation: UiObservation): Result<ActionPlan> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Gemini API key is missing" }
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val request = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(task, observation))))))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema())
                })
            }
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) error("Gemini HTTP ${connection.responseCode}: ${body.take(300)}")
            ActionPlanParser.parse(JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
        }
    }

    private fun prompt(task: String, observation: UiObservation) = """
        You are a constrained Android UI agent. User task: $task
        Current accessibility observation:
        ${observation.compactText()}
        Return exactly one action. Use semantic node ids from the observation. Never claim success without evidence in the current observation. Ask for confirmation for sending, publishing, purchasing, deleting, security changes, or important submissions. To open an app, use package names only when known; Chrome is com.android.chrome.
    """.trimIndent()

    private fun schema() = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().put("type", "string").put("enum", JSONArray(arrayOf("open_app", "tap", "type_text", "scroll", "press_back", "wait", "ask_user", "finish", "report_error"))))
            put("node_id", JSONObject().put("type", arrayOf("string", "null")))
            put("package_name", JSONObject().put("type", arrayOf("string", "null")))
            put("text", JSONObject().put("type", arrayOf("string", "null")))
            put("direction", JSONObject().put("type", arrayOf("string", "null")).put("enum", JSONArray(arrayOf("UP", "DOWN", "LEFT", "RIGHT", null))))
            put("milliseconds", JSONObject().put("type", "integer"))
            put("summary", JSONObject().put("type", arrayOf("string", "null")))
            put("evidence", JSONObject().put("type", arrayOf("string", "null")))
            put("reason", JSONObject().put("type", arrayOf("string", "null")))
            put("message", JSONObject().put("type", arrayOf("string", "null")))
        })
        put("required", JSONArray(arrayOf("action")))
    }
}

object ActionPlanParser {
    fun parse(raw: String): ActionPlan {
        val json = JSONObject(raw.trim().removePrefix("```").removeSuffix("```").trim())
        return ActionPlan(when (json.getString("action")) {
            "open_app" -> AgentAction.OpenApp(json.getString("package_name"))
            "tap" -> AgentAction.Tap(json.getString("node_id"))
            "type_text" -> AgentAction.TypeText(json.getString("node_id"), json.getString("text"))
            "scroll" -> AgentAction.Scroll(json.optString("node_id").takeIf { it.isNotBlank() }, Direction.valueOf(json.getString("direction")))
            "press_back" -> AgentAction.PressBack
            "wait" -> AgentAction.Wait(json.getLong("milliseconds"))
            "ask_user" -> AgentAction.AskUser(json.getString("reason"))
            "finish" -> AgentAction.Finish(json.getString("summary"), json.getString("evidence"))
            "report_error" -> AgentAction.ReportError(json.getString("message"))
            else -> error("Unsupported action")
        })
    }
}
