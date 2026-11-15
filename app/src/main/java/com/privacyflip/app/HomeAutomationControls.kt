package com.privacyflip.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private fun iconFor(feature: Feature): ImageVector = when (feature) {
    Feature.WIFI -> Icons.Default.Wifi
    Feature.BLUETOOTH -> Icons.Default.Bluetooth
    Feature.LOCATION -> Icons.Default.LocationOn
    Feature.MOBILE_DATA -> Icons.Default.SignalCellularAlt
    Feature.NFC -> Icons.Default.Nfc
    Feature.CAMERA_PRIVACY -> Icons.Default.CameraAlt
    Feature.MIC_PRIVACY -> Icons.Default.MicOff
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

@Composable
private fun ModeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
internal fun LockUnlockCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("backendMode", "auto") ?: "auto") }
    var rootFound by remember { mutableStateOf(RootShell.cachedAvailable()) }
    var shizuku by remember { mutableStateOf("checking") }
    var recheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(recheck) {
        rootFound = withContext(Dispatchers.IO) { RootShell.isAvailable(recheck > 0) }
        shizuku = withContext(Dispatchers.IO) {
            when {
                !ShizukuBridge.isRunning() -> "not running"
                !ShizukuBridge.hasPermission() -> "needs permission"
                else -> "ready"
            }
        }
    }
    DisposableEffect(Unit) {
        val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> recheck++ }
        val onBinder = Shizuku.OnBinderReceivedListener { recheck++ }
        try {
            Shizuku.addRequestPermissionResultListener(onPermission)
            Shizuku.addBinderReceivedListenerSticky(onBinder)
        } catch (_: Throwable) {
            // Shizuku classes unavailable: the status simply stays on the last check.
        }
        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(onPermission)
                Shizuku.removeBinderReceivedListener(onBinder)
            } catch (_: Throwable) {
                // Nothing to clean up.
            }
        }
    }
    val backend: AutomationBackend = when (mode) {
        "standard" -> StandardBackend
        "root" -> RootBackend
        "shizuku" -> ShizukuBackend
        else -> if (shizuku == "ready") ShizukuBackend else if (rootFound == true) RootBackend else StandardBackend
    }
    val rootText = when (rootFound) {
        true -> "detected"
        false -> "not found"
        null -> "checking"
    }
    val select: (String) -> Unit = { value ->
        mode = value
        prefs.edit().putString("backendMode", value).apply()
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lock / Unlock automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "One switch per feature. When locked: connectivity OFF, privacy ON. When unlocked: the reverse.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Mode in use: ${backend.name}", style = MaterialTheme.typography.labelLarge)
            Text(
                "Root: $rootText · Shizuku: $shizuku",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeChip(mode == "auto", "Auto") { select("auto") }
                ModeChip(mode == "standard", "Standard") { select("standard") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeChip(mode == "root", "Root") { select("root") }
                ModeChip(mode == "shizuku", "Shizuku") { select("shizuku") }
                TextButton(onClick = { recheck++ }) { Text("Re-check") }
            }
            if (shizuku == "not running") {
                Text(
                    "Shizuku is not running. Install it, then start it from its app using Wireless debugging (Developer options). It must be started again after every reboot.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { openShizuku(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Shizuku") }
            } else if (shizuku == "needs permission") {
                Button(onClick = { ShizukuBridge.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Shizuku permission")
                }
            }
            if (!backend.supports(Feature.WIFI)) {
                Text(
                    "Standard Android cannot switch these settings. Switches are saved, but skipped and logged until Shizuku or Root is ready.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                )
            }
            Feature.values().forEach { feature ->
                var enabled by remember { mutableStateOf(prefs.getBoolean(feature.enabledKey, false)) }
                val note = when {
                    !backend.supports(feature) -> " · needs Shizuku or Root"
                    feature == Feature.CAMERA_PRIVACY || feature == Feature.MIC_PRIVACY -> " · experimental, cannot be verified"
                    else -> ""
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(feature), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(feature.title, fontWeight = FontWeight.Medium)
                        Text(
                            feature.description + note,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(feature.enabledKey, it).apply()
                    })
                }
            }
            var lockDelay by remember { mutableFloatStateOf(prefs.getInt("lockDelaySec", 0).toFloat()) }
            Text("Lock delay: ${lockDelay.toInt()} s", fontWeight = FontWeight.Medium)
            Slider(
                value = lockDelay, onValueChange = { lockDelay = it },
                onValueChangeFinished = { prefs.edit().putInt("lockDelaySec", lockDelay.toInt()).apply() },
                valueRange = 0f..30f, steps = 29
            )
            var unlockDelay by remember { mutableFloatStateOf(prefs.getInt("unlockDelaySec", 0).toFloat()) }
            Text("Unlock delay: ${unlockDelay.toInt()} s", fontWeight = FontWeight.Medium)
            Slider(
                value = unlockDelay, onValueChange = { unlockDelay = it },
                onValueChangeFinished = { prefs.edit().putInt("unlockDelaySec", unlockDelay.toInt()).apply() },
                valueRange = 0f..10f, steps = 9
            )
            var restore by remember { mutableStateOf(prefs.getBoolean("restoreOriginal", false)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Restore original state on unlock", fontWeight = FontWeight.Medium)
                    Text(
                        "Instead of forcing ON, put each feature back to what it was before locking.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = restore, onCheckedChange = {
                    restore = it
                    prefs.edit().putBoolean("restoreOriginal", it).apply()
                })
            }
        }
    }
}
