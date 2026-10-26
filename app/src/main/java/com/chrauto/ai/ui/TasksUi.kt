package com.chrauto.ai.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrauto.ai.tasks.MessageStatus
import com.chrauto.ai.tasks.ScheduledTask
import com.chrauto.ai.tasks.TaskKind
import com.chrauto.ai.tasks.TaskRules
import com.chrauto.ai.tasks.TaskStatus
import com.chrauto.ai.tasks.TaskStore
import com.chrauto.ai.tasks.ViberBatch
import com.chrauto.ai.tasks.ViberMessage
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.Date

/** Everything the task screens can do; wired to [com.chrauto.ai.tasks.TaskRunner] in the app, faked in tests. */
class TaskActions(
    val save: (task: ScheduledTask, baseUpdatedAt: Long?) -> String? = { _, _ -> null },
    val delete: (String) -> Unit = {},
    val runNow: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    val cancel: (String) -> Unit = {},
    val retry: (String) -> String? = { null },
    val markMessage: (taskId: String, msgId: String, sent: Boolean) -> Unit = { _, _, _ -> },
    /** Copies picked images into private storage; returns stored paths and an error, if any. */
    val importImages: suspend (List<Uri>) -> Pair<List<String>, String?> = { emptyList<String>() to null },
    val exactAlarms: Boolean = true,
    val requestExactAlarms: () -> Unit = {},
    val viberInstalled: Boolean = true,
    val activeId: () -> String? = { null }
)

@Composable
fun StatusPill(status: TaskStatus, modifier: Modifier = Modifier, label: String = status.label) {
    val (fg, bg) = when (status) {
        TaskStatus.COMPLETED -> Brand.Success to Brand.SuccessSoft
        TaskStatus.FAILED -> Brand.Danger to Brand.DangerSoft
        TaskStatus.NEEDS_ATTENTION, TaskStatus.WAITING_USER, TaskStatus.PARTIAL -> Brand.Warn to Brand.WarnSoft
        TaskStatus.RUNNING, TaskStatus.SCHEDULED -> Brand.Accent to Brand.AccentSoft
        else -> Brand.Muted to Brand.Chip
    }
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun formatWhen(context: android.content.Context, at: Long?): String {
    if (at == null) return "Not scheduled"
    val d = Date(at)
    return DateFormat.getMediumDateFormat(context).format(d) + ", " + DateFormat.getTimeFormat(context).format(d)
}

private enum class Filter(val label: String, val match: (TaskStatus) -> Boolean) {
    ACTIVE("Active", { it in setOf(TaskStatus.DRAFT, TaskStatus.SCHEDULED, TaskStatus.RUNNING, TaskStatus.WAITING_DEVICE, TaskStatus.WAITING_USER) }),
    ATTENTION("Needs attention", { it in setOf(TaskStatus.NEEDS_ATTENTION, TaskStatus.FAILED, TaskStatus.PARTIAL) }),
    DONE("History", { it in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.FAILED, TaskStatus.PARTIAL) })
}

/** Home-screen section: scheduled, running and past tasks. */
@Composable
fun TasksSection(tasks: List<ScheduledTask>, onOpen: (String) -> Unit, onNewTask: () -> Unit, onNewViber: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ACTIVE) }
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.Card)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scheduled tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                val attention = tasks.count { Filter.ATTENTION.match(it.status) }
                if (attention > 0) Text("$attention need attention", color = Brand.Warn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            val shown = tasks.filter { filter.match(it.status) }.sortedWith(compareBy<ScheduledTask> { it.status.terminal }.thenBy { it.scheduledAt ?: Long.MAX_VALUE }.thenByDescending { it.updatedAt })
            if (shown.isEmpty()) {
                Text(
                    when (filter) {
                        Filter.ACTIVE -> "No scheduled tasks. Schedule an automation or a Viber group message to run later."
                        Filter.ATTENTION -> "Nothing needs your attention."
                        Filter.DONE -> "Finished tasks will be listed here."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted
                )
            } else shown.take(30).forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(t.id) }.heightIn(min = 56.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp).testTag("taskRow"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = buildString {
                            append(if (t.kind == TaskKind.VIBER_BATCH) "Viber · " else "Automation · ")
                            append(formatWhen(context, t.scheduledAt))
                            t.waitingReason?.let { append(" · ").append(it) }
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(t.status, label = TaskRules.displayLabel(t, System.currentTimeMillis()))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onNewTask, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("newScheduleButton"), shape = RoundedCornerShape(12.dp)) {
                    Text("Schedule task", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onNewViber, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("newViberButton"), shape = RoundedCornerShape(12.dp)) {
                    Text("Viber message", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brand.Canvas).systemBarsPadding().imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("backButton")) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        }
        HorizontalDivider(color = Brand.Line)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Brand.Card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun LabelValue(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TaskDetailScreen(store: TaskStore?, taskId: String, actions: TaskActions, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val tasks by (store?.tasks ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val task = tasks.firstOrNull { it.id == taskId }
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(task?.title ?: "Task", onBack) {
        if (task == null) { Text("This task no longer exists.", color = Brand.Muted); return@ScreenScaffold }
        val running = task.status == TaskStatus.RUNNING
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (task.kind == TaskKind.VIBER_BATCH) "Viber group message" else "Automation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(task.status, Modifier.testTag("detailStatus"), TaskRules.displayLabel(task, System.currentTimeMillis()))
            }
            LabelValue("Scheduled", formatWhen(context, task.scheduledAt))
            LabelValue("Time zone", task.zoneId)
            LabelValue("Attempts", task.attempts.toString())
            task.startedAt?.let { LabelValue("Last started", formatWhen(context, it)) }
            task.finishedAt?.let { LabelValue("Finished", formatWhen(context, it)) }
            task.waitingReason?.let { LabelValue("Waiting for", it, Brand.Warn) }
            task.lastResult?.let { LabelValue("Result", it) }
            task.lastError?.let { LabelValue("Last error", it, Brand.Danger) }
            if (task.kind == TaskKind.AI_TASK) LabelValue("Instruction", task.prompt)
            if (task.kind == TaskKind.AI_TASK) LabelValue("Send/submit", if (task.preApproved) "Approved in advance" else "Pauses for your approval")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (running || task.status in setOf(TaskStatus.SCHEDULED, TaskStatus.WAITING_DEVICE, TaskStatus.WAITING_USER, TaskStatus.DRAFT, TaskStatus.NEEDS_ATTENTION)) {
                OutlinedButton(onClick = { actions.cancel(task.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("cancelTaskButton"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Danger), shape = RoundedCornerShape(12.dp)) { Text(if (running) "Stop" else "Cancel") }
            }
            if (!running && task.status in setOf(TaskStatus.DRAFT, TaskStatus.SCHEDULED, TaskStatus.WAITING_DEVICE, TaskStatus.WAITING_USER)) {
                Button(onClick = { actions.runNow(task.id) { message = it } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("runNowButton"), shape = RoundedCornerShape(12.dp)) { Text("Run now") }
            }
            if (!running && task.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.NEEDS_ATTENTION, TaskStatus.CANCELLED)) {
                Button(onClick = { message = actions.retry(task.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("retryButton"), shape = RoundedCornerShape(12.dp)) { Text("Retry") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (TaskRules.isEditable(task)) OutlinedButton(onClick = { onEdit(task.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Edit") }
            if (!running) OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Delete") }
        }
        message?.let { Text(it, color = Brand.Warn, style = MaterialTheme.typography.bodySmall) }

        task.viber?.let { v ->
            Panel {
                Text("Messages to “${v.groupName}”", style = MaterialTheme.typography.titleSmall)
                Text(if (v.autoSend) "Chrauto taps Send itself, then checks the message appears." else "Chrauto prepares each message in the group; you tap Send.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                v.messages.forEachIndexed { i, m ->
                    HorizontalDivider(color = Brand.Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. " + m.text.ifBlank { "(images only)" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(m.status.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = when (m.status) {
                            MessageStatus.SENT -> Brand.Success; MessageStatus.FAILED -> Brand.Danger; MessageStatus.UNCERTAIN -> Brand.Warn; else -> Brand.Muted
                        })
                    }
                    if (m.images.isNotEmpty()) Text("${m.images.size} image(s)", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Danger) }
                    if (m.status == MessageStatus.UNCERTAIN) {
                        Text("Open the group in Viber and check whether this message is there.", style = MaterialTheme.typography.bodySmall, color = Brand.Warn)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { actions.markMessage(task.id, m.id, true) }, modifier = Modifier.heightIn(min = 48.dp).testTag("markSent"), shape = RoundedCornerShape(12.dp)) { Text("It was sent") }
                            OutlinedButton(onClick = { actions.markMessage(task.id, m.id, false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("markNotSent"), shape = RoundedCornerShape(12.dp)) { Text("Not sent") }
                        }
                    }
                }
            }
        }

        Panel {
            Text("History", style = MaterialTheme.typography.titleSmall)
            if (task.history.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            task.history.asReversed().forEach { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(if (e.error) Brand.Danger else Brand.Muted))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(e.message, style = MaterialTheme.typography.bodySmall, color = if (e.error) Brand.Danger else MaterialTheme.colorScheme.onSurface)
                        Text(formatWhen(context, e.at), fontSize = 11.sp, color = Brand.Muted)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this task?") },
        text = { Text("It is removed from the list with its history. This doesn't undo anything already done on the phone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete(taskId); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
    )
}

/** Date + time row using the platform pickers (honours the phone's 12/24-hour setting). */
@Composable
private fun WhenPicker(at: Long?, onChange: (Long?) -> Unit, actions: TaskActions) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("When", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatWhen(context, at), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                val c = Calendar.getInstance().apply { timeInMillis = at ?: (System.currentTimeMillis() + 10 * 60_000) }
                DatePickerDialog(context, { _, y, m, d ->
                    c.set(y, m, d)
                    TimePickerDialog(context, { _, h, min ->
                        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                        onChange(c.timeInMillis)
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 60_000 }.show()
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("pickTime"), shape = RoundedCornerShape(12.dp)) { Text(if (at == null) "Pick time" else "Change") }
        }
        if (at != null && at < System.currentTimeMillis()) Text("This time has passed; the task will run as soon as the phone is ready.", style = MaterialTheme.typography.bodySmall, color = Brand.Warn)
        if (!actions.exactAlarms) {
            Text("Exact alarms are off, so Android may start this a few minutes late.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            TextButton(onClick = actions.requestExactAlarms, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow exact alarms") }
        }
        Text("The phone must be unlocked with the screen on for Chrauto to act. If it is locked at that time, the task waits and starts when you unlock.",
            style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
    }
}

@Composable
fun TaskEditorScreen(store: TaskStore?, editId: String?, initialPrompt: String, actions: TaskActions, onBack: () -> Unit) {
    val existing = remember(editId) { editId?.let { store?.get(it) } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var prompt by rememberSaveable { mutableStateOf(existing?.prompt ?: initialPrompt) }
    var at by rememberSaveable { mutableStateOf(existing?.scheduledAt) }
    var approved by rememberSaveable { mutableStateOf(existing?.preApproved ?: false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(if (existing == null) "Schedule task" else "Edit task", onBack) {
        Panel {
            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth().testTag("scheduleTitle"), label = { Text("Name (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(prompt, { prompt = it.take(2000) }, Modifier.fillMaxWidth().testTag("schedulePrompt"), label = { Text("What should Chrauto do?") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            WhenPicker(at, { at = it }, actions)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow send/submit steps", style = MaterialTheme.typography.titleSmall)
                    Text("Off: the task pauses as \"Waiting for user\" before sending, paying, deleting or posting.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                Switch(approved, { approved = it })
            }
        }
        fun build(status: TaskStatus): ScheduledTask {
            val now = System.currentTimeMillis()
            val base = existing ?: ScheduledTask(id = TaskStore.newId(), title = "", createdAt = now)
            return base.copy(
                title = title.ifBlank { prompt.trim().take(48) }, kind = TaskKind.AI_TASK, prompt = prompt.trim(), scheduledAt = at,
                zoneId = java.util.TimeZone.getDefault().id, preApproved = approved, status = status, updatedAt = now, lastError = null
            ).withEvent(if (existing == null) "Created (${status.label})" else "Edited (${status.label})", now = now)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = prompt.isNotBlank(), onClick = { saveError = actions.save(build(TaskStatus.DRAFT), existing?.updatedAt); if (saveError == null) onBack() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save draft") }
            Button(enabled = prompt.isNotBlank() && at != null, onClick = { saveError = actions.save(build(TaskStatus.SCHEDULED), existing?.updatedAt); if (saveError == null) onBack() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("saveSchedule"), shape = RoundedCornerShape(12.dp)) { Text("Schedule") }
        }
        saveError?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }
    }
}

private data class DraftMessage(val id: String, val text: String, val images: List<String>, val status: MessageStatus = MessageStatus.PENDING)

@Composable
fun ViberComposerScreen(store: TaskStore?, editId: String?, actions: TaskActions, onBack: () -> Unit) {
    val existing = remember(editId) { editId?.let { store?.get(it) } }
    val scope = rememberCoroutineScope()
    var group by rememberSaveable { mutableStateOf(existing?.viber?.groupName ?: "") }
    var at by rememberSaveable { mutableStateOf(existing?.scheduledAt) }
    var autoSend by rememberSaveable { mutableStateOf(existing?.viber?.autoSend ?: false) }
    var gap by rememberSaveable { mutableStateOf((existing?.viber?.gapSeconds ?: 20).toString()) }
    val messages = remember {
        mutableStateListOf<DraftMessage>().apply {
            existing?.viber?.messages?.forEach { add(DraftMessage(it.id, it.text, it.images, it.status)) }
            if (isEmpty()) add(DraftMessage(TaskStore.newId(), "", emptyList()))
        }
    }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val target = pickFor ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val (paths, err) = actions.importImages(uris)
            val i = messages.indexOfFirst { it.id == target }
            if (i >= 0) messages[i] = messages[i].copy(images = (messages[i].images + paths).take(10))
            error = err; importing = false
        }
    }
    val editableMsg = { m: DraftMessage -> m.status == MessageStatus.PENDING || m.status == MessageStatus.FAILED || m.status == MessageStatus.CANCELLED }

    fun build(status: TaskStatus): ScheduledTask {
        val now = System.currentTimeMillis()
        val base = existing ?: ScheduledTask(id = TaskStore.newId(), title = "", kind = TaskKind.VIBER_BATCH, createdAt = now)
        val batch = ViberBatch(
            groupName = group.trim(), autoSend = autoSend, gapSeconds = (gap.toIntOrNull() ?: 20).coerceIn(5, 600),
            messages = messages.map { d ->
                val old = existing?.viber?.messages?.firstOrNull { it.id == d.id }
                // Re-scheduling re-queues messages that definitely weren't sent; Sent/Unconfirmed stay as they are.
                val requeue = status == TaskStatus.SCHEDULED && (old?.status == MessageStatus.FAILED || old?.status == MessageStatus.CANCELLED)
                ViberMessage(d.id, d.text.trim(), d.images, status = if (requeue) MessageStatus.PENDING else old?.status ?: MessageStatus.PENDING,
                    error = if (requeue) null else old?.error, updatedAt = now)
            }
        )
        return base.copy(
            title = "Viber: ${group.trim()} (${messages.size} message${if (messages.size == 1) "" else "s"})", kind = TaskKind.VIBER_BATCH,
            scheduledAt = at, zoneId = java.util.TimeZone.getDefault().id, status = status, viber = batch, updatedAt = now, lastError = null
        ).withEvent(if (existing == null) "Created (${status.label})" else "Edited (${status.label})", now = now)
    }
    ScreenScaffold(if (existing == null) "Viber group message" else "Edit Viber message", onBack) {
        if (!actions.viberInstalled) Text("Viber is not installed on this phone. You can still prepare the batch.", color = Brand.Warn, style = MaterialTheme.typography.bodySmall)
        Panel {
            OutlinedTextField(group, { group = it.take(80) }, Modifier.fillMaxWidth().testTag("viberGroup"), label = { Text("Group name, exactly as in Viber") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                supportingText = { Text("Chrauto only picks a chat whose name matches exactly and uniquely; otherwise it stops and asks you.") })
            WhenPicker(at, { at = it }, actions)
        }
        messages.forEachIndexed { i, m ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Message ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (m.status != MessageStatus.PENDING) Text(m.status.label, fontSize = 12.sp, color = Brand.Muted)
                    TextButton(enabled = i > 0, onClick = { messages.add(i - 1, messages.removeAt(i)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Up") }
                    TextButton(enabled = i < messages.size - 1, onClick = { messages.add(i + 1, messages.removeAt(i)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Down") }
                }
                OutlinedTextField(m.text, { v -> messages[i] = m.copy(text = v.take(4000)) }, Modifier.fillMaxWidth().testTag("viberText$i"),
                    enabled = editableMsg(m), label = { Text("Text") }, minLines = 2, shape = RoundedCornerShape(12.dp))
                if (m.images.isNotEmpty()) {
                    m.images.forEachIndexed { k, path ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Image ${k + 1}" + if (!File(path).exists()) " (missing)" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (editableMsg(m)) TextButton(onClick = { messages[i] = m.copy(images = m.images - path) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editableMsg(m)) OutlinedButton(enabled = !importing && m.images.size < 10, onClick = {
                        pickFor = m.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text(if (importing) "Adding…" else "Add images") }
                    if (messages.size > 1 && editableMsg(m)) TextButton(onClick = { messages.removeAt(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove message") }
                }
            }
        }
        OutlinedButton(onClick = { messages.add(DraftMessage(TaskStore.newId(), "", emptyList())) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Add another message") }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tap Send for me", style = MaterialTheme.typography.titleSmall)
                    Text(if (autoSend) "Chrauto taps Send once per message and checks it appears in the chat. If it can't confirm, it stops and asks you; it never re-sends on its own."
                        else "Recommended. Chrauto opens the group with the message ready and waits up to 2 minutes for you to tap Send.",
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                Switch(autoSend, { autoSend = it }, modifier = Modifier.testTag("autoSendSwitch"))
            }
            OutlinedTextField(gap, { gap = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("Seconds between messages") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
            Text("If a message fails, the rest of the batch stops so nothing is sent out of order.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
        error?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }
        val valid = group.isNotBlank() && messages.isNotEmpty() && messages.all { it.text.isNotBlank() || it.images.isNotEmpty() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = valid, onClick = { error = actions.save(build(TaskStatus.DRAFT), existing?.updatedAt); if (error == null) onBack() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save draft") }
            Button(enabled = valid && at != null, onClick = { preview = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("viberPreview"), shape = RoundedCornerShape(12.dp)) { Text("Preview") }
        }
    }
    if (preview) AlertDialog(
        onDismissRequest = { preview = false },
        title = { Text("Schedule these messages?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("To: “${group.trim()}” on Viber")
                Text("At: ${formatWhen(LocalContext.current, at)}")
                Text(if (autoSend) "Chrauto will tap Send." else "You will tap Send for each message.")
                messages.forEachIndexed { i, m -> Text("${i + 1}. ${m.text.ifBlank { "(images only)" }.take(120)}" + if (m.images.isNotEmpty()) " + ${m.images.size} image(s)" else "", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { preview = false; error = actions.save(build(TaskStatus.SCHEDULED), existing?.updatedAt); if (error == null) onBack() }, modifier = Modifier.testTag("confirmSchedule")) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = { preview = false }) { Text("Keep editing") } }
    )
}
