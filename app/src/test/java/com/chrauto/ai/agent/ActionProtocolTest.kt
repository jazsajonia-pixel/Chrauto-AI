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

    @Test fun screenCoordinatesRequireFreshBoundedObservationAndConfidence() {
        val screen = observation.copy(capturedAtMillis = System.currentTimeMillis(), screenWidth = 200, screenHeight = 300)
        assertNull(ActionValidator.validate(AgentAction.TapAt(100f, 150f, .9f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.TapAt(200f, 150f, .9f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.TapAt(Float.NaN, 2f, .9f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.TapAt(20f, 20f, .79f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.TapAt(20f, 20f, .9f), screen.copy(capturedAtMillis = 0)))
    }

    @Test fun gesturePathBoundsPointsDurationAndLengthAreValidated() {
        val screen = observation.copy(capturedAtMillis = System.currentTimeMillis(), screenWidth = 200, screenHeight = 300)
        assertNull(ActionValidator.validate(AgentAction.GesturePath(listOf(ScreenPoint(10f, 10f), ScreenPoint(20f, 20f)), 500, .95f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.GesturePath(listOf(ScreenPoint(10f, 10f)), 500, .95f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.GesturePath(listOf(ScreenPoint(10f, 10f), ScreenPoint(20f, 20f)), 20, .95f), screen))
        assertNotNull(ActionValidator.validate(AgentAction.GesturePath(listOf(ScreenPoint(10f, 10f), ScreenPoint(250f, 20f)), 500, .95f), screen))
    }

    @Test fun consequentialTaskAndVisualTargetRequireConfirmation() {
        assertTrue(SafetyPolicy.requiresConfirmation("send this message", AgentAction.Tap("0.2")))
        assertTrue(SafetyPolicy.requiresConfirmation("play the game", AgentAction.TapAt(5f, 5f, .9f, "Buy gems")))
        assertFalse(SafetyPolicy.requiresConfirmation("play the game", AgentAction.TapAt(5f, 5f, .9f, "Jump")))
        assertFalse(SafetyPolicy.requiresConfirmation("search for Kotlin", AgentAction.Tap("0.2")))
    }
}
