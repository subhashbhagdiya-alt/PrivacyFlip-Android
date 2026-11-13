package com.privacyflip.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun RulesPage(presets: List<Preset>, store: LocalStore, language: String) {
    val context = LocalContext.current
    var customRules by remember { mutableStateOf(store.getCustomRules()) }
    var ruleName by remember { mutableStateOf("") }
    var scheduledTime by remember { mutableStateOf("09:00") }
    var selectedTrigger by remember { mutableStateOf(customTriggers.first()) }
    var selectedAction by remember { mutableStateOf(supportedCustomActions.first()) }
    var triggerMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var showBuilder by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(if (language == "hi") "Custom IF–THEN rules" else "Custom IF–THEN rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Create rules offline. Triggers run in the background; actions use normal Android APIs only, so a few need a one-time permission from Settings.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showBuilder = !showBuilder }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(if (showBuilder) "Close rule builder" else "Create custom rule")
                    }
                }
            }
        }
        if (showBuilder) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("IF … THEN …", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = ruleName, onValueChange = { ruleName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rule name") }, singleLine = true)
                        Box {
                            OutlinedButton(onClick = { triggerMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("IF: $selectedTrigger") }
                            DropdownMenu(expanded = triggerMenu, onDismissRequest = { triggerMenu = false }) {
                                customTriggers.forEach { item ->
                                    DropdownMenuItem(
                                        enabled = item in supportedCustomTriggers,
                                        text = { Text(if (item in supportedCustomTriggers) item else "$item — not available yet") },
                                        onClick = { selectedTrigger = item; triggerMenu = false }
                                    )
                                }
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { actionMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("THEN: $selectedAction") }
                            DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                                customActions.forEach { item ->
                                    DropdownMenuItem(
                                        enabled = item in supportedCustomActions,
                                        text = { Text(if (item in supportedCustomActions) item else "$item — not available yet") },
                                        onClick = { selectedAction = item; actionMenu = false }
                                    )
                                }
                            }
                        }
                        if (selectedTrigger == "Time schedule") {
                            OutlinedTextField(
                                value = scheduledTime,
                                onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() || it == ':' }) scheduledTime = value },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Daily time (24-hour HH:mm)") },
                                placeholder = { Text("09:00") },
                                singleLine = true,
                                supportingText = { Text("Runs daily at this time. Allow Exact alarms in Settings for on-the-minute delivery.") }
                            )
                        }
                        Text("Actions marked \"needs\" require a permission from the Settings tab. Wi-Fi and Bluetooth cannot be switched silently on modern Android, so those give a one-tap shortcut.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            if (ruleName.isBlank()) {
                                validationMessage = "Enter a name before saving."
                            } else if (selectedTrigger !in supportedCustomTriggers) {
                                validationMessage = "This trigger is not available in the current MVP."
                            } else if (selectedAction !in supportedCustomActions) {
                                validationMessage = "This action is not available in the current MVP."
                            } else if (selectedTrigger == "Time schedule" && !scheduledTime.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) {
                                validationMessage = "Enter a valid time like 09:30."
                            } else {
                                val id = "rule_${System.currentTimeMillis()}"
                                customRules = customRules + CustomRule(id, ruleName.trim(), selectedTrigger, selectedAction, true, scheduledTime)
                                store.saveCustomRules(customRules)
                                AutomationScheduler.scheduleNext(context = context)
                                ruleName = ""
                                validationMessage = if (selectedTrigger == "Time schedule") "Daily schedule saved; Android may delay delivery." else "Rule saved locally."
                                showBuilder = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save rule") }
                        if (validationMessage.isNotBlank()) Text(validationMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (validationMessage.isNotBlank() && !showBuilder) {
            item {
                AssistChip(
                    onClick = { validationMessage = "" },
                    label = { Text(validationMessage) },
                    leadingIcon = { Icon(Icons.Default.Info, null) }
                )
            }
        }
        item { Text("Your custom rules (${customRules.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (customRules.isEmpty()) {
            item { Text("No custom rules yet. Create one above.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(customRules, key = { it.id }) { rule ->
                var enabled by remember(rule.id, rule.enabled) { mutableStateOf(rule.enabled) }
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, fontWeight = FontWeight.Bold)
                                Text("IF: ${rule.trigger}", style = MaterialTheme.typography.bodySmall)
                                Text("THEN: ${rule.action}", style = MaterialTheme.typography.bodySmall)
                                if (rule.trigger == "Time schedule") Text("Daily at ${rule.scheduledTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Switch(checked = enabled, onCheckedChange = { newValue ->
                                enabled = newValue
                                customRules = customRules.map { if (it.id == rule.id) it.copy(enabled = newValue) else it }
                                store.saveCustomRules(customRules)
                                AutomationScheduler.scheduleNext(context = context)
                            })
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (rule.trigger == "Manual quick action" && rule.enabled) {
                                TextButton(onClick = {
                                    if (store.getBool("masterPaused", false)) {
                                        validationMessage = "Master Pause is ON. Resume automations before running this rule."
                                    } else {
                                        AutomationEventProcessor.handle(context, "Manual quick action", rule.id)
                                        validationMessage = "Manual rule run requested. Check Diagnostics for the local activity log."
                                    }
                                }) { Icon(Icons.Default.PlayArrow, null); Text("Run now") }
                            }
                            TextButton(onClick = {
                                customRules = customRules.filterNot { it.id == rule.id }
                                store.saveCustomRules(customRules)
                                AutomationScheduler.scheduleNext(context = context)
                            }) { Icon(Icons.Default.Delete, null); Text("Delete") }
                        }
                    }
                }
            }
        }
        item { Text("Enabled presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        val active = presets.filter { store.getBool("preset_${it.id}", false) }
        if (active.isEmpty()) {
            item { Text("No presets enabled yet. Open Presets and turn one on.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(active, key = { it.id }) { p ->
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.title, fontWeight = FontWeight.Bold)
                        Text("WHEN: ${p.trigger}", style = MaterialTheme.typography.bodySmall)
                        val enabledCount = p.actions.count { store.getBool("action_${p.id}_${it.id}", false) }
                        Text("Enabled actions: $enabledCount/${p.actions.size}", color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Preset enabled", Modifier.weight(1f))
                            Switch(checked = store.getBool("preset_${p.id}", false), onCheckedChange = { store.setBool("preset_${p.id}", it) })
                        }
                    }
                }
            }
        }
    }
}

