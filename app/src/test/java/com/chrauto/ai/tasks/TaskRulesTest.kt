package com.chrauto.ai.tasks

import org.junit.Assert.*
import org.junit.Test

class TaskRulesTest {
    private fun task(status: TaskStatus, at: Long? = 1_000, viber: ViberBatch? = null) =
        ScheduledTask(id = "t", title = "T", status = status, scheduledAt = at, createdAt = 0, viber = viber, kind = if (viber != null) TaskKind.VIBER_BATCH else TaskKind.AI_TASK)
    private fun msg(id: String, s: MessageStatus) = ViberMessage(id, "hello $id", status = s)

    @Test fun completedIsFinalAndRunningCountsAttempts() {
        val done = TaskRules.transition(task(TaskStatus.RUNNING), TaskStatus.COMPLETED, 5)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(5L, done.finishedAt)
        assertSame(done, TaskRules.transition(done, TaskStatus.RUNNING, 6))
        val running = TaskRules.transition(task(TaskStatus.SCHEDULED), TaskStatus.RUNNING, 7)
        assertEquals(1, running.attempts); assertEquals(7L, running.startedAt)
    }

    @Test fun draftCannotJumpToCompleted() {
        assertFalse(TaskRules.canTransition(TaskStatus.DRAFT, TaskStatus.COMPLETED))
        assertEquals(TaskStatus.DRAFT, TaskRules.transition(task(TaskStatus.DRAFT), TaskStatus.COMPLETED, 1).status)
    }

    @Test fun dueOnlyWhenScheduledAndTimeReached() {
        assertFalse(TaskRules.isDue(task(TaskStatus.SCHEDULED, 2_000), 1_999))
        assertTrue(TaskRules.isDue(task(TaskStatus.SCHEDULED, 2_000), 2_000))
        assertFalse(TaskRules.isDue(task(TaskStatus.DRAFT, 0), 5_000))
        assertFalse(TaskRules.isDue(task(TaskStatus.SCHEDULED, null), 5_000))
        val a = task(TaskStatus.SCHEDULED, 300).copy(id = "a"); val b = task(TaskStatus.SCHEDULED, 100).copy(id = "b")
        assertEquals("b", TaskRules.nextDue(listOf(a, b), 1_000)?.id)
        assertEquals(100L, TaskRules.nextAlarmAt(listOf(a, b, task(TaskStatus.COMPLETED, 50).copy(id = "c"))))
    }

    @Test fun interruptedRunNeedsAttentionAndSendingBecomesUncertain() {
        val v = ViberBatch("Team", listOf(msg("1", MessageStatus.SENT), msg("2", MessageStatus.SENDING), msg("3", MessageStatus.PENDING)))
        val out = TaskRules.recoverInterrupted(listOf(task(TaskStatus.RUNNING, viber = v)), 10, activeId = null).single()
        assertEquals(TaskStatus.NEEDS_ATTENTION, out.status)
        assertEquals(listOf(MessageStatus.SENT, MessageStatus.UNCERTAIN, MessageStatus.PENDING), out.viber!!.messages.map { it.status })
        // The task that is actually executing in this process is left alone.
        assertEquals(TaskStatus.RUNNING, TaskRules.recoverInterrupted(listOf(task(TaskStatus.RUNNING)), 10, activeId = "t").single().status)
    }

    @Test fun retryRequeuesOnlyDefinitelyUnsentMessages() {
        val v = ViberBatch("Team", listOf(msg("1", MessageStatus.SENT), msg("2", MessageStatus.UNCERTAIN), msg("3", MessageStatus.FAILED)))
        val r = TaskRules.retry(task(TaskStatus.NEEDS_ATTENTION, viber = v), 50)!!
        assertEquals(TaskStatus.SCHEDULED, r.status)
        assertEquals(listOf(MessageStatus.SENT, MessageStatus.UNCERTAIN, MessageStatus.PENDING), r.viber!!.messages.map { it.status })
        val nothing = ViberBatch("Team", listOf(msg("1", MessageStatus.SENT), msg("2", MessageStatus.UNCERTAIN)))
        assertNull(TaskRules.retry(task(TaskStatus.NEEDS_ATTENTION, viber = nothing), 50))
        assertNull(TaskRules.retry(task(TaskStatus.COMPLETED), 50))
    }

    @Test fun batchOutcomeIsHonest() {
        fun o(vararg s: MessageStatus) = TaskRules.batchOutcome(ViberBatch("g", s.mapIndexed { i, x -> msg("$i", x) }))
        assertEquals(TaskStatus.COMPLETED, o(MessageStatus.SENT, MessageStatus.SENT))
        assertEquals(TaskStatus.NEEDS_ATTENTION, o(MessageStatus.SENT, MessageStatus.UNCERTAIN))
        assertEquals(TaskStatus.PARTIAL, o(MessageStatus.SENT, MessageStatus.FAILED, MessageStatus.PENDING))
        assertEquals(TaskStatus.FAILED, o(MessageStatus.FAILED, MessageStatus.PENDING))
        assertEquals(TaskStatus.CANCELLED, o(MessageStatus.CANCELLED))
        assertEquals(TaskStatus.FAILED, TaskRules.batchOutcome(ViberBatch("g", emptyList())))
    }

    @Test fun markingUncertainMessagesResolvesTheBatch() {
        val v = ViberBatch("Team", listOf(msg("1", MessageStatus.SENT), msg("2", MessageStatus.UNCERTAIN)))
        val sent = TaskRules.markMessage(task(TaskStatus.NEEDS_ATTENTION, viber = v), "2", sent = true, now = 9)
        assertEquals(TaskStatus.COMPLETED, sent.status)
        val notSent = TaskRules.markMessage(task(TaskStatus.NEEDS_ATTENTION, viber = v), "2", sent = false, now = 9)
        assertEquals(TaskStatus.PARTIAL, notSent.status)
        assertEquals(MessageStatus.FAILED, notSent.viber!!.messages[1].status)
        // Only Unconfirmed messages can be marked.
        val t = task(TaskStatus.NEEDS_ATTENTION, viber = v)
        assertSame(t, TaskRules.markMessage(t, "1", sent = false, now = 9))
    }
}
