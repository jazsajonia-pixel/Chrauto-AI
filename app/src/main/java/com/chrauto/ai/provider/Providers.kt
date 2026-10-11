package com.chrauto.ai.provider

import com.chrauto.ai.agent.UiObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Wire protocol a provider speaks. */
enum class Protocol { GEMINI, OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC }

/** Catalog of supported AI providers. Model lists are suggestions; "Load models" fetches the live list. */
enum class ProviderKind(
    val id: String,
    val label: String,
    val protocol: Protocol,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val keyUrl: String,
    val pricing: String,
    val requiresKey: Boolean = true,
    val supportsJsonMode: Boolean = true
) {
    GEMINI("gemini", "Google Gemini", Protocol.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
        "gemini-3.8-flash", listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-pro-preview"),
        "https://aistudio.google.com/apikey", "Free tier"),
    GROQ("groq", "Groq", Protocol.OPENAI_CHAT, "https://api.groq.com/openai/v1",
        "openai/gpt-oss-120b", listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b"),
        "https://console.groq.com/keys", "Free tier · very fast"),
    OPENROUTER("openrouter", "OpenRouter", Protocol.OPENAI_CHAT, "https://openrouter.ai/api/v1",
        "nvidia/nemotron-3-super-120b-a12b:free",
        listOf("nvidia/nemotron-3-super-120b-a12b:free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "thinkingmachines/inkling-small:free", "openai/gpt-5.4-mini", "anthropic/claude-haiku-5-5"),
        "https://openrouter.ai/keys", "Free “:free” models"),
    MISTRAL("mistral", "Mistral", Protocol.OPENAI_CHAT, "https://api.mistral.ai/v1",
        "mistral-small-latest", listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest"),
        "https://console.mistral.ai/api-keys", "Free mode"),
    CEREBRAS("cerebras", "Cerebras", Protocol.OPENAI_CHAT, "https://api.cerebras.ai/v1",
        "gpt-oss-120b", listOf("gpt-oss-120b", "qwen-3.8-27b"),
        "https://cloud.cerebras.ai", "Free trial · very fast"),
    NVIDIA("nvidia", "NVIDIA NIM", Protocol.OPENAI_CHAT, "https://integrate.api.nvidia.com/v1",
        "openai/gpt-oss-120b", listOf("openai/gpt-oss-120b", "z-ai/glm-5.3", "moonshotai/kimi-k3"),
        "https://build.nvidia.com", "Free for prototyping", supportsJsonMode = false),
    OPENAI("openai", "OpenAI", Protocol.OPENAI_CHAT, "https://api.openai.com/v1",
        "gpt-5.4-mini", listOf("gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.5"),
        "https://platform.openai.com/api-keys", "Paid"),
    ANTHROPIC("anthropic", "Anthropic Claude", Protocol.ANTHROPIC, "https://api.anthropic.com/v1",
        "claude-haiku-5-5", listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"),
        "https://console.anthropic.com/settings/keys", "Paid"),
    XAI("xai", "xAI Grok", Protocol.OPENAI_RESPONSES, "https://api.x.ai/v1",
        "grok-4.3", listOf("grok-4.3", "grok-4.7", "grok-4.20-0309-non-reasoning"),
        "https://console.x.ai", "Paid"),
    DEEPSEEK("deepseek", "DeepSeek", Protocol.OPENAI_CHAT, "https://api.deepseek.com",
        "deepseek-flash", listOf("deepseek-flash", "deepseek-v4-pro"),
        "https://platform.deepseek.com/api_keys", "Low cost"),
    CUSTOM("custom", "Custom (OpenAI-compatible)", Protocol.OPENAI_CHAT, "http://192.168.1.10:11434/v1",
        "llama3.2", listOf("llama3.2", "qwen3", "gemma3"),
        "https://ollama.com", "Ollama, LM Studio, LiteLLM…", requiresKey = false, supportsJsonMode = false);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Everything needed to call one provider. */
data class AiConfig(
    val provider: ProviderKind = ProviderKind.GEMINI,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
    val baseUrl: String = provider.baseUrl
) {
    val isUsable get() = (!provider.requiresKey || apiKey.isNotBlank()) && model.isNotBlank()
}

object ProviderFactory {
    fun create(config: AiConfig, installedApps: String = ""): AiProvider {
        val key = config.apiKey.trim()
        val model = config.model.trim().ifBlank { config.provider.defaultModel }
        val base = (if (config.provider == ProviderKind.CUSTOM) config.baseUrl else config.provider.baseUrl).trim().trimEnd('/')
        return when (config.provider.protocol) {
            Protocol.GEMINI -> GeminiProvider(key, model, installedApps)
            Protocol.OPENAI_CHAT -> OpenAiChatProvider(config.provider, base, key, model, installedApps)
            Protocol.OPENAI_RESPONSES -> OpenAiResponsesProvider(config.provider, base, key, model, installedApps)
            Protocol.ANTHROPIC -> AnthropicProvider(base, key, model, installedApps)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared prompt
// ---------------------------------------------------------------------------------------------

object PromptBuilder {
    val system = """
        You are Chrauto, a careful and fast Android UI agent operating a real phone through accessibility actions.
        Each turn you get the user's task, recent steps, and the current screen as a list of nodes. Return exactly ONE next action.

        Actions and their fields:
        - open_app: package_name. (Chrome is com.android.chrome.) If the screen is the Chrauto AI app itself, open the target app first.
        - tap: node_id. Prefer nodes marked clickable=true; tapping a text label also works.
        - type_text: node_id (an editable=true field), text. This replaces the field's text.
        - press_enter: optional node_id of the field. Submits a search box / form (like the keyboard's Enter/Search key). Use right after type_text when searching.
        - scroll: direction (UP, DOWN, LEFT, RIGHT), optional node_id of a scrollable=true container. DOWN reveals content further down.
        - press_back
        - wait: milliseconds (300-5000) when the screen is still loading.
        - ask_user: reason. Use before sending, publishing, purchasing, deleting, changing security settings, or important submissions.
        - finish: summary, evidence (a short exact substring copied from the CURRENT screen proving completion).
        - report_error: message, if the task is impossible.

        Rules:
        - Use node ids exactly as listed (e.g. "0.2.1"). bounds=[left,top,right,bottom] are screen pixels and help you pick the right one of similar nodes.
        - Do not repeat an action that did not change the screen; try a different element or approach.
        - If a previous step was REJECTED or failed, fix the problem it describes.
        - Keep "thought" to one short sentence the user will see.

        Reply with ONLY a JSON object, no markdown, using these keys (omit or null the ones you don't need):
        {"thought": string, "action": string, "node_id": string, "package_name": string, "text": string,
         "direction": string, "milliseconds": integer, "summary": string, "evidence": string, "reason": string, "message": string}
    """.trimIndent()

    fun user(task: String, observation: UiObservation, history: List<String>, installedApps: String) = buildString {
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
        appendLine("Current screen:")
        append(observation.compactText())
    }
}

// ---------------------------------------------------------------------------------------------
// HTTP helper with friendly errors and one retry for transient failures
// ---------------------------------------------------------------------------------------------

class ProviderHttpException(val code: Int, message: String) : IOException(message)

internal object Http {
    suspend fun request(
        providerLabel: String,
        url: String,
        headers: Map<String, String>,
        body: JSONObject?,
        timeoutMs: Int = 35_000,
        attempts: Int = 2
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return@withContext once(providerLabel, url, headers, body, timeoutMs)
            } catch (e: ProviderHttpException) {
                if (e.code != 429 && e.code < 500) throw e
                last = e
            } catch (e: SocketTimeoutException) {
                last = IOException("$providerLabel took too long to answer")
            } catch (e: IOException) {
                last = IOException("Network problem talking to $providerLabel: ${e.message}")
            }
            if (attempt < attempts - 1) delay(1_500L * (attempt + 1))
        }
        throw last ?: IOException("$providerLabel request failed")
    }

    private fun once(label: String, url: String, headers: Map<String, String>, body: JSONObject?, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (body == null) "GET" else "POST"
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ProviderHttpException(code, friendly(label, code, errorMessage(text)))
            return text
        } finally {
            c.disconnect()
        }
    }

    fun errorMessage(body: String): String = runCatching {
        val root = JSONObject(body)
        when (val e = root.opt("error")) {
            is JSONObject -> e.optString("message").ifBlank { e.toString() }
            is String -> e
            else -> root.optString("message").ifBlank { root.optString("detail") }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(300) ?: body.take(300)

    private fun friendly(label: String, code: Int, message: String) = when (code) {
        401, 403 -> "$label rejected the API key (HTTP $code): $message"
        404 -> "$label couldn't find that model (HTTP 404): $message"
        429 -> "$label rate limit or quota reached (HTTP 429). Wait a moment or pick another model/provider. $message"
        else -> "$label HTTP $code: $message"
    }
}

/** Fetches the live model list for the "Load models" button. */
object ModelLister {
    private val skip = Regex("(embed|whisper|tts|audio|image|vision-preview|dall-e|moderation|guard|rerank|transcribe|realtime|imagine|speech|sora|search)", RegexOption.IGNORE_CASE)

    suspend fun list(config: AiConfig): List<String> {
        val p = config.provider
        val base = (if (p == ProviderKind.CUSTOM) config.baseUrl else p.baseUrl).trim().trimEnd('/')
        val key = config.apiKey.trim()
        val ids = when (p.protocol) {
            Protocol.GEMINI -> {
                val json = JSONObject(Http.request(p.label, "$base/models?pageSize=200", mapOf("x-goog-api-key" to key), null, attempts = 1))
                val arr = json.optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
                    .map { it.getString("name").removePrefix("models/") }
            }
            Protocol.ANTHROPIC -> {
                val json = JSONObject(Http.request(p.label, "$base/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), null, attempts = 1))
                val arr = json.optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            }
            else -> {
                val headers = if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap()
                val json = JSONObject(Http.request(p.label, "$base/models", headers, null, attempts = 1))
                val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).map { i -> arr.optJSONObject(i)?.let { it.optString("id").ifBlank { it.optString("name") } } ?: arr.optString(i) }
            }
        }
        val clean = ids.filter { it.isNotBlank() && !skip.containsMatchIn(it) }.distinct()
        // Show free models first (OpenRouter marks them with ":free").
        return clean.sortedWith(compareBy<String>({ !it.endsWith(":free") }, { it.lowercase() }))
    }
}

// ---------------------------------------------------------------------------------------------
// Adapters
// ---------------------------------------------------------------------------------------------

/** OpenAI Chat Completions and compatible APIs (Groq, OpenRouter, Mistral, Cerebras, NVIDIA, DeepSeek, OpenAI, Ollama…). */
class OpenAiChatProvider(
    private val kind: ProviderKind,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val installedApps: String
) : AiProvider {
    @Volatile private var jsonMode = kind.supportsJsonMode

    override suspend fun plan(task: String, observation: UiObservation, history: List<String>) = runCatching {
        if (kind.requiresKey) require(apiKey.isNotBlank()) { "${kind.label} API key is missing" }
        val text = try { call(task, observation, history) } catch (e: ProviderHttpException) {
            // Some models reject response_format; retry once without it and remember.
            if (jsonMode && e.code == 400 && e.message.orEmpty().contains(Regex("response_format|json", RegexOption.IGNORE_CASE))) {
                jsonMode = false; call(task, observation, history)
            } else throw e
        }
        ActionPlanParser.parse(text)
    }

    private suspend fun call(task: String, observation: UiObservation, history: List<String>): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", PromptBuilder.system))
                .put(JSONObject().put("role", "user").put("content", PromptBuilder.user(task, observation, history, installedApps))))
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            put("stream", false)
        }
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            if (kind == ProviderKind.OPENROUTER) { put("HTTP-Referer", "https://github.com/jazsajonia-pixel/Chrauto-AI"); put("X-Title", "Chrauto AI") }
        }
        val root = JSONObject(Http.request(kind.label, "$baseUrl/chat/completions", headers, body))
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: error("${kind.label} returned no choices")
        val message = choice.optJSONObject("message") ?: error("${kind.label} returned an empty message")
        return message.optString("content").ifBlank { message.optString("reasoning_content") }
            .ifBlank { error("${kind.label} returned an empty reply (finish_reason=${choice.optString("finish_reason")})") }
    }
}

/** OpenAI-style Responses API (used for xAI Grok, whose newest models are Responses-only). */
class OpenAiResponsesProvider(
    private val kind: ProviderKind,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val installedApps: String
) : AiProvider {
    @Volatile private var jsonMode = true

    override suspend fun plan(task: String, observation: UiObservation, history: List<String>) = runCatching {
        require(apiKey.isNotBlank()) { "${kind.label} API key is missing" }
        val text = try { call(task, observation, history) } catch (e: ProviderHttpException) {
            if (jsonMode && e.code == 400) { jsonMode = false; call(task, observation, history) } else throw e
        }
        ActionPlanParser.parse(text)
    }

    private suspend fun call(task: String, observation: UiObservation, history: List<String>): String {
        val body = JSONObject().apply {
            put("model", model)
            put("instructions", PromptBuilder.system)
            put("input", PromptBuilder.user(task, observation, history, installedApps))
            put("store", false)
            if (jsonMode) put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
        }
        val root = JSONObject(Http.request(kind.label, "$baseUrl/responses", mapOf("Authorization" to "Bearer $apiKey"), body))
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: error("${kind.label} returned no output")
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                if (part.optString("type") == "output_text") return part.getString("text")
            }
        }
        error("${kind.label} reply did not contain text")
    }
}

/** Anthropic Messages API. */
class AnthropicProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val installedApps: String
) : AiProvider {
    override suspend fun plan(task: String, observation: UiObservation, history: List<String>) = runCatching {
        require(apiKey.isNotBlank()) { "Anthropic API key is missing" }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("system", PromptBuilder.system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", PromptBuilder.user(task, observation, history, installedApps))))
        }
        val root = JSONObject(Http.request("Anthropic", "$baseUrl/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body))
        val content = root.optJSONArray("content") ?: error("Anthropic returned no content")
        val text = (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        ActionPlanParser.parse(text.ifBlank { error("Anthropic reply did not contain text (stop_reason=${root.optString("stop_reason")})") })
    }
}
