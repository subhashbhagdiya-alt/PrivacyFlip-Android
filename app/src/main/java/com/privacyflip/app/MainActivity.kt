package com.privacyflip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class FlipRule(
    val id: Long, val name: String, val trigger: String, val action: String,
    val enabled: Boolean = true, val runs: Int = 0
)
private enum class Screen { HOME, AUTOMATIONS, LOGS, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrivacyFlipApp(getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyFlipApp(prefs: android.content.SharedPreferences) {
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var developer by remember { mutableStateOf(prefs.getBoolean("developer", false)) }
    var paused by remember { mutableStateOf(prefs.getBoolean("paused", false)) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var showAdd by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<FlipRule?>(null) }
    var logs by remember { mutableStateOf(prefs.getString("logs", "Welcome to Privacy Flip.\n") ?: "") }
    val rules = remember { mutableStateListOf<FlipRule>().apply { addAll(loadRules(prefs)) } }
    val backupJson = JSONObject().put("app", "Privacy Flip").put("version", 1)
        .put("paused", paused).put("darkTheme", dark).put("developerMode", developer)
        .put("rules", JSONArray().apply {
            rules.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.name)
                .put("trigger", r.trigger).put("action", r.action).put("enabled", r.enabled).put("runs", r.runs)) }
        }).toString(2)

    fun saveAll() {
        prefs.edit().putBoolean("dark", dark).putBoolean("developer", developer)
            .putBoolean("paused", paused).putString("logs", logs)
            .putString("rules", JSONArray().apply {
                rules.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.name)
                    .put("trigger", r.trigger).put("action", r.action).put("enabled", r.enabled).put("runs", r.runs)) }
            }.toString()).apply()
    }
    fun log(message: String) {
        logs = (message + "\n" + logs).take(6000)
        saveAll()
    }

    val context = LocalContext.current
    // Screen events are handled by the opt-in foreground ScreenMonitorService.


    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF8FE3C1), secondary = Color(0xFFB9C7FF),
        background = Color(0xFF0B1110), surface = Color(0xFF141D1B)
    ) else lightColorScheme(
        primary = Color(0xFF176B50), secondary = Color(0xFF4459A8),
        background = Color(0xFFF5F8F6), surface = Color.White
    )
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Privacy Flip", fontWeight = FontWeight.Bold)
                            Text("Private by design · offline-first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }, actions = { IconButton(onClick = { screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") } })
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(screen == Screen.HOME, { screen = Screen.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(screen == Screen.AUTOMATIONS, { screen = Screen.AUTOMATIONS }, { Icon(Icons.Default.Bolt, null) }, label = { Text("Rules") })
                    NavigationBarItem(screen == Screen.LOGS, { screen = Screen.LOGS }, { Icon(Icons.Default.History, null) }, label = { Text("Activity") })
                    NavigationBarItem(screen == Screen.SETTINGS, { screen = Screen.SETTINGS }, { Icon(Icons.Default.Tune, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            when (screen) {
                Screen.HOME -> HomeScreen(Modifier.padding(padding), rules, paused,
                    onPause = { paused = it; saveAll(); log(if (it) "Master Pause enabled" else "Master Pause disabled") },
                    onOpenRules = { screen = Screen.AUTOMATIONS },
                    onRun = { rule -> runRule(rule, rules, paused, ::saveAll, ::log) })
                Screen.AUTOMATIONS -> RulesScreen(Modifier.padding(padding), rules, { showAdd = true },
                    { rule, enabled ->
                        val i = rules.indexOfFirst { it.id == rule.id }
                        if (i >= 0) rules[i] = rules[i].copy(enabled = enabled)
                        saveAll(); log("${rule.name}: ${if (enabled) "enabled" else "disabled"}")
                    },
                    { rule -> runRule(rule, rules, paused, ::saveAll, ::log) },
                    { rule -> ruleToDelete = rule })
                Screen.LOGS -> LogsScreen(Modifier.padding(padding), logs, { logs = ""; saveAll() })
                Screen.SETTINGS -> SettingsScreen(Modifier.padding(padding), dark, developer, backupJson,
                    { dark = it; saveAll() },
                    { developer = it; saveAll(); log("Advanced Developer Mode ${if (it) "enabled" else "disabled"}") },
                    { raw ->
                        runCatching {
                            val backup = JSONObject(raw)
                            require(backup.optString("app") == "Privacy Flip") { "This is not a Privacy Flip backup." }
                            require(backup.optInt("version", -1) == 1) { "Unsupported backup version." }
                            val imported = backup.getJSONArray("rules")
                            require(imported.length() <= 500) { "Backup contains too many rules (maximum 500)." }
                            val restoredRules = (0 until imported.length()).map { i ->
                                val o = imported.getJSONObject(i)
                                val name = o.optString("name", "").trim()
                                val trigger = o.optString("trigger", "Manual").trim()
                                val action = o.optString("action", "Privacy routine (demo)").trim()
                                require(name.isNotEmpty() && name.length <= 48) { "Rule ${i + 1} has an invalid name." }
                                require(trigger.isNotEmpty() && trigger.length <= 64) { "Rule ${i + 1} has an invalid trigger." }
                                require(action.length <= 80) { "Rule ${i + 1} has an action that is too long." }
                                val ruleId = o.optLong("id", System.currentTimeMillis() + i)
                                require(ruleId > 0L) { "Rule ${i + 1} has an invalid ID." }
                                FlipRule(
                                    ruleId,
                                    name, trigger, action.ifBlank { "Privacy routine (demo)" },
                                    o.optBoolean("enabled", true),
                                    o.optInt("runs", 0).coerceIn(0, 1_000_000)
                                )
                            }
                            require(restoredRules.map { it.id }.distinct().size == restoredRules.size) {
                                "Backup contains duplicate rule IDs."
                            }
                            // Validate everything before replacing any current data.
                            rules.clear()
                            rules.addAll(restoredRules)
                            paused = backup.optBoolean("paused", false)
                            dark = backup.optBoolean("darkTheme", true)
                            developer = backup.optBoolean("developerMode", false)
                            logs = ("Backup restored: ${restoredRules.size} rule(s).\n" + logs).take(6000)
                            saveAll()
                            Toast.makeText(context, "Backup restored", Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            Toast.makeText(context, "Restore failed: ${error.message ?: "invalid backup"}", Toast.LENGTH_LONG).show()
                        }
                    })
            }
        }
        if (showAdd) AddRuleDialog({ showAdd = false }) { name, trigger, action ->
            when {
                rules.size >= 500 -> Toast.makeText(context, "Maximum 500 rules allowed", Toast.LENGTH_LONG).show()
                rules.any { it.name.equals(name.trim(), ignoreCase = true) } ->
                    Toast.makeText(context, "A rule with this name already exists", Toast.LENGTH_LONG).show()
                else -> {
                    val usedIds = rules.map { it.id }.toSet()
                    var newId = System.currentTimeMillis()
                    while (newId <= 0L || newId in usedIds) newId++
                    rules.add(FlipRule(newId, name.trim(), trigger, action.trim().ifBlank { "Privacy routine (demo)" }))
                    saveAll()
                    log("Created rule: ${name.trim()}")
                    showAdd = false
                }
            }
        }
        ruleToDelete?.let { rule ->
            AlertDialog(
                onDismissRequest = { ruleToDelete = null },
                title = { Text("Delete rule?") },
                text = { Text("Delete \"${rule.name}\"? This cannot be undone.") },
                confirmButton = {
                    Button(onClick = {
                        rules.removeAll { it.id == rule.id }
                        saveAll()
                        log("Deleted rule: ${rule.name}")
                        ruleToDelete = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete")
                    }
                },
                dismissButton = { TextButton(onClick = { ruleToDelete = null }) { Text("Cancel") } }
            )
        }
    }
}

private fun runRule(rule: FlipRule, rules: MutableList<FlipRule>, paused: Boolean, save: () -> Unit, log: (String) -> Unit) {
    if (paused) { log("Skipped '${rule.name}': Master Pause is enabled"); return }
    if (!rule.enabled) { log("Skipped '${rule.name}': rule is disabled"); return }
    val i = rules.indexOfFirst { it.id == rule.id }
    if (i >= 0) rules[i] = rules[i].copy(runs = rules[i].runs + 1)
    save()
    log("Run requested: ${rule.name}. Demo action logged; Android system privacy changes are not active yet.")
}

@Composable
private fun HomeScreen(modifier: Modifier, rules: List<FlipRule>, paused: Boolean,
    onPause: (Boolean) -> Unit, onOpenRules: () -> Unit, onRun: (FlipRule) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)) {
                            Icon(Icons.Default.Lock, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (paused) "Automations paused" else "You're in control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (paused) "No rules will run until resumed." else "Your rules stay on this device.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Master Pause", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = paused, onCheckedChange = onPause)
                    }
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Total", rules.size.toString(), Modifier.weight(1f))
            StatCard("Enabled", rules.count { it.enabled }.toString(), Modifier.weight(1f))
            StatCard("Disabled", rules.count { !it.enabled }.toString(), Modifier.weight(1f))
        } }
        item { Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rule health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val enabledCount = rules.count { it.enabled }
                val disabledCount = rules.size - enabledCount
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Text(enabledCount.toString(), fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { if (rules.isEmpty()) 0f else enabledCount.toFloat() / rules.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Disabled", modifier = Modifier.weight(1f))
                    Text(disabledCount.toString(), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (rules.isEmpty()) "Create a rule to see your rule status summary."
                    else if (paused) "Master Pause is on. Rule status is shown, but actions are paused."
                    else "Status summary only. Rule actions are still demo-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenRules) { Text("View all") }
        } }
        if (rules.isEmpty()) item { EmptyState("No rules yet", "Create a rule to organize your privacy routines.") }
        items(rules.take(3), key = { it.id }) { rule -> RuleCard(rule, {}, { onRun(rule) }, showToggle = false) }
        item { Text("Prototype note: Run now records a demo action; it does not change Android system privacy settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RulesScreen(modifier: Modifier, rules: List<FlipRule>, onAdd: () -> Unit,
    onToggle: (FlipRule, Boolean) -> Unit, onRun: (FlipRule) -> Unit, onDelete: (FlipRule) -> Unit) {
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("All") }
    val filteredRules = rules.filter { rule ->
        val matchesSearch = rule.name.contains(search, ignoreCase = true) ||
            rule.trigger.contains(search, ignoreCase = true) ||
            rule.action.contains(search, ignoreCase = true)
        val matchesStatus = when (statusFilter) {
            "Enabled" -> rule.enabled
            "Disabled" -> !rule.enabled
            else -> true
        }
        matchesSearch && matchesStatus
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Automations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${rules.size} saved rule(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add") }
        }
        if (rules.isEmpty()) {
            EmptyState("Nothing automated yet", "Add your first rule. Rules are stored offline.")
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(80) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                label = { Text("Search rules") },
                placeholder = { Text("Name, trigger or action") },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )
            Text(
                if (search.isBlank() && statusFilter == "All") "${rules.size} rule(s)"
                else "Showing ${filteredRules.size} of ${rules.size} rule(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                listOf("All", "Enabled", "Disabled").forEach { option ->
                    FilterChip(
                        selected = statusFilter == option,
                        onClick = { statusFilter = option },
                        label = { Text(option) }
                    )
                }
            }
            if (filteredRules.isEmpty()) {
                EmptyState("No matching rules", "Try another search or status filter.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filteredRules, key = { it.id }) { rule ->
                        RuleCard(rule, { onToggle(rule, it) }, { onRun(rule) }, onDelete = { onDelete(rule) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: FlipRule, onToggle: (Boolean) -> Unit, onRun: () -> Unit, showToggle: Boolean = true, onDelete: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                    Icon(Icons.Default.Bolt, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, fontWeight = FontWeight.Bold)
                    Text(rule.trigger, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showToggle) Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text("Action: ${rule.action}", style = MaterialTheme.typography.bodyMedium)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            ) {
                Text(
                    "DEMO ONLY · This rule does not change Android settings",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (rule.trigger.contains("planned", ignoreCase = true)) {
                Text(
                    "This trigger is planned and is not active yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Runs: ${rule.runs}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onRun) { Icon(Icons.Default.PlayArrow, null); Text("Run now") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun LogsScreen(modifier: Modifier, logs: String, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity log?") },
            text = { Text("This permanently clears the activity history saved on this device. Your rules will not be deleted.") },
            confirmButton = {
                Button(onClick = {
                    onClear()
                    confirmClear = false
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Clear log")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
    val context = LocalContext.current
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Activity log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Stored locally on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Privacy Flip activity log", logs))
                    Toast.makeText(context, "Activity log copied", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Could not copy activity log", Toast.LENGTH_SHORT).show()
                }
            }, enabled = logs.isNotBlank()) { Text("Copy") }
            TextButton(onClick = { confirmClear = true }, enabled = logs.isNotBlank()) { Text("Clear") }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
            if (logs.isBlank()) {
                Text("No activity yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(logs.lines().filter { it.isNotBlank() }) { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, dark: Boolean, developer: Boolean, backupJson: String,
    onDark: (Boolean) -> Unit, onDeveloper: (Boolean) -> Unit, onRestore: (String) -> Unit) {
    val context = LocalContext.current
    val appPrefs = remember(context) { context.getSharedPreferences("privacy_flip", Context.MODE_PRIVATE) }
    var monitoring by remember { mutableStateOf(appPrefs.getBoolean("screen_monitor_enabled", false)) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val reader = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?: error("Unable to read selected file")
                reader.use {
                    val output = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        require(output.length + count <= 1_000_000) {
                            "Backup file is too large (maximum 1 MB)."
                        }
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }
            }.onSuccess { pendingImport = it }.onFailure {
                Toast.makeText(context, "Could not read backup: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backupJson) }
                    ?: error("Unable to open destination")
            }.onSuccess {
                Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Backup failed: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    pendingImport?.let { rawBackup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Replace current data?") },
            text = { Text("Restoring this backup replaces your current rules and saved preferences. Export a backup first if you may need your current data.") },
            confirmButton = {
                Button(onClick = {
                    pendingImport = null
                    onRestore(rawBackup)
                }) { Text("Restore backup") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your preferences are saved locally.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SettingRow("Dark theme", "Use a darker interface", dark, onDark) }
        item { SettingRow("Advanced Developer Mode", "Show diagnostic details and advanced options", developer, onDeveloper) }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background screen monitoring", fontWeight = FontWeight.Bold)
                    Text(
                        if (monitoring) "Monitoring is enabled. Screen triggers can be logged while the app is in the background; actions remain demo-only."
                        else "Enable a persistent notification and monitor screen ON/OFF triggers while this service runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            runCatching {
                                if (monitoring) {
                                    context.stopService(Intent(context, ScreenMonitorService::class.java))
                                    monitoring = false
                                    appPrefs.edit().putBoolean("screen_monitor_enabled", false).apply()
                                    Toast.makeText(context, "Screen monitoring stopped", Toast.LENGTH_SHORT).show()
                                } else {
                                    val intent = Intent(context, ScreenMonitorService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                    else context.startService(intent)
                                    monitoring = true
                                    appPrefs.edit().putBoolean("screen_monitor_enabled", true).apply()
                                    Toast.makeText(context, "Screen monitoring started", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure {
                                Toast.makeText(context, "Could not change monitoring: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (monitoring) "Stop screen monitoring" else "Start screen monitoring") }
                    Text(
                        "Android may stop background services under battery restrictions. This does not automatically change Wi-Fi, mobile data, GPS, camera or microphone settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android privacy shortcuts", fontWeight = FontWeight.Bold)
                    Text("These open Android settings. You stay in control and must confirm any system changes there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { openSettingsPage(context, Intent(Settings.ACTION_PRIVACY_SETTINGS), "Privacy settings") }, modifier = Modifier.fillMaxWidth()) { Text("Open Privacy settings") }
                    OutlinedButton(onClick = { openSettingsPage(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }, "App permissions") }, modifier = Modifier.fillMaxWidth()) { Text("App info & permissions") }
                    OutlinedButton(onClick = { openSettingsPage(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), "Notification settings") }, modifier = Modifier.fillMaxWidth()) { Text("App notification settings") }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android privacy controls", fontWeight = FontWeight.Bold)
                    Text(
                        "Open Android's app settings to review permissions and other controls yourself. Privacy Flip does not silently change system permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { openSettingsPage(context, Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Wi-Fi settings") }
                    OutlinedButton(
                        onClick = { openSettingsPage(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Location settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Location / GPS settings") }
                    OutlinedButton(
                        onClick = { openSettingsPage(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Bluetooth settings") }
                    OutlinedButton(
                        onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                            } else {
                                Intent(Settings.ACTION_WIRELESS_SETTINGS)
                            }
                            openSettingsPage(context, intent, "Internet connectivity panel")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Internet connectivity panel") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            }.onFailure {
                                Toast.makeText(context, "Could not open Android app settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Android app settings")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local backup", fontWeight = FontWeight.Bold)
                    Text("Export rules and preferences to a JSON file on your device.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { exportLauncher.launch("privacy-flip-backup.json") }) { Text("Export backup") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Import / Restore") }
                    }
                    Text("Restore replaces current rules and saved preferences with the selected backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (developer) item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp)) {
                    Text("Developer diagnostics", fontWeight = FontWeight.Bold)
                    Text("Storage: SharedPreferences · Network access: none · Minimum Android: 8.0 (API 26)", style = MaterialTheme.typography.bodySmall)
                    Text("System privacy actions are not connected in this prototype.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { Text("Privacy Flip 0.2.0 · Offline-first prototype", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}


private fun openSettingsPage(context: Context, intent: Intent, label: String) {
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "$label could not be opened on this device.", Toast.LENGTH_LONG).show()
        }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Shield, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("Manual") }
    var action by remember { mutableStateOf("Privacy routine (demo)") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text("Rule name") }, singleLine = true)
                Text("Trigger", style = MaterialTheme.typography.labelLarge)
                listOf("Manual", "Screen turns ON", "Screen turns OFF", "Phone unlocked", "Phone starts", "Scheduled (planned)", "App opened (planned)", "Location (planned)").forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = trigger == option, onClick = { trigger = option })
                        Text(option)
                    }
                }
                OutlinedTextField(value = action, onValueChange = { action = it.take(80) }, label = { Text("Action description") }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), trigger, action.trim().ifBlank { "Privacy routine (demo)" }) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun loadRules(prefs: android.content.SharedPreferences): List<FlipRule> {
    val raw = prefs.getString("rules", null) ?: return listOf(
        FlipRule(1L, "Focus mode", "Manual", "Privacy routine (demo)"),
        FlipRule(2L, "Quiet hours", "Scheduled (planned)", "Privacy routine (demo)", enabled = false)
    )
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FlipRule(o.optLong("id", System.currentTimeMillis() + i), o.optString("name", "Untitled"),
                o.optString("trigger", "Manual"), o.optString("action", "Privacy routine (demo)"),
                o.optBoolean("enabled", true), o.optInt("runs", 0))
        }
    } catch (_: Exception) { emptyList() }
}
