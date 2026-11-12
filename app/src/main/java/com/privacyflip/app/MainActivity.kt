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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationScheduler.scheduleNext(applicationContext)
        setContent { PrivacyFlipApp(applicationContext, requestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }) }
    }
}

private enum class Page { HOME, PRESETS, RULES, DIAGNOSTICS, SETTINGS }

private data class CustomRule(val id: String, val name: String, val trigger: String, val action: String, val enabled: Boolean, val scheduledTime: String = "09:00")

private val customTriggers = TriggerCatalog.all
private val customActions = Actions.all.map { it.label }
private val supportedCustomTriggers = customTriggers.toSet()
private val supportedCustomActions = customActions.toSet()
private val categories = PresetCatalog.categories
private fun makePresets(): List<Preset> = PresetCatalog.all

private class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE)
    fun getBool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun getString(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun setString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun getInt(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun setInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    fun getEventLogs(): List<String> = try {
        val a = JSONArray(prefs.getString("eventLog", "[]"))
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { value -> value.isNotBlank() } }
    } catch (_: Exception) { emptyList() }
    fun getCustomRules(): List<CustomRule> = try {
        val array = JSONArray(prefs.getString("customRules", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            CustomRule(o.optString("id"), o.optString("name"), o.optString("trigger"), o.optString("action"), o.optBoolean("enabled"), o.optString("scheduledTime", "09:00").takeIf { it.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) } ?: "09:00")
        }
    } catch (_: Exception) { emptyList() }
    fun saveCustomRules(rules: List<CustomRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("trigger", rule.trigger).put("action", rule.action).put("enabled", rule.enabled).put("scheduledTime", rule.scheduledTime))
        }
        prefs.edit().putString("customRules", array.toString()).apply()
    }
    fun exportJson(presets: List<Preset>): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("app", "Privacy Flip")
        root.put("darkTheme", getBool("darkTheme", true))
        root.put("language", getString("language", "hi"))
        root.put("masterPaused", getBool("masterPaused", false))
        root.put("monitorEnabled", getBool("monitorEnabled", true))
        root.put("batteryThreshold", getInt("batteryThreshold", 20))
        val array = JSONArray()
        presets.forEach { p ->
            val item = JSONObject()
            item.put("id", p.id)
            item.put("enabled", getBool("preset_${p.id}", false))
            val actions = JSONObject()
            p.actions.forEach { a -> actions.put(a.id, getBool("action_${p.id}_${a.id}", false)) }
            item.put("actions", actions)
            array.put(item)
        }
        root.put("presets", array)
        val customArray = JSONArray()
        getCustomRules().forEach { rule ->
            customArray.put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("trigger", rule.trigger).put("action", rule.action).put("enabled", rule.enabled).put("scheduledTime", rule.scheduledTime))
        }
        root.put("customRules", customArray)
        return root.toString(2)
    }
    fun importJson(json: String, presets: List<Preset>): Boolean {
        return try {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion", -1) != 1) return false
        setBool("darkTheme", root.optBoolean("darkTheme", true))
        setString("language", root.optString("language", "hi"))
        setBool("masterPaused", root.optBoolean("masterPaused", false))
        setBool("monitorEnabled", root.optBoolean("monitorEnabled", true))
        setInt("batteryThreshold", root.optInt("batteryThreshold", 20).coerceIn(5, 50))
        val customArray = root.optJSONArray("customRules") ?: JSONArray()
        val importedRules = mutableListOf<CustomRule>()
        for (i in 0 until customArray.length()) {
            val o = customArray.optJSONObject(i) ?: continue
            val trigger = o.optString("trigger")
            val action = o.optString("action")
            if (trigger !in supportedCustomTriggers || action !in supportedCustomActions) continue
            importedRules += CustomRule(o.optString("id").ifBlank { "rule_${System.currentTimeMillis()}_$i" },
                o.optString("name", "Custom rule"), trigger, action, o.optBoolean("enabled", false), o.optString("scheduledTime", "09:00").takeIf { it.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) } ?: "09:00")
        }
        saveCustomRules(importedRules)
        val arr = root.optJSONArray("presets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (presets.none { it.id == id }) continue
            setBool("preset_$id", item.optBoolean("enabled", false))
            val actions = item.optJSONObject("actions") ?: JSONObject()
            presets.firstOrNull { it.id == id }?.actions?.forEach { a ->
                setBool("action_${id}_${a.id}", actions.optBoolean(a.id, false))
            }
        }
        true
        } catch (_: Exception) { false }
    }
}

@Composable
private fun PrivacyFlipApp(context: Context, requestNotificationPermission: () -> Unit) {
    val store = remember { LocalStore(context) }
    val presets = remember { makePresets() }

    // Triggers are observed by MonitorService so they keep working when the app is closed.
    var page by remember { mutableStateOf(Page.HOME) }
    var dark by remember { mutableStateOf(store.getBool("darkTheme", true)) }
    var language by remember { mutableStateOf(store.getString("language", "hi")) }
    var selectedCategory by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var infoMessage by remember { mutableStateOf("") }
    var refreshCounter by remember { mutableIntStateOf(0) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            infoMessage = try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportJson(presets)) }
                    ?: throw IllegalStateException("Could not open destination file")
                "Backup exported successfully"
            } catch (e: Exception) { "Backup export failed: ${e.message ?: "unknown error"}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Could not read selected file")
                store.importJson(json, presets)
            } catch (_: Exception) { false }
            if (result) {
                AutomationScheduler.scheduleNext(context = context)
                dark = store.getBool("darkTheme", true)
                language = store.getString("language", "hi")
                refreshCounter++
                infoMessage = "Backup restored"
            } else infoMessage = "Import failed: invalid or incompatible JSON backup"
        }
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFB6C1FF), secondary = Color(0xFF7EE0B0),
        background = Color(0xFF10131B), surface = Color(0xFF191E29)
    ) else lightColorScheme(
        primary = Color(0xFF4657C8), secondary = Color(0xFF16865B),
        background = Color(0xFFF5F6FC), surface = Color.White
    )
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavItem("Home", Icons.Default.Home, page == Page.HOME) { page = Page.HOME }
                    NavItem("Presets", Icons.Default.Tune, page == Page.PRESETS) { page = Page.PRESETS }
                    NavItem("Rules", Icons.Default.AccountTree, page == Page.RULES) { page = Page.RULES }
                    NavItem("Diagnostics", Icons.Default.FactCheck, page == Page.DIAGNOSTICS) { page = Page.DIAGNOSTICS }
                    NavItem("Settings", Icons.Default.Settings, page == Page.SETTINGS) { page = Page.SETTINGS }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.Shield, null, Modifier.padding(10.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Privacy Flip", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (language == "hi") "ऑफलाइन ऑटोमेशन" else "Offline-first automation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        dark = !dark
                        store.setBool("darkTheme", dark)
                    }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Toggle theme") }
                }
                Spacer(Modifier.height(12.dp))
                key(refreshCounter) {
                    when (page) {
                        Page.HOME -> HomePage(presets, store, language, onGoPresets = { page = Page.PRESETS }, onGoRules = { page = Page.RULES })
                        Page.PRESETS -> PresetsPage(presets, store, selectedCategory, { selectedCategory = it }, search, { search = it }, language)
                        Page.RULES -> RulesPage(presets, store, language)
                        Page.DIAGNOSTICS -> DiagnosticsPage(presets, store, language)
                        Page.SETTINGS -> SettingsPage(
                            dark, { dark = it; store.setBool("darkTheme", it) },
                            language, { language = it; store.setString("language", it) },
                            store, presets, { infoMessage = it },
                            onExport = { exportLauncher.launch("privacy-flip-backup.json") },
                            onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            onRequestNotificationPermission = requestNotificationPermission
                        )
                    }
                }
                if (infoMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(onClick = { infoMessage = "" }, label = { Text(infoMessage) }, leadingIcon = { Icon(Icons.Default.Info, null) })
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) })
}

@Composable
private fun HomePage(presets: List<Preset>, store: LocalStore, language: String, onGoPresets: () -> Unit, onGoRules: () -> Unit) {
    val context = LocalContext.current
    val active = presets.count { store.getBool("preset_${it.id}", false) }
    val enabledActions = presets.sumOf { p -> p.actions.count { store.getBool("action_${p.id}_${it.id}", false) } }
    var masterPaused by remember { mutableStateOf(store.getBool("masterPaused", false)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(if (language == "hi") "Tumhara control, tumhare rules" else "Your device. Your rules.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(if (language == "hi") "Rules offline save hote hain aur background mein chalte hain." else "Rules are saved locally and run in the background while monitoring is on.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (language == "hi") "Master pause" else "Master pause", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(if (masterPaused) "Automations paused" else "Saved rules enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Switch(checked = !masterPaused, onCheckedChange = {
                            masterPaused = !it
                            store.setBool("masterPaused", masterPaused)
                            AutomationScheduler.scheduleNext(context = context)
                        })
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Presets", "${presets.size}", Modifier.weight(1f))
                MetricCard("Enabled", "$active", Modifier.weight(1f))
                MetricCard("Actions ON", "$enabledActions", Modifier.weight(1f))
            }
        }
        item { SectionTitle("Quick start") }
        item { QuickCard("Preset library", "Ready-made automations you can switch on", Icons.Default.Tune, onGoPresets) }
        item { QuickCard("Custom rules", "View rules and their action toggles", Icons.Default.AccountTree, onGoRules) }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Build status", fontWeight = FontWeight.Bold)
                    }
                    Text("Triggers run through a background monitor. Some actions need one-time permissions: open Settings to grant them.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun QuickCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun PresetsPage(
    presets: List<Preset>, store: LocalStore, category: String, onCategory: (String) -> Unit,
    search: String, onSearch: (String) -> Unit, language: String
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(), label = { Text(if (language == "hi") "Preset khojein" else "Search presets") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All") .plus(categories).take(3).forEach { cat ->
                FilterChip(selected = category == cat, onClick = { onCategory(cat) }, label = { Text(cat, maxLines = 1) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.drop(2).forEach { cat ->
                FilterChip(selected = category == cat, onClick = { onCategory(cat) }, label = { Text(cat, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val filtered = presets.filter {
            (category == "All" || it.category == category) &&
                (search.isBlank() || it.title.contains(search, true) || it.category.contains(search, true) || it.trigger.contains(search, true))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(filtered, key = { it.id }) { preset ->
                PresetCard(preset, store)
            }
        }
    }
}

@Composable
private fun PresetCard(preset: Preset, store: LocalStore) {
    var expanded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(store.getBool("preset_${preset.id}", false)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.title, fontWeight = FontWeight.SemiBold)
                    Text(preset.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    store.setBool("preset_${preset.id}", it)
                    if (it) expanded = true
                })
            }
            Text(preset.trigger, style = MaterialTheme.typography.bodyMedium)
            Text(preset.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled) Text("Turn on the actions you want below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide actions" else "Configure actions")
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                preset.actions.forEach { action ->
                    var actionEnabled by remember { mutableStateOf(store.getBool("action_${preset.id}_${action.id}", false)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(action.title, fontWeight = FontWeight.Medium)
                            Text(action.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = actionEnabled, onCheckedChange = {
                            actionEnabled = it
                            store.setBool("action_${preset.id}_${action.id}", it)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesPage(presets: List<Preset>, store: LocalStore, language: String) {
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

@Composable
private fun DiagnosticsPage(presets: List<Preset>, store: LocalStore, language: String) {
    val context = LocalContext.current
    val monitorOn = store.getBool("monitorEnabled", true)
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val dndOk = (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.isNotificationPolicyAccessGranted == true
    val writeOk = Settings.System.canWrite(context)
    val lockOk = PrivacyFlipAccessibilityService.instance != null
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Triggers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    DiagnosticRow("Background monitor", if (monitorOn) "On: screen, unlock, battery, network, headset, airplane and flip triggers keep running" else "Off: those triggers are inactive (turn on in Settings)", if (monitorOn) Icons.Default.CheckCircle else Icons.Default.Warning, monitorOn)
                    DiagnosticRow("Charger and boot", "System broadcasts, work even when the app is closed", Icons.Default.CheckCircle, true)
                    DiagnosticRow("Daily schedule", "Alarm based; exact timing needs the Exact alarms permission in Settings", Icons.Default.Info, true)
                    DiagnosticRow("Manual quick action", "Run now button processes one enabled rule", Icons.Default.PlayArrow, true)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    DiagnosticRow("Notification", if (notificationsOk) "Allowed" else "Permission not granted", if (notificationsOk) Icons.Default.CheckCircle else Icons.Default.Warning, notificationsOk)
                    DiagnosticRow("Do Not Disturb and silent mode", if (dndOk) "Access granted" else "Needs access (Settings)", if (dndOk) Icons.Default.CheckCircle else Icons.Default.Warning, dndOk)
                    DiagnosticRow("Brightness", if (writeOk) "Access granted" else "Needs Modify system settings (Settings)", if (writeOk) Icons.Default.CheckCircle else Icons.Default.Warning, writeOk)
                    DiagnosticRow("Lock screen", if (lockOk) "Accessibility service running" else "Needs Accessibility service (Settings)", if (lockOk) Icons.Default.CheckCircle else Icons.Default.Warning, lockOk)
                    DiagnosticRow("Vibrate, sound mode vibrate/normal, flashlight", "No special access needed", Icons.Default.CheckCircle, true)
                    DiagnosticRow("Wi-Fi / Bluetooth", "Android blocks silent switching without root; a one-tap shortcut notification is used", Icons.Default.Info, false)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Configuration summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Presets: ${presets.size}")
                    Text("Enabled presets: ${presets.count { store.getBool("preset_${it.id}", false) }}")
                    Text("Enabled actions: ${presets.sumOf { p -> p.actions.count { store.getBool("action_${p.id}_${it.id}", false) } }}")
                    Text("Master paused: ${store.getBool("masterPaused", false)}")
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recent local activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val logs = remember { store.getEventLogs() }
                    if (logs.isEmpty()) Text("No events recorded yet. Enable a preset or rule, then plug a charger or lock and unlock the phone.", style = MaterialTheme.typography.bodySmall)
                    else logs.takeLast(12).reversed().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(title: String, status: String, icon: ImageVector, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Some phones do not expose every settings screen.
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, buttonLabel: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!granted) OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
    }
}

@Composable
private fun SettingsPage(
    dark: Boolean, onDark: (Boolean) -> Unit,
    language: String, onLanguage: (String) -> Unit,
    store: LocalStore, presets: List<Preset>, onMessage: (String) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    var statusTick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) statusTick++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val notificationsOk = remember(statusTick) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val dndOk = remember(statusTick) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.isNotificationPolicyAccessGranted == true
    }
    val writeOk = remember(statusTick) { Settings.System.canWrite(context) }
    val lockOk = remember(statusTick) { PrivacyFlipAccessibilityService.instance != null }
    val exactOk = remember(statusTick) {
        Build.VERSION.SDK_INT < 31 || (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
    }
    val batteryOk = remember(statusTick) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    var monitorOn by remember { mutableStateOf(store.getBool("monitorEnabled", true)) }
    val packageUri = Uri.parse("package:${context.packageName}")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark theme", Modifier.weight(1f))
                        Switch(checked = dark, onCheckedChange = onDark)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Language / भाषा", Modifier.weight(1f))
                        TextButton(onClick = { onLanguage("hi") }) { Text(if (language == "hi") "✓ हिंदी" else "हिंदी") }
                        TextButton(onClick = { onLanguage("en") }) { Text(if (language == "en") "✓ English" else "English") }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep triggers running", fontWeight = FontWeight.Medium)
                            Text("Needed for screen, unlock, battery, network, headset, airplane and flip triggers. Shows one quiet notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = monitorOn, onCheckedChange = {
                            monitorOn = it
                            store.setBool("monitorEnabled", it)
                            MonitorService.sync(context)
                        })
                    }
                    PermissionRow("Battery optimization", "Set Privacy Flip to Unrestricted so the phone does not kill the monitor.", batteryOk, "Open battery settings") {
                        openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permissions for actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("No root, Shizuku or ADB. Each permission is optional and only needed by the action named.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PermissionRow("Notifications", "Notification actions and one-tap Wi-Fi/Bluetooth shortcuts.", notificationsOk, "Allow notifications") {
                        onRequestNotificationPermission()
                    }
                    PermissionRow("Do Not Disturb access", "Silent mode and Do Not Disturb actions.", dndOk, "Grant access") {
                        openSettings(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                    PermissionRow("Modify system settings", "Brightness actions.", writeOk, "Grant access") {
                        openSettings(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri))
                    }
                    PermissionRow("Accessibility service", "Lock screen action. It reads nothing on screen. If Android greys it out, open App info, tap the three dots, then Allow restricted settings.", lockOk, "Open accessibility settings") {
                        openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionRow("Exact alarms", "Daily schedules at the exact minute.", exactOk, "Grant access") {
                        if (Build.VERSION.SDK_INT >= 31) openSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
                    }
                    var threshold by remember { mutableFloatStateOf(store.getInt("batteryThreshold", 20).toFloat()) }
                    Text("Low battery trigger: ${threshold.toInt()}%", fontWeight = FontWeight.Medium)
                    Slider(value = threshold, onValueChange = { threshold = it }, onValueChangeFinished = {
                        store.setInt("batteryThreshold", threshold.toInt())
                    }, valueRange = 5f..50f, steps = 8)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup & restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Export or import your settings as a local JSON file. No account or internet is needed.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export backup JSON")
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Import backup JSON")
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy & data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("No account and no network permission. Everything stays on this device.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        store.setBool("masterPaused", true)
                        presets.forEach { p ->
                            store.setBool("preset_${p.id}", false)
                            p.actions.forEach { a -> store.setBool("action_${p.id}_${a.id}", false) }
                        }
                        onMessage("All local preset toggles reset.")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Reset preset toggles") }
                }
            }
        }
    }
}
