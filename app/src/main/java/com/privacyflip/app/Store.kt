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

internal enum class Page { HOME, PRESETS, RULES, DIAGNOSTICS, SETTINGS }

internal data class CustomRule(val id: String, val name: String, val trigger: String, val action: String, val enabled: Boolean, val scheduledTime: String = "09:00")

internal val customTriggers = TriggerCatalog.all
internal val customActions = Actions.all.map { it.label }
internal val supportedCustomTriggers = customTriggers.toSet()
internal val supportedCustomActions = customActions.toSet()
internal val categories = PresetCatalog.categories
internal fun makePresets(): List<Preset> = PresetCatalog.all

internal class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE)
    fun getBool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun getString(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun setString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun getInt(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun setInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    fun getEventLogs(): List<String> = try {
        val a = JSONArray(prefs.getString("eventLog", "[]"))
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { value -> value.isNotBlank() } }
    } catch (_: Exception) { emptyList() }
    fun getCustomRules(): List<CustomRule> = try {
        val array = JSONArray(prefs.getString("customRules", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            CustomRule(o.optString("id"), o.optString("name"), o.optString("trigger"), o.optString("action"), o.optBoolean("enabled"), o.optString("scheduledTime", "09:00").takeIf { it.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) } ?: "09:00")
        }
    } catch (_: Exception) { emptyList() }
    fun saveCustomRules(rules: List<CustomRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("trigger", rule.trigger).put("action", rule.action).put("enabled", rule.enabled).put("scheduledTime", rule.scheduledTime))
        }
        prefs.edit().putString("customRules", array.toString()).apply()
    }
    fun exportJson(presets: List<Preset>): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("app", "Privacy Flip")
        root.put("darkTheme", getBool("darkTheme", true))
        root.put("language", getString("language", "hi"))
        root.put("masterPaused", getBool("masterPaused", false))
        root.put("monitorEnabled", getBool("monitorEnabled", true))
        root.put("batteryThreshold", getInt("batteryThreshold", 20))
        val array = JSONArray()
        presets.forEach { p ->
            val item = JSONObject()
            item.put("id", p.id)
            item.put("enabled", getBool("preset_${p.id}", false))
            val actions = JSONObject()
            p.actions.forEach { a -> actions.put(a.id, getBool("action_${p.id}_${a.id}", false)) }
            item.put("actions", actions)
            array.put(item)
        }
        root.put("presets", array)
        val customArray = JSONArray()
        getCustomRules().forEach { rule ->
            customArray.put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("trigger", rule.trigger).put("action", rule.action).put("enabled", rule.enabled).put("scheduledTime", rule.scheduledTime))
        }
        root.put("customRules", customArray)
        return root.toString(2)
    }
    fun importJson(json: String, presets: List<Preset>): Boolean {
        return try {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion", -1) != 1) return false
        setBool("darkTheme", root.optBoolean("darkTheme", true))
        setString("language", root.optString("language", "hi"))
        setBool("masterPaused", root.optBoolean("masterPaused", false))
        setBool("monitorEnabled", root.optBoolean("monitorEnabled", true))
        setInt("batteryThreshold", root.optInt("batteryThreshold", 20).coerceIn(5, 50))
        val customArray = root.optJSONArray("customRules") ?: JSONArray()
        val importedRules = mutableListOf<CustomRule>()
        for (i in 0 until customArray.length()) {
            val o = customArray.optJSONObject(i) ?: continue
            val trigger = o.optString("trigger")
            val action = o.optString("action")
            if (trigger !in supportedCustomTriggers || action !in supportedCustomActions) continue
            importedRules += CustomRule(o.optString("id").ifBlank { "rule_${System.currentTimeMillis()}_$i" },
                o.optString("name", "Custom rule"), trigger, action, o.optBoolean("enabled", false), o.optString("scheduledTime", "09:00").takeIf { it.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) } ?: "09:00")
        }
        saveCustomRules(importedRules)
        val arr = root.optJSONArray("presets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (presets.none { it.id == id }) continue
            setBool("preset_$id", item.optBoolean("enabled", false))
            val actions = item.optJSONObject("actions") ?: JSONObject()
            presets.firstOrNull { it.id == id }?.actions?.forEach { a ->
                setBool("action_${id}_${a.id}", actions.optBoolean(a.id, false))
            }
        }
        true
        } catch (_: Exception) { false }
    }
}

