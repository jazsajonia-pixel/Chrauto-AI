package com.chrauto.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chrauto.ai.tasks.*
import com.chrauto.ai.ui.TaskActions
import com.chrauto.ai.ui.TaskDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TasksUiTest {
    @get:Rule val compose = createComposeRule()

    private fun store(): TaskStore {
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-tasks-${System.nanoTime()}.json")
        return TaskStore(f)
    }

    @Test fun homeListsScheduledTasksAndOpensEditors() {
        val s = store()
        s.upsert(ScheduledTask(id = "1", title = "Morning check", prompt = "Open Settings", scheduledAt = System.currentTimeMillis() + 3_600_000, status = TaskStatus.SCHEDULED))
        compose.setContent { ChrautoScreen(onOpenAccessibility = {}, runTask = { _, _, _, _ -> }, observeController = false, taskStore = s) }
        compose.onNodeWithText("Morning check").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scheduled").assertExists()
        compose.onNodeWithTag("newViberButton").performScrollTo().performClick()
        compose.onNodeWithTag("viberGroup").assertIsDisplayed()
        compose.onNodeWithTag("backButton").performClick()
        compose.onNodeWithTag("newScheduleButton").performScrollTo().performClick()
        compose.onNodeWithTag("schedulePrompt").assertIsDisplayed()
        compose.onNodeWithTag("saveSchedule").assertIsNotEnabled() // no time picked yet
    }

    @Test fun unconfirmedViberMessageAsksTheUserInsteadOfResending() {
        val s = store()
        val t = ScheduledTask(
            id = "v", title = "Viber: Family", kind = TaskKind.VIBER_BATCH, status = TaskStatus.NEEDS_ATTENTION,
            viber = ViberBatch("Family", listOf(ViberMessage("m1", "Hello", status = MessageStatus.SENT), ViberMessage("m2", "Dinner at 7", status = MessageStatus.UNCERTAIN)))
        )
        s.upsert(t)
        var marked: Pair<String, Boolean>? = null
        compose.setContent {
            TaskDetailScreen(s, "v", TaskActions(markMessage = { _, m, sent -> marked = m to sent; s.update("v") { TaskRules.markMessage(it, m, sent, 1) } }), onBack = {}, onEdit = {})
        }
        compose.onNodeWithText("Needs attention").assertExists()
        compose.onNodeWithText("Unconfirmed").assertExists()
        compose.onNodeWithTag("markSent").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("m2" to true, marked)
        assertEquals(TaskStatus.COMPLETED, s.get("v")!!.status)
    }
}
