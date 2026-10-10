package com.chrauto.ai.agent

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class ActionProtocolTest {
    private val editable = UiNodeSnapshot("0.1", "", "Search", "EditText", false, true, false, true, Rect(0, 0, 100, 100), emptyList())
    private val button = UiNodeSnapshot("0.2", "Search", null, "Button", true, false, false, true, Rect(0, 0, 100, 100), emptyList())
    private val observation = UiObservation("com.example", "Main", listOf(editable, button))

    @Test fun validTapAndTypingAreAccepted() {
        assertNull(ActionValidator.validate(AgentAction.Tap("0.2"), observation))
        assertNull(ActionValidator.validate(AgentAction.TypeText("0.1", "Kotlin"), observation))
    }

    @Test fun staleAndUnsafeTargetsAreRejected() {
        assertNotNull(ActionValidator.validate(AgentAction.Tap("missing"), observation))
        assertNotNull(ActionValidator.validate(AgentAction.TypeText("0.2", "no"), observation))
        assertNotNull(ActionValidator.validate(AgentAction.Wait(20_000), observation))
    }

    @Test fun consequentialTaskRequiresConfirmation() {
        assertTrue(SafetyPolicy.requiresConfirmation("send this message", AgentAction.Tap("0.2")))
        assertFalse(SafetyPolicy.requiresConfirmation("search for Kotlin", AgentAction.Tap("0.2")))
    }
}
