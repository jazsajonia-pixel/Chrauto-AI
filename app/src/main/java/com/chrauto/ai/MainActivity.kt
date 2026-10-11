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
import com.chrauto.ai.provider.DEFAULT_MODEL
import com.chrauto.ai.provider.SUGGESTED_MODELS
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
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { micOn = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        AgentController.init(this)
        refreshPermissions()
        setContent {
            ChrautoTheme {
                var floating by remember { mutableStateOf(settings.floatingAssistant) }
                var voiceReplies by remember { mutableStateOf(settings.voiceReplies) }
                ChrautoScreen(
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    initialApiKey = settings.apiKey,
                    initialModel = settings.model,
                    initialTask = settings.lastTask.ifBlank { DEFAULT_TASK },
                    onSettingsChanged = { key, model -> settings.apiKey = key; settings.model = model },
                    accessibilityEnabled = accessibilityOn,
                    micGranted = micOn,
                    onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    floatingAssistant = floating,
                    onFloatingAssistantChanged = { floating = it; settings.floatingAssistant = it; AccessibilityBridge.setAssistantVisible(it) },
                    voiceReplies = voiceReplies,
                    onVoiceRepliesChanged = { voiceReplies = it; settings.voiceReplies = it },
                    canStoreKey = settings.canStoreKey
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
    runTask: RunTask = { task, apiKey, model, onState -> AgentController.runAndAwait(task, apiKey.trim(), model.trim(), onState) },
    initialApiKey: String = "",
    initialModel: String = DEFAULT_MODEL,
    initialTask: String = DEFAULT_TASK,
    onSettingsChanged: (apiKey: String, model: String) -> Unit = { _, _ -> },
    accessibilityEnabled: Boolean = false,
    micGranted: Boolean = false,
    onRequestMic: () -> Unit = {},
    floatingAssistant: Boolean = true,
    onFloatingAssistantChanged: (Boolean) -> Unit = {},
    voiceReplies: Boolean = true,
    onVoiceRepliesChanged: (Boolean) -> Unit = {},
    canStoreKey: Boolean = true,
    observeController: Boolean = true
) {
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf(initialTask) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var model by remember { mutableStateOf(initialModel) }
    var state by remember { mutableStateOf(AgentState()) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val running = state.status == RunStatus.RUNNING

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
                apiKey = apiKey, onApiKey = { apiKey = it; onSettingsChanged(it, model) },
                model = model, onModel = { model = it; onSettingsChanged(apiKey, it) },
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_TASKS.forEach { q ->
                        SuggestionChip(onClick = { task = q }, label = { Text(q.removeSuffix("."), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                            shape = RoundedCornerShape(50))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !running && task.isNotBlank() && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f).height(52.dp).testTag("runButton"),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { runJob = scope.launch { runTask(task, apiKey, model) { state = it } } }
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
                if (apiKey.isBlank()) Text("Add your Gemini API key above to start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!accessibilityEnabled) Text("Tip: turn on the Accessibility service so I can see and tap the screen.", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
            }

            StatusCard(state)

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
    apiKey: String, onApiKey: (String) -> Unit, model: String, onModel: (String) -> Unit,
    accessibilityEnabled: Boolean, onOpenAccessibility: () -> Unit,
    micGranted: Boolean, onRequestMic: () -> Unit,
    floatingAssistant: Boolean, onFloatingAssistantChanged: (Boolean) -> Unit,
    voiceReplies: Boolean, onVoiceRepliesChanged: (Boolean) -> Unit,
    canStoreKey: Boolean
) {
    val done = listOf(apiKey.isNotBlank(), accessibilityEnabled, micGranted).count { it }
    var expanded by rememberSaveable { mutableStateOf(done < 3) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    var showKey by remember { mutableStateOf(false) }

    SectionCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (done == 3) "All set" else "Setup", style = MaterialTheme.typography.titleMedium)
                Text("$done of 3 ready · ${model.ifBlank { DEFAULT_MODEL }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { done / 3f }, modifier = Modifier.width(64.dp).clip(CircleShape), color = Brand.Mint, trackColor = Brand.MintSoft)
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_settings), "Toggle setup", Modifier.size(22.dp).rotate(arrow), tint = Brand.Muted)
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepRow(1, "Gemini API key", if (apiKey.isBlank()) "Paste a key from Google AI Studio" else if (canStoreKey) "Saved securely on this phone" else "Secure storage unavailable; key kept for this session", apiKey.isNotBlank())
                OutlinedTextField(
                    apiKey, onApiKey, Modifier.fillMaxWidth().testTag("apiKeyInput"),
                    label = { Text("Gemini API key") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(painterResource(if (showKey) R.drawable.ic_eye_off else R.drawable.ic_eye), if (showKey) "Hide key" else "Show key", Modifier.size(20.dp))
                        }
                    }
                )
                OutlinedTextField(model, onModel, Modifier.fillMaxWidth().testTag("modelInput"), label = { Text("Gemini model") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUGGESTED_MODELS.forEach { m ->
                        FilterChip(selected = model.trim() == m, onClick = { onModel(m) }, label = { Text(m, fontSize = 12.sp) }, shape = RoundedCornerShape(50))
                    }
                }
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
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(state: AgentState) {
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
