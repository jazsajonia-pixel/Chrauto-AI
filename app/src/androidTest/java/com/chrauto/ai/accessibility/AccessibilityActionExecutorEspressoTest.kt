package com.chrauto.ai.accessibility

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ScrollView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrauto.ai.agent.AgentAction
import com.chrauto.ai.agent.Direction
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityActionExecutorEspressoTest {
    @Test fun tapAndTypeTextDispatchThroughAccessibilityInteractionLayer() {
        ActivityScenario.launch(AccessibilityInteractionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val executor = executorFor(activity)
                assertTrue(executor.execute(AgentAction.Tap("tap")).ok)
                assertTrue(executor.execute(AgentAction.TypeText("input", "Kotlin Android")).ok)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText("Tapped")).check(matches(withText("Tapped")))
            onView(withText(containsString("Kotlin Android"))).check(matches(withText(containsString("Kotlin Android"))))
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
                    findNode = { id -> if (id == "scroll") ViewTarget(activity.scroll) else null },
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
                "tap" -> ViewTarget(activity.tapTarget)
                "input" -> ViewTarget(activity.input)
                else -> null
            }
        },
        performGlobalAction = { true }
    )

    private class ViewTarget(private val view: View) : AccessibilityNodeTarget {
        override val isEnabled: Boolean get() = view.isEnabled
        override val isEditable: Boolean get() = view is EditText

        override fun performAction(action: Int, arguments: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> view.performClick()
            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                val editText = view as? EditText ?: return false
                editText.setText(arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE) ?: "")
                true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> (view as? ScrollView)?.fullScroll(View.FOCUS_DOWN) ?: false
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> (view as? ScrollView)?.fullScroll(View.FOCUS_UP) ?: false
            else -> false
        }
    }
}
