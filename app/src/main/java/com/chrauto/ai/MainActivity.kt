package com.chrauto.ai

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chrauto.ai.accessibility.AccessibilityAgentService
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.agent.*
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.overlay.Mood
import com.chrauto.ai.voice.ChronEngine
import com.chrauto.ai.voice.ChronService
import android.os.Build
import com.chrauto.ai.provider.AiConfig
import com.chrauto.ai.provider.ModelLister
import com.chrauto.ai.provider.ProviderKind
import androidx.compose.ui.platform.LocalUriHandler
import com.chrauto.ai.ui.Brand
import com.chrauto.ai.ui.ChrautoTheme
import com.chrauto.ai.ui.Mascot
import com.chrauto.ai.ui.TaskActions
import com.chrauto.ai.ui.TaskDetailScreen
import com.chrauto.ai.ui.TaskEditorScreen
import com.chrauto.ai.ui.TasksSection
import com.chrauto.ai.ui.ViberComposerScreen
import com.chrauto.ai.tasks.ScheduledTask
import com.chrauto.ai.tasks.TaskKind
import com.chrauto.ai.tasks.TaskRules
import com.chrauto.ai.tasks.TaskRunner
import com.chrauto.ai.tasks.TaskScheduler
import com.chrauto.ai.tasks.TaskStore
import com.chrauto.ai.viber.ViberShare
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_REQUEST_MIC = "request_mic"; const val EXTRA_TASK_ID = "task_id" }

    private lateinit var settings: SettingsStore
    private var accessibilityOn by mutableStateOf(false)
    private var micOn by mutableStateOf(false)
    private var chronOn by mutableStateOf(false)
    private var exactAlarms by mutableStateOf(true)
    private var openTaskId by mutableStateOf<String?>(null)
    private var startChronAfterMic = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micOn = granted
        if (granted && (startChronAfterMic || settings.chronEnabled)) { startChronAfterMic = false; startChron() }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun setChron(on: Boolean) {
        settings.chronEnabled = on
        chronOn = on
        if (!on) { ChronService.stop(this); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!micOn) { startChronAfterMic = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) } else startChron()
    }

    private fun startChron() { if (!ChronService.running) ChronService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        AgentController.init(this)
        ChronEngine.attach(this)
        chronOn = settings.chronEnabled
        refreshPermissions()
        setContent {
            ChrautoTheme {
                var floating by remember { mutableStateOf(settings.floatingAssistant) }
                var voiceReplies by remember { mutableStateOf(settings.voiceReplies) }
                ChrautoScreen(
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    initialConfig = settings.config(),
                    initialTask = settings.lastTask.ifBlank { DEFAULT_TASK },
                    onConfigChanged = { settings.save(it) },
                    loadConfig = { settings.config(it) },
                    accessibilityEnabled = accessibilityOn,
                    micGranted = micOn,
                    onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    floatingAssistant = floating,
                    onFloatingAssistantChanged = { floating = it; settings.floatingAssistant = it; AccessibilityBridge.setAssistantVisible(it) },
                    voiceReplies = voiceReplies,
                    onVoiceRepliesChanged = { voiceReplies = it; settings.voiceReplies = it },
                    canStoreKey = settings.canStoreKey,
                    chronEnabled = chronOn,
                    onChronChanged = ::setChron,
                    initialWakeName = settings.wakeName,
                    onWakeNameChanged = { settings.wakeName = it.ifBlank { "Chron" } },
                    onTalkNow = { if (!micOn) micPermission.launch(Manifest.permission.RECORD_AUDIO) else ChronEngine.listenNow(this) },
                    loadRecents = { settings.recents },
                    loadFavorites = { settings.favorites },
                    onToggleFavorite = { settings.toggleFavorite(it) },
                    taskStore = TaskRunner.store(this),
                    taskActions = taskActions(),
                    openTaskId = openTaskId,
                    onOpenTaskConsumed = { openTaskId = null }
                )
            }
        }
        handleIntent(intent)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun taskActions(): TaskActions {
        val store = TaskRunner.store(this)
        return TaskActions(
            save = { t, base ->
                store.saveEdited(t, base).also { err ->
                    if (err == null) { if (t.status == com.chrauto.ai.tasks.TaskStatus.SCHEDULED) askNotifications(); TaskRunner.changed(this) }
                }
            },
            delete = { id -> if (TaskRunner.activeId != id) { store.delete(id); TaskRunner.changed(this); cleanupImages(store) } },
            runNow = { id, done -> TaskRunner.runNow(this, id, done) },
            cancel = { id -> TaskRunner.cancel(this, id) },
            retry = { id ->
                val t = store.get(id)
                val next = t?.let { TaskRules.retry(it, System.currentTimeMillis()) }
                if (next == null) "Nothing can be retried safely. Check Unconfirmed messages in Viber first."
                else { store.update(id) { cur -> if (cur.updatedAt == t.updatedAt) next else cur }; TaskRunner.changed(this); null }
            },
            markMessage = { id, msg, sent -> store.update(id) { TaskRules.markMessage(it, msg, sent, System.currentTimeMillis()) }; TaskRunner.changed(this) },
            importImages = { uris ->
                withContext(Dispatchers.IO) {
                    val results = uris.map { ViberShare.importImage(this@MainActivity, it) }
                    results.mapNotNull { it.getOrNull() } to results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                }
            },
            exactAlarms = exactAlarms,
            requestExactAlarms = {
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
                }
            },
            viberInstalled = ViberShare.isInstalled(this),
            activeId = { TaskRunner.activeId }
        )
    }

    private fun cleanupImages(store: TaskStore) {
        val keep = store.tasks.value.flatMap { t -> t.viber?.messages.orEmpty().flatMap { it.images } }.toSet()
        ViberShare.cleanup(this, keep)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        exactAlarms = TaskScheduler.canExact(this)
        TaskRunner.kick(this, "app opened")
        if (settings.floatingAssistant) AccessibilityBridge.setAssistantVisible(true)
        if (settings.chronEnabled && micOn) startChron()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true && !micOn) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        intent?.getStringExtra(EXTRA_TASK_ID)?.let { openTaskId = it }
    }

    private fun refreshPermissions() {
        micOn = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val me = ComponentName(this, AccessibilityAgentService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        accessibilityOn = AccessibilityBridge.isReady() || enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

const val DEFAULT_TASK = "Open Chrome and search for Kotlin Android development tutorials."
private const val FAV = "Favorite: "
private val QUICK_TASKS = listOf(
    "Open Chrome and search for Kotlin Android development tutorials.",
    "Open YouTube and search for lo-fi study music.",
    "Open Settings and show the Wi-Fi page.",
    "Open the Play Store and search for Notion."
)

typealias RunTask = suspend (task: String, apiKey: String, model: String, onState: (AgentState) -> Unit) -> Unit

@Composable
fun ChrautoScreen(
    onOpenAccessibility: () -> Unit,
    /** Test hook; when null the shared [AgentController] runs the task with the selected provider. */
    runTask: RunTask? = null,
    initialConfig: AiConfig = AiConfig(),
    initialTask: String = DEFAULT_TASK,
    onConfigChanged: (AiConfig) -> Unit = {},
    loadConfig: (ProviderKind) -> AiConfig = { AiConfig(it) },
    accessibilityEnabled: Boolean = false,
    micGranted: Boolean = false,
    onRequestMic: () -> Unit = {},
    floatingAssistant: Boolean = true,
    onFloatingAssistantChanged: (Boolean) -> Unit = {},
    voiceReplies: Boolean = true,
    onVoiceRepliesChanged: (Boolean) -> Unit = {},
    canStoreKey: Boolean = true,
    observeController: Boolean = true,
    chronEnabled: Boolean = false,
    onChronChanged: (Boolean) -> Unit = {},
    initialWakeName: String = "Chron",
    onWakeNameChanged: (String) -> Unit = {},
    onTalkNow: () -> Unit = {},
    loadRecents: () -> List<String> = { emptyList() },
    loadFavorites: () -> List<String> = { emptyList() },
    onToggleFavorite: (String) -> Boolean = { false },
    taskStore: TaskStore? = null,
    taskActions: TaskActions = TaskActions(),
    openTaskId: String? = null,
    onOpenTaskConsumed: () -> Unit = {}
) {
    // Simple in-app navigation: "home", "task:<id>", "edit:<id>", "new", "viber", "viber:<id>".
    var screen by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(openTaskId) { if (openTaskId != null) { screen = "task:$openTaskId"; onOpenTaskConsumed() } }
    BackHandler(enabled = screen != "home") { screen = "home" }
    val tasksFlow = taskStore?.tasks ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<ScheduledTask>()) }
    val tasks by tasksFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf(initialTask) }
    var config by remember { mutableStateOf(initialConfig) }
    fun updateConfig(c: AiConfig) { config = c; onConfigChanged(c) }
    var state by remember { mutableStateOf(AgentState()) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val running = state.status == RunStatus.RUNNING
    var favTick by remember { mutableIntStateOf(0) }
    val recents = remember(state.status, favTick) { loadRecents() }
    val favorites = remember(state.status, favTick) { loadFavorites() }
    val chron by (if (observeController) ChronEngine.state else remember { kotlinx.coroutines.flow.MutableStateFlow(ChronEngine.State()) }).collectAsState()

    // Mirror runs started from the floating assistant (voice / retry).
    if (observeController) {
        LaunchedEffect(Unit) {
            AgentController.state.collect { s -> if (s.status != RunStatus.IDLE && runJob?.isActive != true) state = s }
        }
    }

    when {
        screen.startsWith("task:") -> { val id = screen.removePrefix("task:")
            TaskDetailScreen(taskStore, id, taskActions, onBack = { screen = "home" },
                onEdit = { eid -> screen = if (taskStore?.get(eid)?.kind == TaskKind.VIBER_BATCH) "viber:$eid" else "edit:$eid" }); return }
        screen == "new" || screen.startsWith("edit:") -> {
            TaskEditorScreen(taskStore, screen.removePrefix("edit:").takeIf { screen.startsWith("edit:") }, task, taskActions, onBack = { screen = "home" }); return }
        screen == "viber" || screen.startsWith("viber:") -> {
            ViberComposerScreen(taskStore, screen.removePrefix("viber:").takeIf { screen.startsWith("viber:") }, taskActions, onBack = { screen = "home" }); return }
    }

    Box(
        Modifier.fillMaxSize().background(Brand.Canvas)
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(state)
            val setupDone = config.isUsable && accessibilityEnabled
            StatusStrip(accessibilityEnabled, config.isUsable, taskActions.exactAlarms, onFix = { if (!accessibilityEnabled) onOpenAccessibility() else if (!taskActions.exactAlarms) taskActions.requestExactAlarms() })

            if (!setupDone) SetupCard(
                config = config, onConfig = ::updateConfig,
                onProvider = { p -> val c = loadConfig(p); config = c; onConfigChanged(c) },
                accessibilityEnabled = accessibilityEnabled, onOpenAccessibility = onOpenAccessibility,
                micGranted = micGranted, onRequestMic = onRequestMic,
                floatingAssistant = floatingAssistant, onFloatingAssistantChanged = onFloatingAssistantChanged,
                voiceReplies = voiceReplies, onVoiceRepliesChanged = onVoiceRepliesChanged,
                canStoreKey = canStoreKey
            )

            SectionCard {
                Text("New task", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    task, { task = it }, Modifier.fillMaxWidth().testTag("taskInput"),
                    placeholder = { Text("e.g. Open Chrome and search for today's weather") },
                    minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp)
                )
                val chips = (favorites.map { "$FAV$it" } + recents.filterNot { r -> favorites.any { it.equals(r, true) } } + QUICK_TASKS)
                    .distinctBy { it.removePrefix(FAV).lowercase() }.take(14)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { q ->
                        SuggestionChip(onClick = { task = q.removePrefix(FAV) }, label = { Text(q.removeSuffix("."), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                            shape = RoundedCornerShape(50))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !running && task.isNotBlank() && config.isUsable,
                        modifier = Modifier.weight(1f).height(52.dp).testTag("runButton"),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            if (runJob?.isActive == true) return@Button
                            val c = config
                            runJob = scope.launch {
                                val custom = runTask
                                if (custom != null) custom(task, c.apiKey, c.model) { state = it }
                                else AgentController.runAndAwait(task, c, { state = it })
                            }
                        }
                    ) {
                        Icon(painterResource(R.drawable.ic_play), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Run task")
                    }
                    OutlinedButton(
                        enabled = running, modifier = Modifier.height(52.dp).testTag("stopButton"), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Danger),
                        onClick = {
                            runJob?.cancel(); AgentController.stop()
                            state = state.copy(status = RunStatus.CANCELLED, phase = AgentPhase.DONE, logs = state.logs + AgentLog("Cancelled by user."))
                        }
                    ) { Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
                val isFav = favorites.any { it.equals(task.trim(), true) }
                if (task.isNotBlank()) TextButton(onClick = { onToggleFavorite(task); favTick++ }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("favoriteButton")) {
                    Text(if (isFav) "Saved to favorites" else "Save to favorites", fontSize = 13.sp)
                }
                if (!config.isUsable) Text("Add your ${config.provider.label} API key above to start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!accessibilityEnabled) Text("Turn on the accessibility service so Chrauto can read and tap the screen.", style = MaterialTheme.typography.bodySmall, color = Brand.Warn)
            }

            TasksSection(tasks, onOpen = { screen = "task:$it" }, onNewTask = { screen = "new" }, onNewViber = { screen = "viber" })

            ChronCard(chron, chronEnabled, onChronChanged, initialWakeName, onWakeNameChanged, onTalkNow)

            if (setupDone) SetupCard(
                config = config, onConfig = ::updateConfig,
                onProvider = { p -> val c = loadConfig(p); config = c; onConfigChanged(c) },
                accessibilityEnabled = accessibilityEnabled, onOpenAccessibility = onOpenAccessibility,
                micGranted = micGranted, onRequestMic = onRequestMic,
                floatingAssistant = floatingAssistant, onFloatingAssistantChanged = onFloatingAssistantChanged,
                voiceReplies = voiceReplies, onVoiceRepliesChanged = onVoiceRepliesChanged,
                canStoreKey = canStoreKey
            )

            StatusCard(state, onApprove = {
                val c = config
                if (runJob?.isActive != true) runJob = scope.launch { AgentController.runAndAwait(state.task.ifBlank { task }, c, { state = it }, approved = true) }
            })

            Text(
                "Your API key stays on this phone (encrypted) and is only sent to the AI provider you picked, together with a text description of the screen. It is never written to logs. Device-local keys can still be exposed on a compromised device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun Header(state: AgentState) {
    val mood = when (state.status) {
        RunStatus.RUNNING -> if (state.phase == AgentPhase.ACTING || state.phase == AgentPhase.MOVING) Mood.WORKING else Mood.THINKING
        RunStatus.SUCCEEDED -> Mood.HAPPY
        RunStatus.FAILED, RunStatus.NEEDS_ASSISTANCE -> Mood.SAD
        else -> Mood.IDLE
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Mascot(mood, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Chrauto", style = MaterialTheme.typography.headlineSmall)
            Text("Phone automation agent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One-line readiness summary: what the agent can do right now. */
@Composable
private fun StatusStrip(accessibility: Boolean, aiReady: Boolean, exactAlarms: Boolean, onFix: () -> Unit) {
    @Composable fun chip(label: String, ok: Boolean) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (ok) Brand.SuccessSoft else Brand.WarnSoft).clickable(onClick = onFix)
                .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Brand.Success else Brand.Warn))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (ok) Brand.Success else Brand.Warn, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("statusStrip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chip(if (accessibility) "Accessibility on" else "Accessibility off", accessibility)
        chip(if (aiReady) "AI provider ready" else "AI key missing", aiReady)
        chip(if (exactAlarms) "Exact scheduling" else "Approximate scheduling", exactAlarms)
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun SetupCard(
    config: AiConfig, onConfig: (AiConfig) -> Unit, onProvider: (ProviderKind) -> Unit,
    accessibilityEnabled: Boolean, onOpenAccessibility: () -> Unit,
    micGranted: Boolean, onRequestMic: () -> Unit,
    floatingAssistant: Boolean, onFloatingAssistantChanged: (Boolean) -> Unit,
    voiceReplies: Boolean, onVoiceRepliesChanged: (Boolean) -> Unit,
    canStoreKey: Boolean
) {
    val done = listOf(config.isUsable, accessibilityEnabled, micGranted).count { it }
    var expanded by rememberSaveable { mutableStateOf(done < 3) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")

    SectionCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (done == 3) "Settings and permissions" else "Setup", style = MaterialTheme.typography.titleMedium)
                Text("$done of 3 ready · ${config.provider.label} · ${config.model}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { done / 3f }, modifier = Modifier.width(64.dp).clip(CircleShape), color = Brand.Success, trackColor = Brand.SuccessSoft)
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_settings), "Toggle setup", Modifier.size(22.dp).rotate(arrow), tint = Brand.Muted)
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepRow(1, "AI brain", if (!config.isUsable) "Pick a provider and paste its API key" else if (canStoreKey) "Key saved securely on this phone" else "Secure storage unavailable; key kept for this session", config.isUsable)
                ProviderSection(config, onConfig, onProvider)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StepRow(2, "Accessibility service", if (accessibilityEnabled) "On — I can read and tap the screen" else "Enable \"Chrauto AI Accessibility\" in Settings", accessibilityEnabled) {
                    FilledTonalButton(onClick = onOpenAccessibility, modifier = Modifier.testTag("accessibilityButton"), shape = RoundedCornerShape(12.dp)) {
                        Text(if (accessibilityEnabled) "Manage" else "Turn on", fontSize = 13.sp)
                    }
                }
                StepRow(3, "Microphone", if (micGranted) "Voice commands ready" else "Needed for voice commands", micGranted) {
                    if (!micGranted) FilledTonalButton(onClick = onRequestMic, shape = RoundedCornerShape(12.dp)) { Text("Allow", fontSize = 13.sp) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Floating assistant", "Small on-screen control with voice, stop and repeat", floatingAssistant, onFloatingAssistantChanged)
                ToggleRow("Voice replies", "The assistant talks back when it starts or finishes", voiceReplies, onVoiceRepliesChanged)
            }
        }
    }
}

@Composable
private fun ChronCard(
    chron: ChronEngine.State, enabled: Boolean, onEnabled: (Boolean) -> Unit,
    initialWakeName: String, onWakeName: (String) -> Unit, onTalkNow: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SettingsStore(context) }
    var wake by remember { mutableStateOf(initialWakeName) }
    var live by remember { mutableStateOf(store.liveConversation) }
    var barge by remember { mutableStateOf(store.bargeIn) }
    var rate by remember { mutableFloatStateOf(store.speechRate) }
    var language by remember { mutableStateOf(store.voiceLanguage) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    val name = wake.ifBlank { "Chron" }
    val mood = when (chron.mode) {
        ChronEngine.Mode.ACTIVE -> Mood.LISTENING
        ChronEngine.Mode.THINKING -> Mood.THINKING
        ChronEngine.Mode.SPEAKING -> Mood.HAPPY
        else -> Mood.IDLE
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mascot(mood, 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Talk to $name", style = MaterialTheme.typography.titleMedium)
                    if (chron.live) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Brand.DangerSoft).padding(horizontal = 8.dp, vertical = 2.dp).testTag("liveBadge")) {
                            Text("LIVE", color = Brand.Danger, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    when (chron.mode) {
                        ChronEngine.Mode.PASSIVE -> "Listening for “$name”…"
                        ChronEngine.Mode.ACTIVE -> chron.partial.ifBlank { if (chron.live) "Go ahead, I'm listening…" else "I'm listening…" }
                        ChronEngine.Mode.THINKING -> chron.reply.ifBlank { "Thinking about “${chron.heard}”" }
                        ChronEngine.Mode.SPEAKING -> if (chron.duplex) "Talking… just speak to interrupt" else "Talking…"
                        ChronEngine.Mode.OFF -> chron.error ?: if (enabled) "Starting…" else "Say “$name, open YouTube” or ask a question"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (chron.mode == ChronEngine.Mode.ACTIVE) Brand.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(onClick = onTalkNow, shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("talkButton")) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(when (chron.mode) { ChronEngine.Mode.ACTIVE -> "Stop"; ChronEngine.Mode.SPEAKING -> "Interrupt"; else -> "Talk" })
            }
        }
        ToggleRow("Hands-free “$name”", "Listens for its name, even in other apps. Shows a notification while on.", enabled, onEnabled, Modifier.testTag("chronSwitch"))
        AnimatedVisibility(enabled) {
            OutlinedTextField(wake, { wake = it.take(16); onWakeName(wake) }, Modifier.fillMaxWidth(), label = { Text("Wake name") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), supportingText = { Text("Tip: add the “$name” tile in Quick Settings, or hold the floating bubble to talk.") })
        }
        ToggleRow("Live conversation", "Keeps listening after each reply, so you can chat back and forth without saying “$name”.", live,
            { live = it; store.liveConversation = it }, Modifier.testTag("liveSwitch"))
        ToggleRow("Interrupt anytime", "Talk over $name and it stops to listen. Works best with earphones.", barge,
            { barge = it; store.bargeIn = it }, Modifier.testTag("bargeSwitch"))
        TextButton(onClick = { showVoice = !showVoice }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (showVoice) "Hide voice settings" else "Voice settings (speed, language)", fontSize = 13.sp)
        }
        AnimatedVisibility(showVoice) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Speaking speed", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Relaxed" to 0.9f, "Normal" to 1.1f, "Quick" to 1.35f, "Rapid" to 1.6f).forEach { (label, v) ->
                        FilterChip(selected = kotlin.math.abs(rate - v) < 0.08f, onClick = {
                            rate = v; store.speechRate = v; ChronEngine.applyVoiceSettings()
                        }, label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(50))
                    }
                }
                Text("Voice language", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Phone default" to "", "English (US)" to "en-US", "English (PH)" to "en-PH", "Filipino" to "fil-PH", "English (UK)" to "en-GB").forEach { (label, tag) ->
                        FilterChip(selected = language == tag, onClick = {
                            language = tag; store.voiceLanguage = tag; ChronEngine.applyVoiceSettings()
                        }, label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(50))
                    }
                }
            }
        }
        if (chron.conversation.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chron.conversation.takeLast(4).forEach { (you, bot) ->
                    if (you.isNotBlank()) ChatBubble(you, mine = true)
                    ChatBubble(bot, mine = false)
                }
                TextButton(onClick = { ChronEngine.clearConversation() }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("clearChat")) {
                    Text("Clear chat", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp))
                .background(if (mine) Brand.Accent else Brand.AccentSoft).padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(text, color = if (mine) MaterialTheme.colorScheme.onPrimary else Brand.Ink, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ProviderSection(config: AiConfig, onConfig: (AiConfig) -> Unit, onProvider: (ProviderKind) -> Unit) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember(config.provider) { mutableStateOf<List<String>>(emptyList()) }
    var modelsOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var modelError by remember(config.provider) { mutableStateOf<String?>(null) }
    val p = config.provider

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            OutlinedCard(
                onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth().testTag("providerPicker"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Provider", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.label, style = MaterialTheme.typography.titleSmall)
                    }
                    PriceBadge(p.pricing)
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(18.dp), tint = Brand.Muted)
                }
            }
            DropdownMenu(pickerOpen, onDismissRequest = { pickerOpen = false }, modifier = Modifier.heightIn(max = 460.dp)) {
                ProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(kind.label, fontWeight = if (kind == p) FontWeight.Bold else FontWeight.Normal)
                                Text(kind.pricing, fontSize = 12.sp, color = if (isFree(kind.pricing)) Brand.Success else Brand.Muted)
                            }
                        },
                        onClick = { pickerOpen = false; if (kind != p) onProvider(kind) }
                    )
                }
            }
        }

        if (p == ProviderKind.CUSTOM) {
            OutlinedTextField(config.baseUrl, { onConfig(config.copy(baseUrl = it)) }, Modifier.fillMaxWidth().testTag("baseUrlInput"),
                label = { Text("Base URL (OpenAI-compatible, ends in /v1)") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        OutlinedTextField(
            config.apiKey, { onConfig(config.copy(apiKey = it)) }, Modifier.fillMaxWidth().testTag("apiKeyInput"),
            label = { Text(if (p.requiresKey) "${p.label} API key" else "API key (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(painterResource(if (showKey) R.drawable.ic_eye_off else R.drawable.ic_eye), if (showKey) "Hide key" else "Show key", Modifier.size(20.dp))
                }
            }
        )
        TextButton(onClick = { runCatching { uri.openUri(p.keyUrl) } }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (isFree(p.pricing)) "Get a free ${p.label} key" else "Get a ${p.label} key", fontSize = 13.sp)
        }

        Box {
            OutlinedTextField(
                config.model, { onConfig(config.copy(model = it)) }, Modifier.fillMaxWidth().testTag("modelInput"),
                label = { Text("Model") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    TextButton(enabled = !loading && (config.apiKey.isNotBlank() || !p.requiresKey), onClick = {
                        loading = true; modelError = null
                        scope.launch {
                            runCatching { ModelLister.list(config) }
                                .onSuccess { models = it; modelsOpen = it.isNotEmpty(); if (it.isEmpty()) modelError = "No chat models returned" }
                                .onFailure { modelError = it.message ?: "Couldn't load models" }
                            loading = false
                        }
                    }) { Text(if (loading) "Loading" else "Load", fontSize = 13.sp) }
                }
            )
            DropdownMenu(modelsOpen, onDismissRequest = { modelsOpen = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                models.take(200).forEach { m ->
                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                            if (m.endsWith(":free")) { Spacer(Modifier.width(6.dp)); PriceBadge("Free") }
                        }
                    }, onClick = { modelsOpen = false; onConfig(config.copy(model = m)) })
                }
            }
        }
        modelError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.suggestedModels.forEach { m ->
                FilterChip(selected = config.model.trim() == m, onClick = { onConfig(config.copy(model = m)) }, label = { Text(m, fontSize = 12.sp) }, shape = RoundedCornerShape(50))
            }
        }
    }
}

private fun isFree(pricing: String) = pricing.contains("free", ignoreCase = true)

@Composable
private fun PriceBadge(text: String) {
    val free = isFree(text)
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (free) Brand.SuccessSoft else Brand.Chip).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(if (free) text.substringBefore(" ·") else text.substringBefore(" ·"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (free) Brand.Success else Brand.Muted, maxLines = 1)
    }
}

@Composable
private fun StepRow(n: Int, title: String, subtitle: String, done: Boolean, action: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) Brand.SuccessSoft else Brand.AccentSoft)
                .border(1.dp, if (done) Brand.Success else Brand.Accent.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = Brand.Success)
            else Text("$n", fontWeight = FontWeight.Bold, color = Brand.Accent, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(state: AgentState, onApprove: () -> Unit = {}) {
    val (label, fg, bg) = when (state.status) {
        RunStatus.IDLE -> Triple("Ready", Brand.Muted, Brand.Chip)
        RunStatus.RUNNING -> Triple("Running", Brand.Accent, Brand.AccentSoft)
        RunStatus.SUCCEEDED -> Triple("Completed", Brand.Success, Brand.SuccessSoft)
        RunStatus.FAILED -> Triple("Failed", Brand.Danger, Brand.DangerSoft)
        RunStatus.CANCELLED -> Triple("Cancelled", Brand.Muted, Brand.Chip)
        RunStatus.NEEDS_ASSISTANCE -> Triple("Waiting for user", Brand.Warn, Brand.WarnSoft)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Current run", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text("Status: $label", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.testTag("status"))
            }
        }
        AnimatedVisibility(state.status == RunStatus.RUNNING) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (state.step > 0 && (state.phase == AgentPhase.MOVING || state.phase == AgentPhase.ACTING)) "Step ${state.step} · " else "") + state.detail.ifBlank { "Working…" },
                    style = MaterialTheme.typography.bodyMedium, color = Brand.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Accent, trackColor = Brand.AccentSoft)
            }
        }
        if (state.status == RunStatus.NEEDS_ASSISTANCE) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.WarnSoft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paused before an important step: ${state.detail}", style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                Button(onClick = onApprove, modifier = Modifier.testTag("approveButton"), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Warn)) { Text("Approve & continue") }
            }
        }
        if (state.logs.isEmpty()) {
            Text("No run yet. Each step Chrauto observes, plans and verifies is listed here. With the accessibility service on, you can also start tasks from the floating control in any app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.logs.takeLast(40).forEach { log ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (log.isError) Brand.Danger else Brand.Accent.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (log.isError) "Error: ${log.message}" else log.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
