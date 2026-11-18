package com.privacyflip.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val Amber = Color(0xFFE09A1A)

private fun iconFor(feature: Feature): ImageVector = when (feature) {
    Feature.WIFI -> Icons.Default.Wifi
    Feature.BLUETOOTH -> Icons.Default.Bluetooth
    Feature.LOCATION -> Icons.Default.LocationOn
    Feature.MOBILE_DATA -> Icons.Default.SignalCellularAlt
    Feature.NFC -> Icons.Default.Nfc
    Feature.BRIGHTNESS -> Icons.Default.BrightnessLow
    Feature.CAMERA_PRIVACY -> Icons.Default.CameraAlt
    Feature.MIC_PRIVACY -> Icons.Default.MicOff
}

private fun ruleText(feature: Feature): String = when (feature) {
    Feature.BRIGHTNESS -> "Lowest when locked, normal again when unlocked"
    Feature.BLUETOOTH -> "Off when locked (not while sound plays), on when unlocked"
    Feature.CAMERA_PRIVACY, Feature.MIC_PRIVACY -> "On when locked, off when unlocked · experimental"
    else -> "Off when locked, on when unlocked"
}

private fun openShizuku(context: Context) {
    try {
        val launch = context.packageManager.getLaunchIntentForPackage(ShizukuBridge.PACKAGE)
        if (launch != null) context.startActivity(launch)
        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    } catch (_: Exception) {
        // No browser or launcher available.
    }
}

private fun shizukuState(): String = when {
    !ShizukuBridge.isRunning() -> "not running"
    !ShizukuBridge.hasPermission() -> "needs permission"
    else -> "ready"
}

internal fun readActivityLog(context: Context): List<String> = try {
    val array = JSONArray(context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE).getString("eventLog", "[]"))
    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.reversed()
} catch (_: Exception) {
    emptyList()
}

internal fun clearActivityLog(context: Context) {
    context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE).edit().remove("eventLog").apply()
}

/** One activity line: small time, then the message coloured by outcome. */
@Composable
internal fun ActivityRow(entry: String) {
    val parts = entry.split(" \u00b7 ", limit = 2)
    val time = parts[0].substringAfter(" ", parts[0])
    val message = if (parts.size > 1) parts[1] else entry
    val color = when {
        message.contains("FAILED") || message.contains("NOT verified") -> MaterialTheme.colorScheme.error
        message.contains("unverified") -> MaterialTheme.colorScheme.onSurfaceVariant
        message.contains("verified") -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column {
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** The whole Home screen content below the master switch: connection, switches, test and latest activity. */
@Composable
internal fun LockUnlockCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE) }
    var rootFound by remember { mutableStateOf(RootShell.cachedAvailable()) }
    var shizuku by remember { mutableStateOf("checking") }
    var logs by remember { mutableStateOf(readActivityLog(context)) }
    LaunchedEffect(Unit) {
        rootFound = withContext(Dispatchers.IO) { RootShell.isAvailable() }
        while (true) {
            shizuku = withContext(Dispatchers.IO) { shizukuState() }
            logs = readActivityLog(context)
            delay(2000L)
        }
    }

    val mode = prefs.getString("backendMode", "auto") ?: "auto"
    val viaShizuku = shizuku == "ready"
    val viaRoot = rootFound == true
    val backend: AutomationBackend = when (mode) {
        "standard" -> StandardBackend
        "root" -> RootBackend
        "shizuku" -> ShizukuBackend
        else -> if (viaShizuku) ShizukuBackend else if (viaRoot) RootBackend else StandardBackend
    }
    val connected = when (mode) {
        "standard" -> false
        "root" -> viaRoot
        "shizuku" -> viaShizuku
        else -> viaShizuku || viaRoot
    }
    // Brightness can also be changed directly when the "Modify system settings" permission is given.
    val canDim = BrightnessControl.canWriteDirectly(context)
    val title: String
    val message: String
    val action: Pair<String, () -> Unit>?
    when {
        connected -> {
            title = "Connected via ${backend.name}"
            message = "Wi-Fi, Bluetooth, Location, mobile data and NFC can now be switched."
            action = null
        }
        mode == "standard" -> {
            title = "Not connected"
            message = "Connection mode is set to None. Choose Auto, Shizuku or Root in Settings."
            action = null
        }
        mode == "root" -> {
            title = "Root not found"
            message = "Root access was not granted. Choose Auto or Shizuku in Settings."
            action = null
        }
        shizuku == "needs permission" -> {
            title = "Shizuku is running"
            message = "Allow Privacy Flip to use it."
            action = "Allow access" to { ShizukuBridge.requestPermission() }
        }
        shizuku == "checking" -> {
            title = "Checking connection"
            message = ""
            action = null
        }
        else -> {
            title = "Not connected"
            message = "Start Shizuku with Wireless debugging, then come back. The switches stay locked until then."
            action = "Open Shizuku" to { openShizuku(context) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (connected) MaterialTheme.colorScheme.secondary else Amber, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (action != null) {
                    Button(onClick = action.second, modifier = Modifier.fillMaxWidth()) { Text(action.first) }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Switch on lock and unlock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Works only on what is on right now. A setting you keep off on the phone stays off, and only what Privacy Flip turned off is turned back on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Feature.values().forEach { feature ->
                    var enabled by remember { mutableStateOf(prefs.getBoolean(feature.enabledKey, false)) }
                    val usable = connected || (feature == Feature.BRIGHTNESS && canDim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(feature), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(feature.title, fontWeight = FontWeight.Medium)
                            Text(ruleText(feature), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled, enabled = usable, onCheckedChange = {
                            enabled = it
                            prefs.edit().putBoolean(feature.enabledKey, it).apply()
                        })
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Try it now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Runs the same steps as a real lock or unlock, without locking the phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { LockUnlockEngine.runNow(context, true) }, enabled = connected || canDim, modifier = Modifier.weight(1f)) {
                        Text("Test lock")
                    }
                    OutlinedButton(onClick = { LockUnlockEngine.runNow(context, false) }, enabled = connected || canDim, modifier = Modifier.weight(1f)) {
                        Text("Test unlock")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Latest activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (logs.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.take(5).forEach { ActivityRow(it) }
                }
            }
        }
    }
}
