package com.chrauto.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrauto.ai.agent.AgentAction
import com.chrauto.ai.agent.ActionResult
import com.chrauto.ai.agent.Direction
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityActionExecutorEspressoTest {
    @Test fun tapAndTypeTextDispatchThroughAccessibilityNodeInfo() {
        ActivityScenario.launch(AccessibilityInteractionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val executor = executorFor(activity)
                assertTrue(executor.execute(AgentAction.Tap("tap")).ok)
                assertTrue(executor.execute(AgentAction.TypeText("input", "Kotlin Android")).ok)
            }
            onView(withText("Tapped")).check(matches(withText("Tapped")))
            onView(withText("Search text")).check(matches(withText(containsString("Kotlin Android"))))
        }
    }

    @Test fun invalidNodeAndUnsupportedActionFailHonestly() {
        ActivityScenario.launch(AccessibilityInteractionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val executor = executorFor(activity)
                assertFalse(executor.execute(AgentAction.Tap("missing")).ok)
                assertFalse(executor.execute(AgentAction.OpenApp("com.example.missing")).ok)
            }
        }
    }

    @Test fun scrollAndBackUseTheCorrectAccessibilityChannels() {
        var backCalled = false
        ActivityScenario.launch(AccessibilityInteractionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val executor = AccessibilityActionExecutor(
                    findNode = { id -> if (id == "scroll") activity.scroll.createAccessibilityNodeInfo() else null },
                    performGlobalAction = { action ->
                        backCalled = action == android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        backCalled
                    }
                )
                assertTrue(executor.execute(AgentAction.Scroll("scroll", Direction.UP)).ok)
                assertTrue(executor.execute(AgentAction.PressBack).ok)
            }
        }
        assertTrue(backCalled)
    }

    private fun executorFor(activity: AccessibilityInteractionTestActivity) = AccessibilityActionExecutor(
        findNode = { id ->
            when (id) {
                "tap" -> activity.tapTarget.createAccessibilityNodeInfo()
                "input" -> activity.input.createAccessibilityNodeInfo()
                else -> null
            }
        },
        performGlobalAction = { true }
    )
}
