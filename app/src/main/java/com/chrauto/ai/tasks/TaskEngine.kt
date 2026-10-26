package com.chrauto.ai.tasks

import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.agent.AgentState
import com.chrauto.ai.agent.RunStatus
import com.chrauto.ai.viber.SendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Runs an AI task to completion and returns the final agent state. */
fun interface AiTaskRunner { suspend fun run(prompt: String, approved: Boolean): AgentState }

/** Sends one Viber message. [onAboutToSend] must be awaited before the Send tap. */
fun interface ViberMessageSender {
    suspend fun send(groupName: String, message: ViberMessage, autoSend: Boolean, log: (String) -> Unit, onAboutToSend: suspend () -> Unit): SendOutcome
}

/**
 * Executes persisted tasks one at a time. Pure of Android so it is covered by JVM tests with fakes;
 * [TaskRunner] wires the real store, device checks, AI runner and Viber sender.
 */
class TaskEngine(
    private val store: TaskStore,
    private val conditions: DeviceConditions,
    private val ai: AiTaskRunner,
    private val viber: ViberMessageSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: (ScheduledTask, String) -> Unit = { _, _ -> },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Waiting tasks give up after this long past their scheduled time. */
    private val maxWaitMs: Long = 24 * 60 * 60_000L,
    private val maxAutoRetries: Int = 3,
    private val retryDelayMs: Long = 5 * 60_000L
) {
    @Volatile var activeId: String? = null
        private set

    private fun req(t: ScheduledTask) = Requirements(needsNetwork = t.kind == TaskKind.AI_TASK)

    /**
     * Processes every task that can run now, oldest first. Returns when nothing else is runnable.
     * Callers must not run two of these at once (TaskRunner guarantees that).
     */
    suspend fun processAvailable() {
        expireStale()
        while (true) {
            coroutineContext.ensureActive()
            val now = clock()
            val list = store.tasks.value
            val candidate = list.filter { it.status == TaskStatus.WAITING_DEVICE }.minByOrNull { it.scheduledAt ?: it.updatedAt }
                ?: TaskRules.nextDue(list, now) ?: return
            val blocker = conditions.blocker(req(candidate))
            if (blocker != null) { markWaiting(candidate, blocker); return }
            val claimed = store.claim(candidate.id, now, setOf(TaskStatus.SCHEDULED, TaskStatus.WAITING_DEVICE)) ?: continue
            execute(claimed)
        }
    }

    /** Runs one task now (the "Run now" button), if it is in a startable state. */
    suspend fun runNow(id: String): String? {
        val t = store.get(id) ?: return "Task not found"
        conditions.blocker(req(t))?.let { return it.message }
        val claimed = store.claim(id, clock(), setOf(TaskStatus.DRAFT, TaskStatus.SCHEDULED, TaskStatus.WAITING_DEVICE))
            ?: return "This task can't be started from \"${t.status.label}\""
        execute(claimed)
        return null
    }

    private fun markWaiting(t: ScheduledTask, b: Blocker) {
        if (t.status == TaskStatus.WAITING_DEVICE && t.waitingReason == b.message) return
        val moved = store.update(t.id) {
            val w = if (it.status == TaskStatus.WAITING_DEVICE) it.copy(updatedAt = clock()) else TaskRules.transition(it, TaskStatus.WAITING_DEVICE, clock())
            w.copy(waitingReason = b.message).withEvent("Waiting: ${b.message}", now = clock())
        }
        if (moved != null) notify(moved, b.message)
    }

    private fun expireStale() {
        val now = clock()
        store.tasks.value.filter { it.status == TaskStatus.WAITING_DEVICE && now - (it.scheduledAt ?: it.updatedAt) > maxWaitMs }.forEach { t ->
            store.update(t.id) { TaskRules.transition(it, TaskStatus.FAILED, now, "Gave up: the phone wasn't available for 24 hours (${it.waitingReason ?: "unknown"})", true).copy(lastError = "Phone wasn't available in time") }
                ?.let { notify(it, "Didn't run: the phone wasn't available in time") }
        }
    }

    private suspend fun execute(task: ScheduledTask) {
        activeId = task.id
        try {
            when (task.kind) {
                TaskKind.AI_TASK -> runAi(task)
                TaskKind.VIBER_BATCH -> runViber(task)
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { finishCancelled(task.id) }
            throw c
        } catch (e: Exception) {
            store.update(task.id) { TaskRules.transition(it, TaskStatus.FAILED, clock(), "Error: ${e.message}", true).copy(lastError = e.message) }
        } finally {
            activeId = null
        }
    }

    private fun finishCancelled(id: String) {
        store.update(id) { t ->
            val viber = t.viber?.let { v -> v.copy(messages = v.messages.map { m -> if (m.status == MessageStatus.PENDING) m.copy(status = MessageStatus.CANCELLED, updatedAt = clock()) else if (m.status == MessageStatus.SENDING) m.copy(status = MessageStatus.UNCERTAIN, error = "Stopped while sending", updatedAt = clock()) else m }) }
            val withMsgs = t.copy(viber = viber)
            val to = if (viber?.messages?.any { it.status == MessageStatus.UNCERTAIN } == true) TaskStatus.NEEDS_ATTENTION else TaskStatus.CANCELLED
            TaskRules.transition(withMsgs, to, clock(), "Stopped by you")
        }
    }

    private suspend fun runAi(task: ScheduledTask) {
        val final = ai.run(task.prompt, task.preApproved)
        val now = clock()
        val safeToRetry = final.step == 0 && isTransient(final.detail) && task.attempts < maxAutoRetries
        val updated = store.update(task.id) { t ->
            when {
                final.detail == AgentController.BUSY ->
                    TaskRules.transition(t, TaskStatus.SCHEDULED, now, "Another task was running; trying again in a minute").copy(scheduledAt = now + 60_000, attempts = t.attempts - 1)
                final.status == RunStatus.SUCCEEDED -> TaskRules.transition(t, TaskStatus.COMPLETED, now, "Done: ${final.detail}").copy(lastResult = final.detail, lastError = null)
                final.status == RunStatus.NEEDS_ASSISTANCE -> TaskRules.transition(t, TaskStatus.WAITING_USER, now, "Paused for your approval: ${final.detail}").copy(lastResult = final.detail)
                final.status == RunStatus.CANCELLED -> TaskRules.transition(t, TaskStatus.CANCELLED, now, "Stopped")
                safeToRetry -> TaskRules.transition(t, TaskStatus.SCHEDULED, now, "Temporary problem before any action (${final.detail}); retrying in ${retryDelayMs / 60_000} min", true)
                    .copy(scheduledAt = now + retryDelayMs, lastError = final.detail)
                else -> TaskRules.transition(t, TaskStatus.FAILED, now, "Failed: ${final.detail}", true).copy(lastError = final.detail)
            }
        }
        updated?.let { if (it.status != TaskStatus.SCHEDULED) notify(it, "${it.status.label}: ${it.lastError ?: it.lastResult ?: ""}".trim().trimEnd(':')) }
    }

    private fun isTransient(detail: String) = listOf("network problem", "took too long", "rate", "429", "overloaded", "unavailable", "503", "502")
        .any { detail.contains(it, ignoreCase = true) }

    private fun updateMessage(taskId: String, msgId: String, transform: (ViberMessage) -> ViberMessage) =
        store.update(taskId) { t -> t.copy(viber = t.viber?.let { v -> v.copy(messages = v.messages.map { if (it.id == msgId) transform(it).copy(updatedAt = clock()) else it }) }, updatedAt = clock()) }

    private suspend fun runViber(task: ScheduledTask) {
        val batch = task.viber ?: run {
            store.update(task.id) { TaskRules.transition(it, TaskStatus.FAILED, clock(), "No messages", true) }; return
        }
        var first = true
        for (msg in batch.messages) {
            coroutineContext.ensureActive()
            val current = store.get(task.id)?.viber?.messages?.firstOrNull { it.id == msg.id } ?: continue
            if (current.status != MessageStatus.PENDING) continue
            if (!first) pause(batch.gapSeconds * 1_000L)
            first = false
            conditions.blocker(Requirements(needsNetwork = false))?.let { b ->
                store.update(task.id) { TaskRules.transition(it, TaskStatus.WAITING_DEVICE, clock(), "Paused: ${b.message}").copy(waitingReason = b.message) }
                    ?.let { notify(it, b.message) }
                return
            }
            val index = batch.messages.indexOfFirst { it.id == msg.id } + 1
            val log: (String) -> Unit = { line -> store.update(task.id) { it.withEvent("Message $index: $line", now = clock()) } }
            val outcome = viber.send(batch.groupName, current, batch.autoSend, log) {
                updateMessage(task.id, msg.id) { it.copy(status = MessageStatus.SENDING, error = null) }
            }
            when (outcome) {
                is SendOutcome.Sent -> { updateMessage(task.id, msg.id) { it.copy(status = MessageStatus.SENT, error = null) }; log("Sent (${outcome.evidence})") }
                is SendOutcome.Unconfirmed -> {
                    updateMessage(task.id, msg.id) { it.copy(status = MessageStatus.UNCERTAIN, error = outcome.reason) }
                    log("Unconfirmed: ${outcome.reason}. Stopping the batch so nothing is sent twice.")
                    break
                }
                is SendOutcome.NotSent -> {
                    updateMessage(task.id, msg.id) { it.copy(status = MessageStatus.FAILED, error = outcome.reason) }
                    log("Not sent: ${outcome.reason}. Remaining messages are kept.")
                    break
                }
            }
        }
        val final = store.update(task.id) { t ->
            val v = t.viber ?: return@update t
            val outcome = TaskRules.batchOutcome(v)
            val pendingLeft = v.messages.any { it.status == MessageStatus.PENDING }
            val err = v.messages.firstOrNull { it.status == MessageStatus.FAILED || it.status == MessageStatus.UNCERTAIN }?.error
            val sent = v.counts()[MessageStatus.SENT] ?: 0
            val summary = "$sent of ${v.messages.size} sent" + if (pendingLeft) ", ${v.messages.count { it.status == MessageStatus.PENDING }} kept for later" else ""
            TaskRules.transition(t, outcome, clock(), summary, outcome != TaskStatus.COMPLETED).copy(lastResult = summary, lastError = err)
        }
        final?.let { notify(it, "${it.status.label}: ${it.lastResult}") }
    }
}
