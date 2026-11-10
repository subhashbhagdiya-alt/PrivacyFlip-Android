package com.privacyflip.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HomeFeature(
    val key: String,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val privacyDirection: Boolean = false
)

@Composable
fun HomeAutomationControls(
    modifier: Modifier,
    prefs: android.content.SharedPreferences,
    paused: Boolean,
    onPause: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val features = remember {
        listOf(
            HomeFeature("mobile_data", "Mobile Data", "Disable mobile data while the phone is locked", Icons.Default.NetworkCell),
            HomeFeature("wifi", "Wi-Fi", "Turn Wi-Fi off while locked", Icons.Default.Wifi),
            HomeFeature("bluetooth", "Bluetooth", "Turn Bluetooth off while locked", Icons.Default.Bluetooth),
            HomeFeature("location", "Location", "Turn location off while locked", Icons.Default.LocationOn),
            HomeFeature("nfc", "NFC", "Turn NFC off while locked (device support may vary)", Icons.Default.Nfc),
            HomeFeature("camera_privacy", "Camera", "Enable camera privacy while locked", Icons.Default.CameraAlt, true),
            HomeFeature("microphone_privacy", "Microphone", "Enable microphone privacy while locked", Icons.Default.Mic, true)
        )
    }
    var refresh by remember { mutableIntStateOf(0) }
    // Check root off the main thread; Shizuku is checked immediately and again
    // after returning to this screen. Root and Shizuku are alternative backends.
    var elevatedAccessAvailable by remember { mutableStateOf(ShizukuAutomation.hasPermission()) }
    LaunchedEffect(Unit) {
        elevatedAccessAvailable = withContext(Dispatchers.IO) {
            ShizukuAutomation.hasPermission() || RootAutomation.hasRootAccess()
        }
    }
    var lockDelay by remember { mutableFloatStateOf(prefs.getInt("privacy_flip_delay_lock_seconds", 10).toFloat()) }
    var unlockDelay by remember { mutableFloatStateOf(prefs.getInt("privacy_flip_delay_unlock_seconds", 1).toFloat()) }

    fun saveAutomation(feature: HomeFeature, checked: Boolean) {
        // One Home switch controls the complete lock/unlock pair.
        // Always persist both directions together so users never need to
        // configure a separate trigger or second switch.
        prefs.edit()
            .putBoolean("home_${feature.key}_disable_on_lock", checked)
            .putBoolean("home_${feature.key}_enable_on_unlock", checked)
            .apply()
        refresh++
        if (checked) ensureMonitor(context, prefs)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (paused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                        Icon(Icons.Default.Wifi, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Privacy Flip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (paused) "Protection Paused" else "Protection Active", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = !paused, onCheckedChange = { active ->
                        onPause(!active)
                        if (active) ensureMonitor(context, prefs)
                    })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Screen Lock/Unlock Actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Turn one switch ON to apply the lock action and automatically restore the original state on unlock. No separate rule or trigger is needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            if (!elevatedAccessAvailable) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ⓘ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(
                            "Root or Shizuku access is required to change protected system settings automatically. These switches stay disabled until elevated access is available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    features.forEachIndexed { index, feature ->
                        val disableOnLock = remember(refresh) {
                            prefs.getBoolean("home_${feature.key}_disable_on_lock", false)
                        }
                        val enableOnUnlock = remember(refresh) {
                            prefs.getBoolean("home_${feature.key}_enable_on_unlock", false)
                        }
                        val featureAvailable = when (feature.key) {
                            "nfc" -> context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
                            "camera_privacy", "microphone_privacy" -> Build.VERSION.SDK_INT >= 31
                            else -> true
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(feature.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(feature.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        when {
                                            !featureAvailable && feature.key == "nfc" -> "NFC hardware is not available on this device"
                                            !featureAvailable -> "Requires Android 12 or newer"
                                            else -> feature.description
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HomeSwitchRow(
                                "Lock/Unlock Automation",
                                elevatedAccessAvailable && (disableOnLock || enableOnUnlock),
                                featureAvailable && elevatedAccessAvailable
                            ) { enabled ->
                                saveAutomation(feature, enabled)
                            }
                            if (feature.privacyDirection) {
                                Text(
                                    "Privacy turns ON while locked and restores the saved original state on unlock.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index != features.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delays", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Configure timing for privacy actions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Delay after Lock (sec)", modifier = Modifier.weight(1f))
                            Text("${lockDelay.roundToInt()}s", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = lockDelay,
                            onValueChange = { lockDelay = it },
                            onValueChangeFinished = { prefs.edit().putInt("privacy_flip_delay_lock_seconds", lockDelay.roundToInt()).apply() },
                            valueRange = 0f..300f
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0s", style = MaterialTheme.typography.labelSmall)
                            Text("1m", style = MaterialTheme.typography.labelSmall)
                            Text("2m", style = MaterialTheme.typography.labelSmall)
                            Text("5m", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Delay after Unlock (sec)", modifier = Modifier.weight(1f))
                            Text("${unlockDelay.roundToInt()}s", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = unlockDelay,
                            onValueChange = { unlockDelay = it },
                            onValueChangeFinished = { prefs.edit().putInt("privacy_flip_delay_unlock_seconds", unlockDelay.roundToInt()).apply() },
                            valueRange = 0f..300f
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0s", style = MaterialTheme.typography.labelSmall)
                            Text("1m", style = MaterialTheme.typography.labelSmall)
                            Text("2m", style = MaterialTheme.typography.labelSmall)
                            Text("5m", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Changes are verified before the original setting is restored on unlock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeSwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * One-time upgrade for existing multi-action rules. Move legacy automatic
 * feature selections to the direct Home switches, then disable the old
 * automatic-only rule so it cannot execute a duplicate immediate action.
 */
internal fun migrateLegacyHomeActions(prefs: android.content.SharedPreferences): Boolean {
    if (prefs.getBoolean("home_controls_migrated_v1", false)) return false
    val raw = prefs.getString("rules", null)
    val rules = try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Exception) {
        return false
    }
    val actionMap = mapOf(
        "Auto: Mobile data OFF on lock / ON on unlock" to "mobile_data",
        "Auto: Wi-Fi OFF on lock / ON on unlock" to "wifi",
        "Auto: Bluetooth OFF on lock / ON on unlock" to "bluetooth",
        "Auto: Location OFF on lock / ON on unlock" to "location",
        "Auto: NFC OFF on lock / ON on unlock" to "nfc",
        "Auto: Camera privacy ON on lock / OFF on unlock" to "camera_privacy",
        "Auto: Microphone privacy ON on lock / OFF on unlock" to "microphone_privacy"
    )
    val migratedRules = JSONArray()
    var migratedAny = false
    for (index in 0 until rules.length()) {
        val rule = rules.optJSONObject(index) ?: continue
        val actions = rule.optString("action", "").split("|||").map { it.trim() }.filter { it.isNotEmpty() }
        val automatic = actions.mapNotNull { actionMap[it] }.distinct()
        if (automatic.isNotEmpty()) {
            automatic.forEach { option ->
                prefs.edit()
                    .putBoolean("home_${option}_disable_on_lock", true)
                    .putBoolean("home_${option}_enable_on_unlock", true)
                    .apply()
            }
            val remaining = actions.filterNot { actionMap.containsKey(it) }
            if (remaining.isEmpty()) {
                rule.put("action", "Log only (no system change)")
                rule.put("enabled", false)
            } else {
                rule.put("action", remaining.joinToString("|||"))
            }
            migratedAny = true
        }
        migratedRules.put(rule)
    }
    val editor = prefs.edit().putBoolean("home_controls_migrated_v1", true)
    if (migratedAny) editor.putString("rules", migratedRules.toString())
    editor.apply()
    return migratedAny
}

private fun ensureMonitor(context: Context, prefs: android.content.SharedPreferences) {
    if (prefs.getBoolean("screen_monitor_enabled", false) &&
        prefs.getBoolean("monitor_service_running", false)
    ) return
    runCatching {
        val intent = Intent(context, ScreenMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        prefs.edit().putBoolean("screen_monitor_enabled", true).apply()
    }.onFailure {
        Toast.makeText(context, "Could not start screen monitoring: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
    }
}
