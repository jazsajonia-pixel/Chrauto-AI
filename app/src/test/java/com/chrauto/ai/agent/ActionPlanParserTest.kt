package com.chrauto.ai.agent

import com.chrauto.ai.provider.ActionPlanParser
import org.junit.Assert.*
import org.junit.Test

class ActionPlanParserTest {
    @Test fun parsesTypedAction() {
        val plan = ActionPlanParser.parse("{\"action\":\"type_text\",\"node_id\":\"0.1\",\"text\":\"hello\"}")
        assertEquals(AgentAction.TypeText("0.1", "hello"), plan.action)
    }

    @Test(expected = Exception::class) fun rejectsUnknownAction() {
        ActionPlanParser.parse("{\"action\":\"shell\"}")
    }

    @Test(expected = Exception::class) fun rejectsMalformedJson() {
        ActionPlanParser.parse("not json")
    }
}

class GeminiSchemaTest {
    @Test fun schemaUsesOpenApiNullableInsteadOfTypeArrays() {
        val schema = com.chrauto.ai.provider.GeminiProvider("k").schema().toString()
        assertFalse(schema.contains("[Ljava"))
        assertFalse(schema.contains("\"type\":["))
        assertTrue(schema.contains("\"nullable\":true"))
    }

    @Test fun parserTreatsJsonNullAsMissing() {
        val plan = ActionPlanParser.parse("{\"action\":\"scroll\",\"node_id\":null,\"direction\":\"DOWN\",\"text\":null}")
        assertEquals(AgentAction.Scroll(null, Direction.DOWN), plan.action)
    }
}

class MultiProviderParsingTest {
    @Test fun parserStripsThinkBlocksAndMarkdown() {
        val raw = "<think>I should tap search</think>\n```json\n{\"action\":\"tap\",\"node_id\":\"0.3\",\"thought\":\"Tap search\"}\n```"
        val plan = ActionPlanParser.parse(raw)
        assertEquals(AgentAction.Tap("0.3"), plan.action)
        assertEquals("Tap search", plan.thought)
    }

    @Test fun parserFindsJsonInsideProse() {
        val plan = ActionPlanParser.parse("Sure! Here is the action: {\"action\":\"press_enter\"} Hope that helps.")
        assertEquals(AgentAction.PressEnter(null), plan.action)
    }

    @Test fun customProviderDoesNotNeedKeyButOthersDo() {
        assertTrue(com.chrauto.ai.provider.AiConfig(com.chrauto.ai.provider.ProviderKind.CUSTOM).isUsable)
        assertFalse(com.chrauto.ai.provider.AiConfig(com.chrauto.ai.provider.ProviderKind.GROQ).isUsable)
        assertTrue(com.chrauto.ai.provider.AiConfig(com.chrauto.ai.provider.ProviderKind.GROQ, apiKey = "k").isUsable)
    }

    @Test fun everyProviderHasDefaultsAndUniqueIds() {
        val kinds = com.chrauto.ai.provider.ProviderKind.entries
        assertEquals(kinds.size, kinds.map { it.id }.toSet().size)
        kinds.forEach { assertTrue(it.defaultModel.isNotBlank() && it.baseUrl.startsWith("http") && it.suggestedModels.contains(it.defaultModel)) }
    }
}
