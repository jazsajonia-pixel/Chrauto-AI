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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_REQUEST_MIC = "request_mic" }

    private lateinit var settings: SettingsStore
    private var accessibilityOn by mutableStateOf(false)
    private var micOn by mutableStateOf(false)
    private var chronOn by mutableStateOf(false)
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
                    onToggleFavorite = { settings.toggleFavorite(it) }
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        if (settings.floatingAssistant) AccessibilityBridge.setAssistantVisible(true)
        if (settings.chronEnabled && micOn) startChron()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true && !micOn) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun refreshPermissions() {
        micOn = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val me = ComponentName(this, AccessibilityAgentService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        accessibilityOn = AccessibilityBridge.isReady() || enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

const val DEFAULT_TASK = "Open Chrome and search for Kotlin Android development tutorials."
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
    onToggleFavorite: (String) -> Boolean = { false }
) {
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

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.VioletSoft, Brand.Canvas, Brand.Canvas)))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(state)

            SetupCard(
                config = config, onConfig = ::updateConfig,
                onProvider = { p -> val c = loadConfig(p); config = c; onConfigChanged(c) },
                accessibilityEnabled = accessibilityEnabled, onOpenAccessibility = onOpenAccessibility,
                micGranted = micGranted, onRequestMic = onRequestMic,
                floatingAssistant = floatingAssistant, onFloatingAssistantChanged = onFloatingAssistantChanged,
                voiceReplies = voiceReplies, onVoiceRepliesChanged = onVoiceRepliesChanged,
                canStoreKey = canStoreKey
            )

            SectionCard {
                Text("What should I do?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    task, { task = it }, Modifier.fillMaxWidth().testTag("taskInput"),
                    placeholder = { Text("e.g. Open Chrome and search for cute cat videos") },
                    minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp)
                )
                val chips = (favorites.map { "★ $it" } + recents.filterNot { r -> favorites.any { it.equals(r, true) } } + QUICK_TASKS)
                    .distinctBy { it.removePrefix("★ ").lowercase() }.take(14)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { q ->
                        SuggestionChip(onClick = { task = q.removePrefix("★ ") }, label = { Text(q.removeSuffix("."), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                            shape = RoundedCornerShape(50))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !running && task.isNotBlank() && config.isUsable,
                        modifier = Modifier.weight(1f).height(52.dp).testTag("runButton"),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
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
                        enabled = running, modifier = Modifier.height(52.dp).testTag("stopButton"), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Coral),
                        onClick = {
                            runJob?.cancel(); AgentController.stop()
                            state = state.copy(status = RunStatus.CANCELLED, phase = AgentPhase.DONE, logs = state.logs + AgentLog("Cancelled by user."))
                        }
                    ) { Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
                val isFav = favorites.any { it.equals(task.trim(), true) }
                if (task.isNotBlank()) TextButton(onClick = { onToggleFavorite(task); favTick++ }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("favoriteButton")) {
                    Text(if (isFav) "★ Saved to favorites" else "☆ Save to favorites", fontSize = 13.sp)
                }
                if (!config.isUsable) Text("Add your ${config.provider.label} API key above to start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!accessibilityEnabled) Text("Tip: turn on the Accessibility service so I can see and tap the screen.", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
            }

            ChronCard(chron, chronEnabled, onChronChanged, initialWakeName, onWakeNameChanged, onTalkNow)

            StatusCard(state, onApprove = {
                val c = config
                runJob = scope.launch { AgentController.runAndAwait(state.task.ifBlank { task }, c, { state = it }, approved = true) }
            })

            Text(
                "Your API key stays on this phone (encrypted) and is only sent to Google's Gemini API. It is never written to logs. Device-local keys can still be exposed on a compromised device.",
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
        Mascot(mood, 76.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Chrauto AI", style = MaterialTheme.typography.headlineMedium)
            Text("Your little phone helper. Tell me what to do and I'll tap through it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
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
                Text(if (done == 3) "All set" else "Setup", style = MaterialTheme.typography.titleMedium)
                Text("$done of 3 ready · ${config.provider.label} · ${config.model}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { done / 3f }, modifier = Modifier.width(64.dp).clip(CircleShape), color = Brand.Mint, trackColor = Brand.MintSoft)
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
                ToggleRow("Floating assistant", "Cute bubble with voice, stop, and retry buttons", floatingAssistant, onFloatingAssistantChanged)
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
    var wake by remember { mutableStateOf(initialWakeName) }
    val name = wake.ifBlank { "Chron" }
    val mood = when (chron.mode) {
        ChronEngine.Mode.ACTIVE -> Mood.LISTENING
        ChronEngine.Mode.THINKING -> Mood.THINKING
        ChronEngine.Mode.SPEAKING -> Mood.HAPPY
        else -> Mood.IDLE
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mascot(mood, 46.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Talk to $name", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (chron.mode) {
                        ChronEngine.Mode.PASSIVE -> "Listening for “$name”…"
                        ChronEngine.Mode.ACTIVE -> chron.partial.ifBlank { "I'm listening…" }
                        ChronEngine.Mode.THINKING -> "Thinking about “${chron.heard}”"
                        ChronEngine.Mode.SPEAKING -> "Talking…"
                        ChronEngine.Mode.OFF -> chron.error ?: if (enabled) "Starting…" else "Say “$name, open YouTube” or ask a question"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (chron.mode == ChronEngine.Mode.ACTIVE) Brand.Violet else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(onClick = onTalkNow, shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("talkButton")) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (chron.mode == ChronEngine.Mode.ACTIVE) "Stop" else "Talk")
            }
        }
        ToggleRow("Hands-free “$name”", "Listens for its name, even in other apps. Shows a notification while on.", enabled, onEnabled, Modifier.testTag("chronSwitch"))
        AnimatedVisibility(enabled) {
            OutlinedTextField(wake, { wake = it.take(16); onWakeName(wake) }, Modifier.fillMaxWidth(), label = { Text("Wake name") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), supportingText = { Text("Tip: add the “$name” tile in Quick Settings to switch it on and off fast.") })
        }
        if (chron.conversation.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chron.conversation.takeLast(4).forEach { (you, bot) ->
                    ChatBubble(you, mine = true)
                    ChatBubble(bot, mine = false)
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
                .background(if (mine) Brand.Violet else Brand.VioletSoft).padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(text, color = if (mine) Color.White else Brand.Ink, style = MaterialTheme.typography.bodyMedium) }
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
                    Text("▾", color = Brand.Muted, fontSize = 18.sp)
                }
            }
            DropdownMenu(pickerOpen, onDismissRequest = { pickerOpen = false }, modifier = Modifier.heightIn(max = 460.dp)) {
                ProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(kind.label, fontWeight = if (kind == p) FontWeight.Bold else FontWeight.Normal)
                                Text(kind.pricing, fontSize = 12.sp, color = if (isFree(kind.pricing)) Brand.Mint else Brand.Muted)
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
            Text(if (isFree(p.pricing)) "Get a free ${p.label} key →" else "Get a ${p.label} key →", fontSize = 13.sp)
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
                    }) { Text(if (loading) "…" else "Load", fontSize = 13.sp) }
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
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (free) Brand.MintSoft else Color(0xFFF0EEF5)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(if (free) text.substringBefore(" ·") else text.substringBefore(" ·"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (free) Brand.Mint else Brand.Muted, maxLines = 1)
    }
}

@Composable
private fun StepRow(n: Int, title: String, subtitle: String, done: Boolean, action: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) Brand.MintSoft else Brand.VioletSoft)
                .border(1.dp, if (done) Brand.Mint else Brand.Violet.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = Brand.Mint)
            else Text("$n", fontWeight = FontWeight.Bold, color = Brand.Violet, fontSize = 14.sp)
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
        RunStatus.IDLE -> Triple("Ready", Brand.Violet, Brand.VioletSoft)
        RunStatus.RUNNING -> Triple("Working", Brand.Violet, Brand.VioletSoft)
        RunStatus.SUCCEEDED -> Triple("Done", Brand.Mint, Brand.MintSoft)
        RunStatus.FAILED -> Triple("Stuck", Brand.Coral, Brand.CoralSoft)
        RunStatus.CANCELLED -> Triple("Stopped", Brand.Muted, Color(0xFFF0EEF5))
        RunStatus.NEEDS_ASSISTANCE -> Triple("Needs you", Brand.Amber, Brand.AmberSoft)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Status: ${state.status}", modifier = Modifier.weight(1f).testTag("status"), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        AnimatedVisibility(state.status == RunStatus.RUNNING) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (state.step > 0 && (state.phase == AgentPhase.MOVING || state.phase == AgentPhase.ACTING)) "Step ${state.step} · " else "") + state.detail.ifBlank { "Working…" },
                    style = MaterialTheme.typography.bodyMedium, color = Brand.Violet, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Brand.Violet, trackColor = Brand.VioletSoft)
            }
        }
        if (state.status == RunStatus.NEEDS_ASSISTANCE) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.AmberSoft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("I paused before doing something important: ${state.detail}", style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                Button(onClick = onApprove, modifier = Modifier.testTag("approveButton"), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Amber)) { Text("Approve & continue") }
            }
        }
        if (state.logs.isEmpty()) {
            Text("Activity will show up here. Tip: once the accessibility service is on, tap the floating buddy and use the mic to give commands from any app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.logs.takeLast(40).forEach { log ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (log.isError) Brand.Coral else Brand.Violet.copy(alpha = 0.5f)))
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
