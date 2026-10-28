package com.privacyflip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
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
    DisposableEffect(context, paused) {
        // Screen broadcasts are monitored only while this app's UI is active.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val trigger = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> "Screen turns ON"
                    Intent.ACTION_SCREEN_OFF -> "Screen turns OFF"
                    else -> return
                }
                if (paused) return
                rules.indices.toList().forEach { index ->
                    val rule = rules[index]
                    if (rule.enabled && rule.trigger == trigger) {
                        rules[index] = rule.copy(runs = rule.runs + 1)
                        saveAll()
                        log("Trigger detected: $trigger · ${rule.name}. Demo action logged; system settings unchanged.")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

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
            rules.add(FlipRule(System.currentTimeMillis(), name, trigger, action))
            saveAll(); log("Created rule: $name"); showAdd = false
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
            StatCard("Total rules", rules.size.toString(), Modifier.weight(1f))
            StatCard("Enabled", rules.count { it.enabled }.toString(), Modifier.weight(1f))
            StatCard("Run count", rules.sumOf { it.runs }.toString(), Modifier.weight(1f))
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                label = { Text("Search rules") },
                placeholder = { Text("Name, trigger or action") },
                singleLine = true
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
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Activity log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Stored locally on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { confirmClear = true }, enabled = logs.isNotBlank()) { Text("Clear") }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
            Text(logs.ifBlank { "No activity yet." }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, dark: Boolean, developer: Boolean, backupJson: String,
    onDark: (Boolean) -> Unit, onDeveloper: (Boolean) -> Unit, onRestore: (String) -> Unit) {
    val context = LocalContext.current
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
                listOf("Manual", "Screen turns ON", "Screen turns OFF", "Phone starts", "Scheduled (planned)", "App opened (planned)", "Location (planned)").forEach { option ->
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
