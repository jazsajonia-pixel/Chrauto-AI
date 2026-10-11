package com.chrauto.ai.provider

import com.chrauto.ai.agent.AgentAction
import com.chrauto.ai.agent.UiObservation
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ProviderAdaptersTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private val headers = mutableListOf<Map<String, String>>()
    private var responder: (path: String, body: JSONObject) -> Pair<Int, String> = { _, _ -> 500 to "{}" }
    private val base get() = "http://127.0.0.1:${server.address.port}/v1"
    private val obs = UiObservation("com.android.chrome", "Activity", emptyList())

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.bufferedReader().readText().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            requests += ex.requestURI.path to body
            headers += ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            val (code, text) = responder(ex.requestURI.path, body)
            val bytes = text.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }
    @After fun stop() = server.stop(0)

    @Test fun openAiChatSendsJsonModeAndParsesReply() = runBlocking {
        responder = { _, _ -> 200 to """{"choices":[{"message":{"content":"{\"action\":\"tap\",\"node_id\":\"0.1\"}"}}]}""" }
        val plan = OpenAiChatProvider(ProviderKind.GROQ, base, "k", "m", "").plan("t", obs).getOrThrow()
        assertEquals(AgentAction.Tap("0.1"), plan.action)
        assertEquals("/v1/chat/completions", requests[0].first)
        assertEquals("json_object", requests[0].second.getJSONObject("response_format").getString("type"))
        assertEquals("Bearer k", headers[0]["authorization"])
        assertFalse(requests[0].second.has("temperature"))
    }

    @Test fun openAiChatRetriesWithoutJsonModeWhenRejected() = runBlocking {
        responder = { _, body ->
            if (body.has("response_format")) 400 to """{"error":{"message":"response_format json_object is not supported"}}"""
            else 200 to """{"choices":[{"message":{"content":"{\"action\":\"press_back\"}"}}]}"""
        }
        val plan = OpenAiChatProvider(ProviderKind.OPENROUTER, base, "k", "m", "").plan("t", obs).getOrThrow()
        assertEquals(AgentAction.PressBack, plan.action)
        assertEquals(2, requests.size)
    }

    @Test fun responsesApiParsesOutputMessage() = runBlocking {
        responder = { _, _ -> 200 to """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"{\"action\":\"wait\",\"milliseconds\":800}"}]}]}""" }
        val plan = OpenAiResponsesProvider(ProviderKind.XAI, base, "k", "grok", "").plan("t", obs).getOrThrow()
        assertEquals(AgentAction.Wait(800), plan.action)
        assertEquals("/v1/responses", requests[0].first)
        assertTrue(requests[0].second.has("instructions"))
    }

    @Test fun anthropicUsesHeadersAndSkipsThinkingBlocks() = runBlocking {
        responder = { _, _ -> 200 to """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"{\"action\":\"open_app\",\"package_name\":\"com.android.chrome\"}"}]}""" }
        val plan = AnthropicProvider(base, "secret", "claude-haiku-5-5", "").plan("t", obs).getOrThrow()
        assertEquals(AgentAction.OpenApp("com.android.chrome"), plan.action)
        assertEquals("/v1/messages", requests[0].first)
        assertEquals("secret", headers[0]["x-api-key"])
        assertEquals("2023-06-01", headers[0]["anthropic-version"])
    }

    @Test fun authErrorsAreFriendlyAndNotRetried() = runBlocking {
        responder = { _, _ -> 401 to """{"error":{"message":"Invalid API Key"}}""" }
        val error = OpenAiChatProvider(ProviderKind.OPENAI, base, "bad", "m", "").plan("t", obs).exceptionOrNull()
        assertTrue(error!!.message!!.contains("rejected the API key"))
        assertEquals(1, requests.size)
    }

    @Test fun modelListerPutsFreeModelsFirstAndDropsNonChat() = runBlocking {
        responder = { _, _ -> 200 to """{"data":[{"id":"z-model"},{"id":"a-model:free"},{"id":"text-embedding-3"},{"id":"whisper-large-v3"}]}""" }
        val list = ModelLister.list(AiConfig(ProviderKind.CUSTOM, "", "m", base))
        assertEquals(listOf("a-model:free", "z-model"), list)
    }
}
