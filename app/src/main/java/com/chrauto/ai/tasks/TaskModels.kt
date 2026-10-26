package com.chrauto.ai.tasks

import org.json.JSONArray
import org.json.JSONObject

/**
 * Real task states. Each one maps to something the app actually knows; nothing is shown as
 * "running" unless a run is executing, and nothing is "completed" unless it was verified.
 */
enum class TaskStatus(val label: String, val terminal: Boolean) {
    DRAFT("Draft", false),
    SCHEDULED("Scheduled", false),
    RUNNING("Running", false),
    WAITING_DEVICE("Waiting for device", false),
    WAITING_USER("Waiting for user", false),
    NEEDS_ATTENTION("Needs attention", false),
    COMPLETED("Completed", true),
    PARTIAL("Partially completed", true),
    FAILED("Failed", true),
    CANCELLED("Cancelled", true);

    companion object { fun from(id: String?) = entries.firstOrNull { it.name == id } ?: DRAFT }
}

enum class TaskKind { AI_TASK, VIBER_BATCH }

/** Per-message state in a Viber batch. UNCERTAIN = Send may have happened; never retried automatically. */
enum class MessageStatus(val label: String) {
    PENDING("Pending"), SENDING("Sending"), SENT("Sent"), UNCERTAIN("Unconfirmed"), FAILED("Failed"), CANCELLED("Cancelled");
    companion object { fun from(id: String?) = entries.firstOrNull { it.name == id } ?: PENDING }
}

data class ViberMessage(
    val id: String,
    val text: String,
    /** Absolute paths of images copied into the app's private storage (survive restarts). */
    val images: List<String> = emptyList(),
    val status: MessageStatus = MessageStatus.PENDING,
    val error: String? = null,
    val updatedAt: Long = 0
)

data class ViberBatch(
    /** Exact group name as shown in Viber. Only an exact, unique match is ever selected. */
    val groupName: String,
    val messages: List<ViberMessage>,
    /** false = Chrauto prepares the message and picks the group, you tap Send. */
    val autoSend: Boolean = false,
    val gapSeconds: Int = 20
) {
    val pending get() = messages.filter { it.status == MessageStatus.PENDING }
    fun counts(): Map<MessageStatus, Int> = messages.groupingBy { it.status }.eachCount()
}

data class TaskEvent(val at: Long, val message: String, val error: Boolean = false)

data class ScheduledTask(
    val id: String,
    val title: String,
    val kind: TaskKind = TaskKind.AI_TASK,
    /** Natural-language instruction for AI tasks. */
    val prompt: String = "",
    /** Epoch millis; null = run on demand only. */
    val scheduledAt: Long? = null,
    val zoneId: String = java.util.TimeZone.getDefault().id,
    val status: TaskStatus = TaskStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val attempts: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    /** The user approved send/submit steps for this task when scheduling it. */
    val preApproved: Boolean = false,
    /** Why the task is waiting (screen off, locked, accessibility off, no network). */
    val waitingReason: String? = null,
    val viber: ViberBatch? = null,
    val history: List<TaskEvent> = emptyList()
) {
    fun withEvent(message: String, error: Boolean = false, now: Long = System.currentTimeMillis()) =
        copy(history = (history + TaskEvent(now, message.take(300), error)).takeLast(MAX_HISTORY), updatedAt = now)

    companion object { const val MAX_HISTORY = 40 }
}

/** org.json serialization, kept dependency-free so it runs in JVM unit tests. */
object TaskJson {
    fun encode(tasks: List<ScheduledTask>): String = JSONArray().apply { tasks.forEach { put(task(it)) } }.toString()

    fun decode(text: String): List<ScheduledTask> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i -> runCatching { task(a.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun task(t: ScheduledTask) = JSONObject().apply {
        put("id", t.id); put("title", t.title); put("kind", t.kind.name); put("prompt", t.prompt)
        t.scheduledAt?.let { put("scheduledAt", it) }
        put("zoneId", t.zoneId); put("status", t.status.name)
        put("createdAt", t.createdAt); put("updatedAt", t.updatedAt); put("attempts", t.attempts)
        t.startedAt?.let { put("startedAt", it) }; t.finishedAt?.let { put("finishedAt", it) }
        t.lastResult?.let { put("lastResult", it) }; t.lastError?.let { put("lastError", it) }
        put("preApproved", t.preApproved)
        t.waitingReason?.let { put("waitingReason", it) }
        t.viber?.let { v ->
            put("viber", JSONObject().apply {
                put("groupName", v.groupName); put("autoSend", v.autoSend); put("gapSeconds", v.gapSeconds)
                put("messages", JSONArray().apply {
                    v.messages.forEach { m ->
                        put(JSONObject().apply {
                            put("id", m.id); put("text", m.text); put("images", JSONArray(m.images))
                            put("status", m.status.name); m.error?.let { put("error", it) }; put("updatedAt", m.updatedAt)
                        })
                    }
                })
            })
        }
        put("history", JSONArray().apply { t.history.forEach { e -> put(JSONObject().put("at", e.at).put("m", e.message).put("e", e.error)) } })
    }

    private fun task(o: JSONObject): ScheduledTask {
        val created = o.optLong("createdAt", System.currentTimeMillis())
        return ScheduledTask(
            id = o.getString("id"),
            title = o.optString("title"),
            kind = runCatching { TaskKind.valueOf(o.optString("kind")) }.getOrDefault(TaskKind.AI_TASK),
            prompt = o.optString("prompt"),
            scheduledAt = if (o.has("scheduledAt")) o.getLong("scheduledAt") else null,
            zoneId = o.optString("zoneId", java.util.TimeZone.getDefault().id),
            status = TaskStatus.from(o.optString("status")),
            createdAt = created,
            updatedAt = o.optLong("updatedAt", created),
            attempts = o.optInt("attempts"),
            startedAt = if (o.has("startedAt")) o.getLong("startedAt") else null,
            finishedAt = if (o.has("finishedAt")) o.getLong("finishedAt") else null,
            lastResult = o.optStringOrNull("lastResult"),
            lastError = o.optStringOrNull("lastError"),
            preApproved = o.optBoolean("preApproved"),
            waitingReason = o.optStringOrNull("waitingReason"),
            viber = o.optJSONObject("viber")?.let { v ->
                val ms = v.optJSONArray("messages") ?: JSONArray()
                ViberBatch(
                    groupName = v.optString("groupName"),
                    autoSend = v.optBoolean("autoSend"),
                    gapSeconds = v.optInt("gapSeconds", 20),
                    messages = (0 until ms.length()).map { i ->
                        val m = ms.getJSONObject(i)
                        val imgs = m.optJSONArray("images") ?: JSONArray()
                        ViberMessage(
                            id = m.getString("id"), text = m.optString("text"),
                            images = (0 until imgs.length()).map { imgs.getString(it) },
                            status = MessageStatus.from(m.optString("status")),
                            error = m.optStringOrNull("error"), updatedAt = m.optLong("updatedAt")
                        )
                    }
                )
            },
            history = o.optJSONArray("history")?.let { h ->
                (0 until h.length()).map { i -> h.getJSONObject(i).let { TaskEvent(it.optLong("at"), it.optString("m"), it.optBoolean("e")) } }
            }.orEmpty()
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
}
