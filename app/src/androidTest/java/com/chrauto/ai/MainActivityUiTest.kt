package com.chrauto.ai

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
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
        compose.setContent { ChrautoScreen(onOpenAccessibility = {}, runTask = { _, _, _, _ -> }, observeController = false) }
        compose.onNodeWithTag("runButton").assertIsNotEnabled()
        compose.onNodeWithTag("apiKeyInput").performScrollTo().performTextInput("test-key")
        compose.onNodeWithTag("runButton").assertIsEnabled()
    }

    @Test fun screenAwareModeRequiresExplicitOptInAndRoutesThroughConsentCallback() {
        val requested = AtomicBoolean(false)
        compose.setContent {
            ChrautoScreen(onOpenAccessibility = {}, observeController = false,
                onStartScreenAware = { _, _ -> requested.set(true) })
        }
        compose.onNodeWithTag("apiKeyInput").performScrollTo().performTextInput("test-key")
        compose.onNodeWithTag("screenAwareSwitch").performScrollTo().performClick()
        compose.onNodeWithTag("runButton").performScrollTo().performClick()
        assertTrue(requested.get())
    }

    @Test fun runReportsRunningAndCompletionThroughInjectedRuntime() {
        compose.setContent {
            ChrautoScreen(
                onOpenAccessibility = {},
                observeController = false,
                runTask = { _, _, _, onState ->
                    onState(AgentState(RunStatus.RUNNING, listOf(AgentLog("Observed test screen"))))
                    onState(AgentState(RunStatus.SUCCEEDED, listOf(AgentLog("Verified test result"))))
                }
            )
        }
        compose.onNodeWithTag("apiKeyInput").performScrollTo().performTextInput("test-key")
        compose.onNodeWithTag("runButton").performScrollTo().performClick()
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Status: SUCCEEDED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Status: SUCCEEDED").performScrollTo().assertIsDisplayed()
    }

    @Test fun stopCancelsAnActiveRunAndReportsCancellation() {
        val started = AtomicBoolean(false)
        compose.setContent {
            ChrautoScreen(
                onOpenAccessibility = {},
                observeController = false,
                runTask = { _, _, _, onState ->
                    started.set(true)
                    onState(AgentState(RunStatus.RUNNING, listOf(AgentLog("Running"))))
                    delay(10_000)
                }
            )
        }
        compose.onNodeWithTag("apiKeyInput").performScrollTo().performTextInput("test-key")
        compose.onNodeWithTag("runButton").performScrollTo().performClick()
        compose.waitUntil(2_000) { started.get() }
        compose.onNodeWithTag("stopButton").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Status: CANCELLED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Status: CANCELLED").performScrollTo().assertIsDisplayed()
    }

    @Test fun accessibilitySetupCallbackIsInvoked() {
        val opened = AtomicBoolean(false)
        compose.setContent { ChrautoScreen(onOpenAccessibility = { opened.set(true) }, runTask = { _, _, _, _ -> }, observeController = false) }
        compose.onNodeWithTag("accessibilityButton").performScrollTo().performClick()
        assertTrue(opened.get())
    }
}
