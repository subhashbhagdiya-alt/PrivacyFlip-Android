package com.privacyflip.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Checks every link of the chain (service, events, Shizuku, Root, state reading) and writes it as plain text. */
internal object SystemReport {
    private const val PREFS = "privacy_flip_local"

    /** Blocking: talks to Shizuku and may run su, so call it from a background thread. */
    @Suppress("DEPRECATION")
    fun build(context: Context): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = StringBuilder()
        fun line(text: String) {
            out.append(text).append('\n')
        }

        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        line("Privacy Flip report")
        line("App $version | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
        line(
            "Protection: ${if (prefs.getBoolean("masterPaused", false)) "PAUSED" else "active"} | mode: ${Backends.mode(app)} | " +
                "monitor switch: ${if (prefs.getBoolean("monitorEnabled", true)) "on" else "OFF"}"
        )
        line("Monitor service running: ${if (isMonitorRunning(app)) "yes" else "NO"}")
        line("Last screen-off event seen: ${seen(prefs, "Screen turns off")}")
        line("Last screen-on event seen: ${seen(prefs, "Screen turns on")}")
        line("Last unlock event seen: ${seen(prefs, "Phone unlocked")}")

        val installed = try {
            app.packageManager.getPackageInfo(ShizukuBridge.PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        line("Shizuku app installed: ${yesNo(installed)}")
        line(
            "Shizuku running: ${yesNo(ShizukuBridge.isRunning())} | permission: ${yesNo(ShizukuBridge.hasPermission())} | " +
                ShizukuBridge.describe()
        )
        line("Shizuku service: ${ShizukuBridge.connectionTest() ?: "connected"}")
        line("Root: ${if (RootShell.isAvailable()) "available" else "not found"}")

        val enabled = Feature.values().filter { prefs.getBoolean(it.enabledKey, false) }.joinToString { it.title }
        line("Features switched on: ${enabled.ifEmpty { "none" }}")
        line("Current state: " + Feature.values().joinToString(", ") { "${it.title} ${stateText(SystemState.read(app, it))}" })
        line("Backend in use: ${Backends.current(app, true).name}")

        val notifications = Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(app.packageName) == true
        line("Notifications: ${if (notifications) "allowed" else "blocked"} | battery: ${if (battery) "unrestricted" else "RESTRICTED"}")

        line("Last activity:")
        val logs = readActivityLog(app).take(8)
        if (logs.isEmpty()) line(" - none") else logs.forEach { line(" - $it") }
        return out.toString().trimEnd()
    }

    @Suppress("DEPRECATION")
    private fun isMonitorRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == MonitorService::class.java.name }
    }

    private fun seen(prefs: SharedPreferences, event: String): String {
        val at = prefs.getLong("seen_$event", 0L)
        return if (at == 0L) "never" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun stateText(value: Boolean?) = when (value) {
        true -> "ON"
        false -> "OFF"
        null -> "unknown"
    }
}

@Composable
internal fun SystemCheckCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check system", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Finds out which step is failing. Tap Copy and paste the result to get help.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    running = true
                    copied = false
                    scope.launch {
                        report = withContext(Dispatchers.IO) {
                            try {
                                SystemReport.build(context)
                            } catch (e: Exception) {
                                "Report failed: ${e.javaClass.simpleName}"
                            }
                        }
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (running) "Checking..." else "Check system") }
            if (report.isNotBlank()) {
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(report))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (copied) "Copied" else "Copy report") }
            }
        }
    }
}
