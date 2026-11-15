package com.privacyflip.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.clickable
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

private const val PREFS_NAME = "privacy_flip_local"

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

/** What the Home screen needs to know about the backend, computed once per screen. */
internal class BackendUi(
    val backend: AutomationBackend,
    val shizuku: String,
    val rootFound: Boolean?,
    val mode: String,
    val setMode: (String) -> Unit,
    val recheck: () -> Unit
) {
    /** True when Shizuku or Root can really change the settings. */
    val ready: Boolean get() = backend.supports(Feature.WIFI)
}

@Composable
internal fun rememberBackendUi(): BackendUi {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
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
    return BackendUi(
        backend = backend,
        shizuku = shizuku,
        rootFound = rootFound,
        mode = mode,
        setMode = { value ->
            mode = value
            prefs.edit().putString("backendMode", value).apply()
        },
        recheck = { recheck++ }
    )
}

/** The whole Home content: status, connect help (only when needed), features, advanced. */
@Composable
internal fun LockUnlockCard() {
    val ui = rememberBackendUi()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusCard(ui)
        if (!ui.ready) ConnectCard(ui)
        FeaturesCard(ui)
        AdvancedCard(ui)
    }
}

@Composable
private fun StatusCard(ui: BackendUi) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var paused by remember { mutableStateOf(prefs.getBoolean("masterPaused", false)) }
    val active = ui.ready && !paused
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Privacy Flip", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (paused) "Paused" else "Protection on", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = !paused, onCheckedChange = {
                    paused = !it
                    prefs.edit().putBoolean("masterPaused", paused).apply()
                    AutomationScheduler.scheduleNext(context)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ui.ready) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                    tint = if (ui.ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.ready) "Connected · ${ui.backend.name}" else "Not connected",
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ConnectCard(ui: BackendUi) {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ui.shizuku) {
                "needs permission" -> {
                    Text("Shizuku is running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Allow Privacy Flip to use it. This takes one tap.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { ShizukuBridge.requestPermission() }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
                }
                "checking" -> Text("Checking connection…", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("Connect Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "1. Install Shizuku\n2. Turn on Wireless debugging (Settings > Developer options)\n3. Open Shizuku and tap Start",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { openShizuku(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Shizuku") }
                    Text(
                        "Rooted phone? Open Advanced below and choose Root.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = ui.recheck) { Text("Check again") }
        }
    }
}

@Composable
private fun FeaturesCard(ui: BackendUi) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Lock and unlock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Connections turn off when you lock and back on when you unlock. Camera and microphone protection does the reverse.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Feature.values().forEach { feature ->
                val result = remember(tick) { prefs.getString("pf_result_${feature.key}", null) }
                var enabled by remember { mutableStateOf(prefs.getBoolean(feature.enabledKey, false)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        iconFor(feature), null,
                        tint = if (ui.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(feature.title, fontWeight = FontWeight.Medium)
                        Text(
                            result ?: if (feature.lockTarget) "Protected while locked" else "Off while locked",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.edit().putBoolean(feature.enabledKey, it).apply()
                        },
                        enabled = ui.ready
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun AdvancedCard(ui: BackendUi) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (open) {
                Text("Method", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip(ui.mode == "auto", "Auto") { ui.setMode("auto") }
                    ModeChip(ui.mode == "shizuku", "Shizuku") { ui.setMode("shizuku") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip(ui.mode == "root", "Root") { ui.setMode("root") }
                    ModeChip(ui.mode == "standard", "None") { ui.setMode("standard") }
                }
                val rootText = when (ui.rootFound) {
                    true -> "found"
                    false -> "not found"
                    null -> "checking"
                }
                Text(
                    "Auto uses Shizuku first, then Root. Shizuku: ${ui.shizuku}. Root: $rootText.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var lockDelay by remember { mutableFloatStateOf(prefs.getInt("lockDelaySec", 0).toFloat()) }
                Text("Wait after locking: ${lockDelay.toInt()} s", fontWeight = FontWeight.Medium)
                Slider(
                    value = lockDelay, onValueChange = { lockDelay = it },
                    onValueChangeFinished = { prefs.edit().putInt("lockDelaySec", lockDelay.toInt()).apply() },
                    valueRange = 0f..30f, steps = 29
                )
                var unlockDelay by remember { mutableFloatStateOf(prefs.getInt("unlockDelaySec", 0).toFloat()) }
                Text("Wait after unlocking: ${unlockDelay.toInt()} s", fontWeight = FontWeight.Medium)
                Slider(
                    value = unlockDelay, onValueChange = { unlockDelay = it },
                    onValueChangeFinished = { prefs.edit().putInt("unlockDelaySec", unlockDelay.toInt()).apply() },
                    valueRange = 0f..10f, steps = 9
                )
                var restore by remember { mutableStateOf(prefs.getBoolean("restoreOriginal", false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Restore what it was", fontWeight = FontWeight.Medium)
                        Text(
                            "On unlock, put each feature back the way it was before locking.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = restore, onCheckedChange = {
                        restore = it
                        prefs.edit().putBoolean("restoreOriginal", it).apply()
                    })
                }

                Text("Test", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { LockUnlockEngine.testNow(context, true) }, modifier = Modifier.weight(1f)) { Text("Test lock") }
                    OutlinedButton(onClick = { LockUnlockEngine.testNow(context, false) }, modifier = Modifier.weight(1f)) { Text("Test unlock") }
                }
                Text(
                    "Runs your switched-on features right now. The result shows under each one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val lastEvent = remember(open) { prefs.getString("lastScreenEvent", null) ?: "none yet" }
                Text(
                    "Last screen event seen: $lastEvent",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "If this says none yet after you lock the phone, set battery to Unrestricted in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
