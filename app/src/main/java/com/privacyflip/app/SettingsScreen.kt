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

internal fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Some phones do not expose every settings screen.
    }
}

@Composable
internal fun PermissionRow(title: String, detail: String, granted: Boolean, buttonLabel: String, onClick: () -> Unit) {
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
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun SettingsPage(
    dark: Boolean, onDark: (Boolean) -> Unit,
    language: String, onLanguage: (String) -> Unit,
    store: LocalStore, presets: List<Preset>, onMessage: (String) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenPresets: () -> Unit, onOpenRules: () -> Unit
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
    var mode by remember { mutableStateOf(store.getString("backendMode", "auto")) }
    val packageUri = Uri.parse("package:${context.packageName}")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            SectionCard("Connection mode") {
                Text(
                    "Auto uses Shizuku first, then Root. None never changes system settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("auto" to "Auto", "shizuku" to "Shizuku", "root" to "Root", "standard" to "None").forEach { (value, label) ->
                        FilterChip(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                store.setString("backendMode", value)
                            },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Timing") {
                var lockDelay by remember { mutableFloatStateOf(store.getInt("lockDelaySec", 0).toFloat()) }
                Text("Wait after locking: ${lockDelay.toInt()} s", fontWeight = FontWeight.Medium)
                Slider(
                    value = lockDelay, onValueChange = { lockDelay = it },
                    onValueChangeFinished = { store.setInt("lockDelaySec", lockDelay.toInt()) },
                    valueRange = 0f..30f, steps = 29
                )
                var unlockDelay by remember { mutableFloatStateOf(store.getInt("unlockDelaySec", 0).toFloat()) }
                Text("Wait after unlocking: ${unlockDelay.toInt()} s", fontWeight = FontWeight.Medium)
                Slider(
                    value = unlockDelay, onValueChange = { unlockDelay = it },
                    onValueChangeFinished = { store.setInt("unlockDelaySec", unlockDelay.toInt()) },
                    valueRange = 0f..10f, steps = 9
                )
                var restore by remember { mutableStateOf(store.getBool("restoreOriginal", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Restore original state", fontWeight = FontWeight.Medium)
                        Text(
                            "On unlock, put each feature back to what it was before locking.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = restore, onCheckedChange = {
                        restore = it
                        store.setBool("restoreOriginal", it)
                    })
                }
            }
        }
        item {
            SectionCard("Background") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep watching lock and unlock", fontWeight = FontWeight.Medium)
                        Text(
                            "Needed for lock, unlock and other triggers. Shows one quiet notification.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = monitorOn, onCheckedChange = {
                        monitorOn = it
                        store.setBool("monitorEnabled", it)
                        MonitorService.sync(context)
                    })
                }
                PermissionRow("Battery optimization", "Set Privacy Flip to Unrestricted so the phone does not stop it.", batteryOk, "Open battery settings") {
                    openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        item {
            SectionCard("Permissions for extra actions") {
                Text(
                    "Only needed for the presets and custom rules. Lock and unlock automation does not need them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PermissionRow("Notifications", "Notification actions and one-tap shortcuts.", notificationsOk, "Allow notifications") {
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
        item {
            SectionCard("Advanced automation") {
                OutlinedButton(onClick = onOpenPresets, modifier = Modifier.fillMaxWidth()) { Text("Ready-made presets") }
                OutlinedButton(onClick = onOpenRules, modifier = Modifier.fillMaxWidth()) { Text("Custom rules") }
            }
        }
        item {
            SectionCard("Appearance") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark theme", Modifier.weight(1f))
                    Switch(checked = dark, onCheckedChange = onDark)
                }
            }
        }
        item {
            SectionCard("Backup") {
                Text(
                    "Export or import your settings as a local file. No account or internet is needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export backup")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Import backup")
                }
                OutlinedButton(onClick = {
                    store.setBool("masterPaused", true)
                    presets.forEach { p ->
                        store.setBool("preset_${p.id}", false)
                        p.actions.forEach { a -> store.setBool("action_${p.id}_${a.id}", false) }
                    }
                    onMessage("Presets switched off and protection paused.")
                }, modifier = Modifier.fillMaxWidth()) { Text("Switch off all presets") }
            }
        }
    }
}
