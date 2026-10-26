package com.chrauto.ai.tasks

/**
 * Pure scheduling / state-machine rules. Kept free of Android types so every rule is unit tested.
 */
object TaskRules {
    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.DRAFT to setOf(TaskStatus.SCHEDULED, TaskStatus.RUNNING, TaskStatus.CANCELLED),
        TaskStatus.SCHEDULED to setOf(TaskStatus.RUNNING, TaskStatus.WAITING_DEVICE, TaskStatus.DRAFT, TaskStatus.CANCELLED, TaskStatus.SCHEDULED),
        TaskStatus.RUNNING to setOf(
            TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.FAILED, TaskStatus.CANCELLED,
            TaskStatus.WAITING_DEVICE, TaskStatus.WAITING_USER, TaskStatus.NEEDS_ATTENTION, TaskStatus.SCHEDULED
        ),
        TaskStatus.WAITING_DEVICE to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.SCHEDULED, TaskStatus.FAILED),
        TaskStatus.WAITING_USER to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.SCHEDULED, TaskStatus.FAILED, TaskStatus.COMPLETED, TaskStatus.PARTIAL),
        TaskStatus.NEEDS_ATTENTION to setOf(TaskStatus.SCHEDULED, TaskStatus.CANCELLED, TaskStatus.RUNNING, TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.FAILED),
        TaskStatus.FAILED to setOf(TaskStatus.SCHEDULED, TaskStatus.RUNNING),
        TaskStatus.PARTIAL to setOf(TaskStatus.SCHEDULED, TaskStatus.RUNNING),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.CANCELLED to setOf(TaskStatus.SCHEDULED, TaskStatus.DRAFT)
    )

    fun canTransition(from: TaskStatus, to: TaskStatus) = to in allowed[from].orEmpty()

    /** Returns the task moved to [to], or the unchanged task if the move is not allowed. */
    fun transition(task: ScheduledTask, to: TaskStatus, now: Long, note: String? = null, error: Boolean = false): ScheduledTask {
        if (!canTransition(task.status, to)) return task
        var t = task.copy(status = to, updatedAt = now)
        if (to == TaskStatus.RUNNING) t = t.copy(startedAt = now, attempts = task.attempts + 1, waitingReason = null, finishedAt = null)
        if (to.terminal) t = t.copy(finishedAt = now, waitingReason = null)
        return if (note != null) t.withEvent(note, error, now) else t
    }

    /** A task is due if it is scheduled and its time has come. */
    fun isDue(task: ScheduledTask, now: Long) = task.status == TaskStatus.SCHEDULED && (task.scheduledAt ?: Long.MAX_VALUE) <= now

    /** Oldest due task first; UI automation is serial, so one at a time. */
    fun nextDue(tasks: List<ScheduledTask>, now: Long): ScheduledTask? =
        tasks.filter { isDue(it, now) }.minByOrNull { it.scheduledAt ?: 0 }

    /** Time of the next alarm to set, or null when nothing is scheduled. */
    fun nextAlarmAt(tasks: List<ScheduledTask>): Long? =
        tasks.filter { it.status == TaskStatus.SCHEDULED }.mapNotNull { it.scheduledAt }.minOrNull()

    /**
     * After the process was killed or the phone rebooted, a task still marked RUNNING was interrupted
     * mid-way. It is never re-run automatically (it may already have sent something); it goes to
     * NEEDS_ATTENTION, and any Viber message that was mid-send becomes UNCERTAIN.
     */
    fun recoverInterrupted(tasks: List<ScheduledTask>, now: Long, activeId: String?): List<ScheduledTask> = tasks.map { t ->
        if (t.status != TaskStatus.RUNNING || t.id == activeId) t
        else {
            val viber = t.viber?.let { v ->
                v.copy(messages = v.messages.map { m -> if (m.status == MessageStatus.SENDING) m.copy(status = MessageStatus.UNCERTAIN, error = "Interrupted while sending; check Viber before retrying", updatedAt = now) else m })
            }
            t.copy(viber = viber).let { transition(it, TaskStatus.NEEDS_ATTENTION, now, "Interrupted (app closed or phone restarted). Not re-run automatically.", true) }
        }
    }

    /**
     * A user-requested retry. Only messages that definitely were not sent are re-queued; UNCERTAIN
     * ones stay as they are until the user checks Viber and marks them.
     */
    fun retry(task: ScheduledTask, now: Long, at: Long = now): ScheduledTask? {
        if (task.status !in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.NEEDS_ATTENTION, TaskStatus.CANCELLED, TaskStatus.WAITING_USER, TaskStatus.WAITING_DEVICE)) return null
        val viber = task.viber?.let { v ->
            if (v.messages.none { it.status == MessageStatus.FAILED || it.status == MessageStatus.PENDING || it.status == MessageStatus.CANCELLED }) return null
            v.copy(messages = v.messages.map { if (it.status == MessageStatus.FAILED || it.status == MessageStatus.CANCELLED) it.copy(status = MessageStatus.PENDING, error = null, updatedAt = now) else it })
        }
        val base = task.copy(viber = viber, scheduledAt = at, lastError = null)
        // Route through SCHEDULED so the normal claim path (with duplicate protection) runs it.
        val viaScheduled = if (canTransition(base.status, TaskStatus.SCHEDULED)) base.copy(status = TaskStatus.SCHEDULED, updatedAt = now) else return null
        return viaScheduled.withEvent("Retry requested", now = now)
    }

    /** Final status for a Viber batch from its message states. */
    fun batchOutcome(batch: ViberBatch): TaskStatus {
        val c = batch.counts()
        val total = batch.messages.size
        val sent = c[MessageStatus.SENT] ?: 0
        return when {
            total == 0 -> TaskStatus.FAILED
            sent == total -> TaskStatus.COMPLETED
            (c[MessageStatus.UNCERTAIN] ?: 0) > 0 -> TaskStatus.NEEDS_ATTENTION
            sent > 0 -> TaskStatus.PARTIAL
            (c[MessageStatus.CANCELLED] ?: 0) == total -> TaskStatus.CANCELLED
            else -> TaskStatus.FAILED
        }
    }

    /**
     * The user checked Viber and says whether an Unconfirmed message really went out. Once nothing is
     * unconfirmed, a NEEDS_ATTENTION batch gets its real outcome; messages marked "not sent" can then
     * be retried.
     */
    fun markMessage(task: ScheduledTask, msgId: String, sent: Boolean, now: Long): ScheduledTask {
        val v = task.viber ?: return task
        if (v.messages.none { it.id == msgId && it.status == MessageStatus.UNCERTAIN }) return task
        val nv = v.copy(messages = v.messages.map {
            if (it.id != msgId) it
            else it.copy(status = if (sent) MessageStatus.SENT else MessageStatus.FAILED, error = if (sent) null else "You marked it as not sent", updatedAt = now)
        })
        var t = task.copy(viber = nv).withEvent(if (sent) "You confirmed a message was sent" else "You marked a message as not sent", now = now)
        if (t.status == TaskStatus.NEEDS_ATTENTION && nv.messages.none { it.status == MessageStatus.UNCERTAIN }) t = transition(t, batchOutcome(nv), now)
        return t
    }

    /** Tasks the editor may change: never one that is running or waiting to resume. */
    fun isEditable(task: ScheduledTask) = task.status in setOf(TaskStatus.DRAFT, TaskStatus.SCHEDULED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.PARTIAL)

    /** "Ready" = scheduled and due, about to start once the phone allows it. */
    fun displayLabel(task: ScheduledTask, now: Long): String =
        if (task.status == TaskStatus.SCHEDULED && (task.scheduledAt ?: Long.MAX_VALUE) <= now) "Ready" else task.status.label
}
