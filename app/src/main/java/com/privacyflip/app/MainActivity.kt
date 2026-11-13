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
