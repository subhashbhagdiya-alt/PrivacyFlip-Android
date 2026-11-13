package com.privacyflip.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.TelephonyManager
import java.util.concurrent.TimeUnit

/** A setting that follows the phone's lock state. One switch controls both lock and unlock. */
internal enum class Feature(
    val key: String,
    val title: String,
    val description: String,
    /** State wanted while locked: true = ON (privacy protection enabled), false = OFF (radio off). */
    val lockTarget: Boolean
) {
    WIFI("wifi", "Wi-Fi", "Off while locked, on after unlock", false),
    BLUETOOTH("bluetooth", "Bluetooth", "Off while locked, on after unlock", false),
    LOCATION("location", "Location", "Off while locked, on after unlock", false),
    MOBILE_DATA("mobile_data", "Mobile data", "Off while locked, on after unlock", false),
    NFC("nfc", "NFC", "Off while locked, on after unlock", false),
    CAMERA_PRIVACY("camera_privacy", "Camera privacy", "Blocked while locked, allowed after unlock", true),
    MIC_PRIVACY("mic_privacy", "Microphone privacy", "Blocked while locked, allowed after unlock", true);

    val unlockTarget: Boolean get() = !lockTarget
    val enabledKey: String get() = "pf_$key"
    val originalKey: String get() = "pf_orig_$key"
    val pendingKey: String get() = "pf_pending_$key"
}

/** Reads the real system state with normal Android APIs. Returns null when Android will not say. */
internal object SystemState {
    @Suppress("DEPRECATION")
    fun read(context: Context, feature: Feature): Boolean? = try {
        val app = context.applicationContext
        when (feature) {
            Feature.WIFI -> (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            Feature.BLUETOOTH -> BluetoothAdapter.getDefaultAdapter()?.isEnabled
            Feature.LOCATION ->
                if (Build.VERSION.SDK_INT >= 28) (app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled
                else null
            Feature.MOBILE_DATA -> (app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.isDataEnabled
            Feature.NFC -> NfcAdapter.getDefaultAdapter(app)?.isEnabled
            Feature.CAMERA_PRIVACY, Feature.MIC_PRIVACY -> null
        }
    } catch (_: Exception) {
        null
    }
}

internal interface AutomationBackend {
    val name: String
    fun supports(feature: Feature): Boolean

    /** Returns null when the command was accepted, otherwise a short failure reason. */
    fun apply(feature: Feature, on: Boolean): String?
}

/** No root: Android does not let normal apps change these settings, so nothing is claimed. */
internal object StandardBackend : AutomationBackend {
    override val name = "Standard (no root)"
    override fun supports(feature: Feature) = false
    override fun apply(feature: Feature, on: Boolean): String? =
        "Android does not let normal apps change this; needs Root or Shizuku"
}

internal data class ShellResult(val exitCode: Int, val output: String)

internal object RootShell {
    @Volatile
    private var cached: Boolean? = null

    fun cachedAvailable(): Boolean? = cached

    /** Blocking: call from a background thread. */
    fun isAvailable(force: Boolean = false): Boolean {
        if (!force) cached?.let { return it }
        val result = run("id", 5000L)
        val ok = result.exitCode == 0 && result.output.contains("uid=0")
        cached = ok
        return ok
    }

    /** Only fixed commands built from the Feature allow-list are ever passed in. */
    fun run(command: String, timeoutMs: Long = 4000L): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                } catch (_: Exception) {
                    // Stream closed when the process ends.
                }
            }
            reader.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return ShellResult(-1, "timeout")
            }
            reader.join(500)
            ShellResult(process.exitValue(), output.toString().trim())
        } catch (e: Exception) {
            ShellResult(-1, e.javaClass.simpleName)
        }
    }
}

internal object RootBackend : AutomationBackend {
    override val name = "Root"
    override fun supports(feature: Feature) = true

    override fun apply(feature: Feature, on: Boolean): String? {
        val result = RootShell.run(command(feature, on))
        if (result.exitCode == 0) return null
        val detail = result.output.take(80)
        return "command failed (exit ${result.exitCode}${if (detail.isNotBlank()) ": $detail" else ""})"
    }

    private fun command(feature: Feature, on: Boolean): String {
        val word = if (on) "enable" else "disable"
        return when (feature) {
            Feature.WIFI -> "svc wifi $word"
            Feature.BLUETOOTH -> "svc bluetooth $word"
            Feature.MOBILE_DATA -> "svc data $word"
            Feature.NFC -> "svc nfc $word"
            Feature.LOCATION ->
                if (on) "cmd location set-location-enabled true || settings put secure location_mode 3"
                else "cmd location set-location-enabled false || settings put secure location_mode 0"
            // For privacy features "on" means the sensor is blocked.
            Feature.CAMERA_PRIVACY -> "cmd sensor_privacy $word 0 camera"
            Feature.MIC_PRIVACY -> "cmd sensor_privacy $word 0 microphone"
        }
    }
}

internal object Backends {
    private const val PREFS = "privacy_flip_local"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("backendMode", "auto") ?: "auto"

    /** [probe] may run `su`, so only pass true from a background thread. */
    fun current(context: Context, probe: Boolean): AutomationBackend = when (mode(context)) {
        "standard" -> StandardBackend
        "root" -> RootBackend
        else -> {
            val root = if (probe) RootShell.isAvailable() else RootShell.cachedAvailable() == true
            if (root) RootBackend else StandardBackend
        }
    }
}
