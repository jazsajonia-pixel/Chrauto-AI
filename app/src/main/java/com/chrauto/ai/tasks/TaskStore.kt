package com.chrauto.ai.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Small file-backed task queue (one JSON file, atomic replace on every write). Task counts are tiny,
 * so this avoids pulling in a database. All mutations go through [mutate] under one lock, which is
 * what makes "claim a due task" safe against two alarms or two callers racing.
 */
class TaskStore(private val file: File) {
    private val lock = Any()
    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    private fun load(): List<ScheduledTask> = runCatching { if (file.exists()) TaskJson.decode(file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun persist(list: List<ScheduledTask>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(TaskJson.encode(list))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun get(id: String): ScheduledTask? = _tasks.value.firstOrNull { it.id == id }

    /** Applies [transform] to the whole list atomically and persists it before publishing. */
    fun <R> mutate(transform: (List<ScheduledTask>) -> Pair<List<ScheduledTask>, R>): R = synchronized(lock) {
        val (next, result) = transform(_tasks.value)
        if (next !== _tasks.value) { persist(next); _tasks.value = next }
        result
    }

    fun upsert(task: ScheduledTask) = mutate { list ->
        val i = list.indexOfFirst { it.id == task.id }
        (if (i < 0) list + task else list.toMutableList().also { it[i] = task }) to Unit
    }

    /**
     * Saves a task from the editor. A new task is added; an existing one is replaced only if it is
     * still editable and unchanged since the editor opened ([baseUpdatedAt]), so an edit can never
     * overwrite a task that started running (or finished) in the meantime. Returns an error or null.
     */
    fun saveEdited(task: ScheduledTask, baseUpdatedAt: Long?): String? = mutate { list ->
        val i = list.indexOfFirst { it.id == task.id }
        when {
            i < 0 && baseUpdatedAt == null -> (list + task) to null
            i < 0 -> list to "This task was deleted"
            !TaskRules.isEditable(list[i]) -> list to "This task is ${list[i].status.label.lowercase()} and can't be edited now"
            list[i].updatedAt != baseUpdatedAt -> list to "This task changed while you were editing; open it again"
            else -> list.toMutableList().also { it[i] = task } to null
        }
    }

    /** Updates one task; returns the updated task or null if it does not exist. */
    fun update(id: String, transform: (ScheduledTask) -> ScheduledTask): ScheduledTask? = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) list to null
        else {
            val updated = transform(list[i])
            if (updated == list[i]) list to updated else list.toMutableList().also { it[i] = updated } to updated
        }
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id }.let { if (it.size == list.size) list else it } to Unit }

    /**
     * Atomically picks the oldest due task and marks it RUNNING (attempt +1). A second caller in the
     * same instant finds it already RUNNING and gets null, so a task can't start twice.
     */
    fun claimDue(now: Long): ScheduledTask? = mutate { list ->
        val due = TaskRules.nextDue(list, now) ?: return@mutate list to null
        val claimed = TaskRules.transition(due, TaskStatus.RUNNING, now, "Started (attempt ${due.attempts + 1})")
        list.map { if (it.id == due.id) claimed else it } to claimed
    }

    /** Claims a specific task (Run now / resume after unlock) if it is in a startable state. */
    fun claim(id: String, now: Long, from: Set<TaskStatus>): ScheduledTask? = mutate { list ->
        val t = list.firstOrNull { it.id == id }?.takeIf { it.status in from } ?: return@mutate list to null
        val claimed = TaskRules.transition(t, TaskStatus.RUNNING, now, "Started (attempt ${t.attempts + 1})")
        if (claimed.status != TaskStatus.RUNNING) return@mutate list to null
        list.map { if (it.id == id) claimed else it } to claimed
    }

    fun recoverInterrupted(now: Long, activeId: String?) = mutate { list ->
        val next = TaskRules.recoverInterrupted(list, now, activeId)
        (if (next == list) list else next) to Unit
    }

    companion object { fun newId(): String = UUID.randomUUID().toString() }
}
