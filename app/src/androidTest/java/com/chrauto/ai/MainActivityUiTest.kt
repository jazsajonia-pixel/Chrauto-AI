package com.chrauto.ai

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrauto.ai.agent.AgentLog
import com.chrauto.ai.agent.AgentState
import com.chrauto.ai.agent.RunStatus
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun runIsDisabledUntilApiKeyIsProvided() {
        compose.setContent { ChrautoScreen(onOpenAccessibility = {}, runTask = { _, _, _, _ -> }) }
        compose.onNodeWithTag("runButton").assertIsNotEnabled()
        compose.onNodeWithTag("apiKeyInput").performTextInput("test-key")
        compose.onNodeWithTag("runButton").assertIsEnabled()
    }

    @Test fun runReportsRunningAndCompletionThroughInjectedRuntime() {
        compose.setContent {
            ChrautoScreen(
                onOpenAccessibility = {},
                runTask = { _, _, _, onState ->
                    onState(AgentState(RunStatus.RUNNING, listOf(AgentLog("Observed test screen"))))
                    onState(AgentState(RunStatus.SUCCEEDED, listOf(AgentLog("Verified test result"))))
                }
            )
        }
        compose.onNodeWithTag("apiKeyInput").performTextInput("test-key")
        compose.onNodeWithTag("runButton").performClick()
        compose.waitUntil(2_000) { compose.onAllNodes(hasTestTag("status")).fetchSemanticsNodes().any { it.text.any { text -> text.text == "Status: SUCCEEDED" } } }
        compose.onNodeWithTag("status").assertTextEquals("Status: SUCCEEDED")
        compose.onNode(hasTestTag("status")).assertExists()
    }

    @Test fun stopCancelsAnActiveRunAndReportsCancellation() {
        val started = AtomicBoolean(false)
        compose.setContent {
            ChrautoScreen(
                onOpenAccessibility = {},
                runTask = { _, _, _, onState ->
                    started.set(true)
                    onState(AgentState(RunStatus.RUNNING, listOf(AgentLog("Running"))))
                    delay(10_000)
                }
            )
        }
        compose.onNodeWithTag("apiKeyInput").performTextInput("test-key")
        compose.onNodeWithTag("runButton").performClick()
        compose.waitUntil(2_000) { started.get() }
        compose.onNodeWithTag("stopButton").assertIsEnabled().performClick()
        compose.waitUntil(2_000) { compose.onAllNodes(hasTestTag("status")).fetchSemanticsNodes().any { it.text.any { text -> text.text == "Status: CANCELLED" } } }
        compose.onNodeWithTag("status").assertTextEquals("Status: CANCELLED")
    }

    @Test fun accessibilitySetupCallbackIsInvoked() {
        val opened = AtomicBoolean(false)
        compose.setContent { ChrautoScreen(onOpenAccessibility = { opened.set(true) }, runTask = { _, _, _, _ -> }) }
        compose.onNodeWithTag("accessibilityButton").performClick()
        assertTrue(opened.get())
    }
}
