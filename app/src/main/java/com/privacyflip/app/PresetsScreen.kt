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
internal fun PresetsPage(
    presets: List<Preset>, store: LocalStore, category: String, onCategory: (String) -> Unit,
    search: String, onSearch: (String) -> Unit, language: String
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(), label = { Text(if (language == "hi") "Preset khojein" else "Search presets") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All") .plus(categories).take(3).forEach { cat ->
                FilterChip(selected = category == cat, onClick = { onCategory(cat) }, label = { Text(cat, maxLines = 1) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.drop(2).forEach { cat ->
                FilterChip(selected = category == cat, onClick = { onCategory(cat) }, label = { Text(cat, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val filtered = presets.filter {
            (category == "All" || it.category == category) &&
                (search.isBlank() || it.title.contains(search, true) || it.category.contains(search, true) || it.trigger.contains(search, true))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(filtered, key = { it.id }) { preset ->
                PresetCard(preset, store)
            }
        }
    }
}

@Composable
internal fun PresetCard(preset: Preset, store: LocalStore) {
    var expanded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(store.getBool("preset_${preset.id}", false)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.title, fontWeight = FontWeight.SemiBold)
                    Text(preset.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    store.setBool("preset_${preset.id}", it)
                    if (it) expanded = true
                })
            }
            Text(preset.trigger, style = MaterialTheme.typography.bodyMedium)
            Text(preset.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled) Text("Turn on the actions you want below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide actions" else "Configure actions")
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                preset.actions.forEach { action ->
                    var actionEnabled by remember { mutableStateOf(store.getBool("action_${preset.id}_${action.id}", false)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(action.title, fontWeight = FontWeight.Medium)
                            Text(action.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = actionEnabled, onCheckedChange = {
                            actionEnabled = it
                            store.setBool("action_${preset.id}_${action.id}", it)
                        })
                    }
                }
            }
        }
    }
}

