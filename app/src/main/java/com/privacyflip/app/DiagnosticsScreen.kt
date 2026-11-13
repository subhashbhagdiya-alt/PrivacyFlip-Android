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
internal fun DiagnosticsPage(presets: List<Preset>, store: LocalStore, language: String) {
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
internal fun DiagnosticRow(title: String, status: String, icon: ImageVector, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

