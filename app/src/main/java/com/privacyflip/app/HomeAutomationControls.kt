package com.privacyflip.app

import android.content.Context
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

private fun iconFor(feature: Feature): ImageVector = when (feature) {
    Feature.WIFI -> Icons.Default.Wifi
    Feature.BLUETOOTH -> Icons.Default.Bluetooth
    Feature.LOCATION -> Icons.Default.LocationOn
    Feature.MOBILE_DATA -> Icons.Default.SignalCellularAlt
    Feature.NFC -> Icons.Default.Nfc
    Feature.CAMERA_PRIVACY -> Icons.Default.CameraAlt
    Feature.MIC_PRIVACY -> Icons.Default.MicOff
}

@Composable
internal fun LockUnlockCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("backendMode", "auto") ?: "auto") }
    var rootFound by remember { mutableStateOf(RootShell.cachedAvailable()) }
    var recheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(recheck) {
        rootFound = withContext(Dispatchers.IO) { RootShell.isAvailable(recheck > 0) }
    }
    val backend: AutomationBackend = when (mode) {
        "standard" -> StandardBackend
        "root" -> RootBackend
        else -> if (rootFound == true) RootBackend else StandardBackend
    }
    val rootText = when (rootFound) {
        true -> "root detected"
        false -> "root not found"
        null -> "checking root"
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lock / Unlock automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "One switch per feature. When locked: connectivity OFF, privacy ON. When unlocked: the reverse.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Mode: ${backend.name} · $rootText", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("auto" to "Auto", "standard" to "Standard", "root" to "Root").forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = {
                            mode = value
                            prefs.edit().putString("backendMode", value).apply()
                        },
                        label = { Text(label) }
                    )
                }
                TextButton(onClick = { recheck++ }) { Text("Re-check") }
            }
            if (!backend.supports(Feature.WIFI)) {
                Text(
                    "Standard Android cannot switch these settings. Switches are saved, but skipped and logged until Root is available (Shizuku support is planned).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                )
            }
            Feature.values().forEach { feature ->
                var enabled by remember { mutableStateOf(prefs.getBoolean(feature.enabledKey, false)) }
                val note = when {
                    !backend.supports(feature) -> " · needs Root"
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
