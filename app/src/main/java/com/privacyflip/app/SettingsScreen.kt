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
internal fun SettingsPage(
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
