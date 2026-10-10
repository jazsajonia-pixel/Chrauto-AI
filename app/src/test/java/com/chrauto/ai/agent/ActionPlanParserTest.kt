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
