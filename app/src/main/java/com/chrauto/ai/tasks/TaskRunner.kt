package com.chrauto.ai.tasks

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chrauto.ai.MainActivity
import com.chrauto.ai.R
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.viber.ViberSender
import com.chrauto.ai.viber.ViberShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide owner of the persistent task queue.
 *
 * Execution model: a scheduled task needs the accessibility service, a lit and unlocked screen
 * (and internet for AI tasks). An alarm wakes the app at the scheduled time; if the phone is ready,
 * the task runs right away in this process (which the bound accessibility service keeps alive, so no
 * extra foreground service is needed). If not, the task is kept as "Waiting for device" and resumes
 * when the screen is unlocked, the service reconnects, or a fallback check fires.
 */
object TaskRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var store: TaskStore? = null
    private var engine: TaskEngine? = null
    private var appContext: Context? = null
    private var job: Job? = null

    fun store(context: Context): TaskStore { init(context); return store!! }

    @Synchronized fun init(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        appContext = app
        val s = TaskStore(File(app.filesDir, "tasks.json"))
        store = s
        engine = TaskEngine(
            store = s,
            conditions = AndroidDeviceConditions(app),
            ai = AiTaskRunner { prompt, approved -> AgentController.runForTask(prompt, approved) },
            viber = ViberMessageSender { group, msg, auto, log, onAboutToSend ->
                ViberSender(AccessibilityBridge, ViberShare.launcher(app), log).send(group, msg, auto, onAboutToSend)
            },
            notify = { t, text -> TaskNotifier.show(app, t, text) }
        )
        AgentController.init(app)
        // A fresh process means nothing is executing: anything left RUNNING was interrupted.
        s.recoverInterrupted(System.currentTimeMillis(), activeId = null)
        TaskScheduler.sync(app, s.tasks.value)
    }

    val activeId: String? get() = engine?.activeId
    val busy get() = job?.isActive == true

    /** Runs whatever is due or waiting. Safe to call often (alarm, unlock, service connect, app open). */
    fun kick(context: Context, @Suppress("UNUSED_PARAMETER") why: String) {
        init(context)
        if (job?.isActive == true) return
        val e = engine ?: return
        job = scope.launch {
            try { e.processAvailable() } finally { appContext?.let { TaskScheduler.sync(it, store!!.tasks.value) } }
        }
    }

    fun runNow(context: Context, id: String, onResult: (String?) -> Unit) {
        init(context)
        if (job?.isActive == true || AgentController.isRunning) { onResult(AgentController.BUSY); return }
        val e = engine ?: return
        job = scope.launch {
            var error: String? = null
            try { error = e.runNow(id) } finally { onResult(error); appContext?.let { TaskScheduler.sync(it, store!!.tasks.value) } }
        }
    }

    /** Stops the task if it is the one running; otherwise just cancels it. */
    fun cancel(context: Context, id: String) {
        init(context)
        val s = store!!
        if (activeId == id) {
            AgentController.stop()
            job?.cancel()
        } else {
            s.update(id) { t ->
                val v = t.viber?.let { b -> b.copy(messages = b.messages.map { if (it.status == MessageStatus.PENDING) it.copy(status = MessageStatus.CANCELLED) else it }) }
                TaskRules.transition(t.copy(viber = v), TaskStatus.CANCELLED, System.currentTimeMillis(), "Cancelled by you")
            }
        }
        TaskScheduler.sync(context, s.tasks.value)
    }

    fun changed(context: Context) { init(context); TaskScheduler.sync(context, store!!.tasks.value); kick(context, "changed") }
}

/** One alarm for the earliest scheduled task, plus a light fallback check while something waits on the network. */
object TaskScheduler {
    private const val FALLBACK_MS = 15 * 60_000L

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun sync(context: Context, tasks: List<ScheduledTask>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 1, Intent(context, TaskAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = System.currentTimeMillis()
        val waitingOnNetwork = tasks.any { it.status == TaskStatus.WAITING_DEVICE && it.waitingReason == Blocker.NO_NETWORK.message }
        val at = listOfNotNull(TaskRules.nextAlarmAt(tasks), if (waitingOnNetwork) now + FALLBACK_MS else null).minOrNull()
        if (at == null) { am.cancel(pi); return }
        val time = at.coerceAtLeast(now + 1_000)
        runCatching {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi) }
    }
}

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { TaskRunner.kick(context, "alarm") }
}

/** Re-arms alarms after reboot, app update, or a clock/time-zone change. */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskRunner.init(context)
        TaskRunner.kick(context, intent.action ?: "boot")
    }
}

object TaskNotifier {
    private const val CHANNEL = "tasks"

    fun show(context: Context, task: ScheduledTask, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a scheduled task finishes, fails, or needs you"
        })
        val open = PendingIntent.getActivity(
            context, task.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TASK_ID, task.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chrauto)
            .setContentTitle(task.title.take(60))
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }
}
