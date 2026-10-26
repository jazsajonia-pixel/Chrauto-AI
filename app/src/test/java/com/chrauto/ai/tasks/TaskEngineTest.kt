package com.chrauto.ai.tasks

import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.agent.AgentState
import com.chrauto.ai.agent.RunStatus
import com.chrauto.ai.viber.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 10_000L
    private var blocker: Blocker? = null
    private val conditions = DeviceConditions { blocker }

    private fun store() = TaskStore(tmp.newFile())
    private fun aiTask(id: String = "a") = ScheduledTask(id = id, title = "AI", prompt = "Open Settings", scheduledAt = 5_000, status = TaskStatus.SCHEDULED, createdAt = 0)
    private fun viberTask(vararg ids: String, auto: Boolean = true) = ScheduledTask(
        id = "v", title = "Viber", kind = TaskKind.VIBER_BATCH, scheduledAt = 5_000, status = TaskStatus.SCHEDULED, createdAt = 0,
        viber = ViberBatch("Family", ids.map { ViberMessage(it, "text $it") }, autoSend = auto, gapSeconds = 1)
    )
    private fun engine(s: TaskStore, ai: AiTaskRunner = AiTaskRunner { _, _ -> AgentState(RunStatus.SUCCEEDED, detail = "Opened", step = 2) },
                       viber: ViberMessageSender = ViberMessageSender { _, _, _, _, about -> about(); SendOutcome.Sent("bubble") }) =
        TaskEngine(s, conditions, ai, viber, clock = { now }, pause = {})

    @Test fun lockedPhoneWaitsInsteadOfFailing() = runTest {
        val s = store(); s.upsert(aiTask())
        blocker = Blocker.LOCKED
        engine(s).processAvailable()
        val t = s.get("a")!!
        assertEquals(TaskStatus.WAITING_DEVICE, t.status)
        assertEquals(Blocker.LOCKED.message, t.waitingReason)
        assertEquals(0, t.attempts)
        // Unlocked later: the waiting task runs and completes.
        blocker = null
        engine(s).processAvailable()
        assertEquals(TaskStatus.COMPLETED, s.get("a")!!.status)
        assertNull(s.get("a")!!.waitingReason)
    }

    @Test fun waitingTooLongFailsWithReason() = runTest {
        val s = store(); s.upsert(aiTask()); blocker = Blocker.SCREEN_OFF
        engine(s).processAvailable()
        now += 25 * 60 * 60_000L
        engine(s).processAvailable()
        assertEquals(TaskStatus.FAILED, s.get("a")!!.status)
    }

    @Test fun aiFailureAfterActionsIsNotRetried() = runTest {
        val s = store(); s.upsert(aiTask())
        engine(s, ai = { _, _ -> AgentState(RunStatus.FAILED, detail = "Network problem talking to Gemini", step = 3) }).processAvailable()
        assertEquals(TaskStatus.FAILED, s.get("a")!!.status)
    }

    @Test fun transientFailureBeforeAnyActionRetriesLaterWithLimit() = runTest {
        val s = store(); s.upsert(aiTask())
        var calls = 0
        val e = engine(s, ai = { _, _ -> calls++; AgentState(RunStatus.FAILED, detail = "Gemini took too long to answer", step = 0) })
        e.processAvailable()
        assertEquals(TaskStatus.SCHEDULED, s.get("a")!!.status)
        assertEquals(1, calls) // not retried immediately in a loop
        repeat(5) { now += 6 * 60_000L; e.processAvailable() }
        assertEquals(TaskStatus.FAILED, s.get("a")!!.status)
        assertEquals(3, calls)
    }

    @Test fun busyAgentReschedulesWithoutCountingAnAttempt() = runTest {
        val s = store(); s.upsert(aiTask())
        engine(s, ai = { _, _ -> AgentState(RunStatus.FAILED, detail = AgentController.BUSY) }).processAvailable()
        val t = s.get("a")!!
        assertEquals(TaskStatus.SCHEDULED, t.status); assertEquals(0, t.attempts); assertEquals(now + 60_000, t.scheduledAt)
    }

    @Test fun approvalPauseIsWaitingForUser() = runTest {
        val s = store(); s.upsert(aiTask())
        engine(s, ai = { _, _ -> AgentState(RunStatus.NEEDS_ASSISTANCE, detail = "Send needs approval") }).processAvailable()
        assertEquals(TaskStatus.WAITING_USER, s.get("a")!!.status)
    }

    @Test fun viberBatchSendsInOrderAndCompletes() = runTest {
        val s = store(); s.upsert(viberTask("1", "2", "3"))
        val order = mutableListOf<String>()
        engine(s, viber = { _, m, _, _, about -> about(); order += m.id; SendOutcome.Sent("bubble") }).processAvailable()
        assertEquals(listOf("1", "2", "3"), order)
        assertEquals(TaskStatus.COMPLETED, s.get("v")!!.status)
    }

    @Test fun uncertainSendStopsTheBatchAndIsNeverResent() = runTest {
        val s = store(); s.upsert(viberTask("1", "2", "3"))
        var sends = 0
        val e = engine(s, viber = { _, m, _, _, about -> about(); sends++; if (m.id == "2") SendOutcome.Unconfirmed("no bubble") else SendOutcome.Sent("bubble") })
        e.processAvailable()
        val t = s.get("v")!!
        assertEquals(TaskStatus.NEEDS_ATTENTION, t.status)
        assertEquals(listOf(MessageStatus.SENT, MessageStatus.UNCERTAIN, MessageStatus.PENDING), t.viber!!.messages.map { it.status })
        assertEquals(2, sends)
        // A later pass must not touch it.
        now += 3_600_000; e.processAvailable()
        assertEquals(2, sends)
        // Retry re-queues nothing unconfirmed; message 3 is still pending and is sent, message 2 is not.
        val r = TaskRules.retry(s.get("v")!!, now)
        assertNotNull(r); s.upsert(r!!)
        val sent = mutableListOf<String>()
        engine(s, viber = { _, m, _, _, about -> about(); sent += m.id; SendOutcome.Sent("bubble") }).processAvailable()
        assertEquals(listOf("3"), sent)
    }

    @Test fun notSentKeepsTheRestPending() = runTest {
        val s = store(); s.upsert(viberTask("1", "2"))
        engine(s, viber = { _, _, _, _, _ -> SendOutcome.NotSent("Group not found", needsUser = true) }).processAvailable()
        val t = s.get("v")!!
        assertEquals(TaskStatus.FAILED, t.status)
        assertEquals(listOf(MessageStatus.FAILED, MessageStatus.PENDING), t.viber!!.messages.map { it.status })
        assertEquals("Group not found", t.lastError)
    }

    @Test fun lockingMidBatchPausesAndResumes() = runTest {
        val s = store(); s.upsert(viberTask("1", "2"))
        engine(s, viber = { _, _, _, _, about -> about(); blocker = Blocker.LOCKED; SendOutcome.Sent("bubble") }).processAvailable()
        assertEquals(TaskStatus.WAITING_DEVICE, s.get("v")!!.status)
        blocker = null
        engine(s).processAvailable()
        assertEquals(TaskStatus.COMPLETED, s.get("v")!!.status)
    }

    @Test fun cancellingWhileSendingMarksUncertainNotSent() = runTest {
        val s = store(); s.upsert(viberTask("1", "2"))
        val inSend = CompletableDeferred<Unit>()
        val e = engine(s, viber = { _, _, _, _, about -> about(); inSend.complete(Unit); CompletableDeferred<SendOutcome>().await() })
        val job = launch { e.processAvailable() }
        inSend.await(); yield()
        job.cancel(); job.join()
        val t = s.get("v")!!
        assertEquals(TaskStatus.NEEDS_ATTENTION, t.status)
        assertEquals(listOf(MessageStatus.UNCERTAIN, MessageStatus.CANCELLED), t.viber!!.messages.map { it.status })
        assertNull(e.activeId)
    }

    @Test fun runNowRefusesFinishedTasks() = runTest {
        val s = store(); s.upsert(aiTask().copy(status = TaskStatus.COMPLETED))
        assertNotNull(engine(s).runNow("a"))
        assertNotNull(engine(s).runNow("missing"))
    }
}
