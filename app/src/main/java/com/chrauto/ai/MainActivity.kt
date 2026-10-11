package com.chrauto.ai

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.agent.*
import com.chrauto.ai.provider.GeminiProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChrautoScreen(onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) }
    }
}

typealias RunTask = suspend (task: String, apiKey: String, model: String, onState: (AgentState) -> Unit) -> Unit

@Composable
fun ChrautoScreen(
    onOpenAccessibility: () -> Unit,
    runTask: RunTask = { task, apiKey, model, onState ->
        AgentOrchestrator(GeminiProvider(apiKey.trim(), model.trim()), AccessibilityBridge, onState).run(task)
    }
) {
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf("Open Chrome and search for Kotlin Android development tutorials.") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("gemini-3.8-flash") }
    var state by remember { mutableStateOf(AgentState()) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val running = state.status == RunStatus.RUNNING
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chrauto AI", style = MaterialTheme.typography.headlineMedium)
            Text("Real Android UI agent. The model sees only the current accessibility tree and every action is validated locally.")
            OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth().testTag("taskInput"), label = { Text("Natural-language task") }, minLines = 3)
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth().testTag("apiKeyInput"), label = { Text("Gemini API key") }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().testTag("modelInput"), label = { Text("Gemini model") }, singleLine = true)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup required", style = MaterialTheme.typography.titleMedium)
                    Text("Enable Chrauto AI under Android Settings → Accessibility. This permits reading visible accessibility nodes and dispatching supported actions; it does not bypass protected fields or system prompts.")
                    Button(onClick = onOpenAccessibility, modifier = Modifier.testTag("accessibilityButton")) { Text("Open Accessibility Settings") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running && task.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.testTag("runButton"), onClick = {
                    runJob = scope.launch { runTask(task, apiKey, model) { state = it } }
                }) { Text("Run") }
                OutlinedButton(enabled = running, modifier = Modifier.testTag("stopButton"), onClick = { runJob?.cancel(); state = state.copy(status = RunStatus.CANCELLED, logs = state.logs + AgentLog("Cancelled by user.")) }) { Text("Stop") }
            }
            Text("Status: ${state.status}", modifier = Modifier.testTag("status"), style = MaterialTheme.typography.titleMedium)
            state.logs.forEach { log -> Text(if (log.isError) "Error: ${log.message}" else log.message, color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            Text("API keys are used only for direct Gemini requests from this app and are not written to logs. Device-local keys can still be exposed on a compromised device.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
