package com.leatherbound.listening.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.ThemeMode
import com.leatherbound.listening.data.db.SourceEntity
import com.leatherbound.listening.data.db.SourceType
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.ConnectState
import com.leatherbound.listening.ui.components.Ornament
import com.leatherbound.listening.ui.components.paper
import java.text.DateFormat
import java.util.Date

private enum class Dialog { NONE, PLEX, JELLYFIN }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: com.leatherbound.listening.ui.Navigator) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val connect by vm.connect.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var sourcesAtOpen by remember { mutableStateOf(0) }
    var confirmRemove by remember { mutableStateOf<SourceEntity?>(null) }
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addLocalFolder) }

    Column(
        Modifier.fillMaxSize().paper().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp))
            Spacer(Modifier.height(12.dp))

            Ornament("Libraries")
            sources.forEach { s ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(when (s.type) { SourceType.PLEX -> Icons.Filled.Storage; SourceType.JELLYFIN -> Icons.Filled.Cloud; SourceType.LOCAL -> Icons.Filled.Folder }, null)
                    },
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Column {
                            Text(
                                if (s.lastSyncedAt > 0) "Synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncedAt))}" else "Not yet synced",
                                fontStyle = FontStyle.Italic,
                            )
                            if (s.type != SourceType.LOCAL) Text(connectionLabel(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingContent = { IconButton(onClick = { confirmRemove = s }) { Icon(Icons.Filled.Delete, "Remove ${s.name}") } },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { sourcesAtOpen = sources.size; dialog = Dialog.PLEX }) { Text("Add Plex") }
                OutlinedButton(onClick = { sourcesAtOpen = sources.size; dialog = Dialog.JELLYFIN }) { Text("Add Jellyfin") }
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
                if (sources.isNotEmpty()) OutlinedButton(onClick = { vm.sync() }, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Text("  Sync now")
                }
            }

            Spacer(Modifier.height(12.dp))
            Ornament("Listening")
            Text("Rewind on resume: ${settings.resumeRewindSeconds} s", style = MaterialTheme.typography.bodyLarge)
            Text("Pressing play after a pause — or opening the app — steps back this far.", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.resumeRewindSeconds.toFloat(),
                onValueChange = { vm.setResumeRewind(it.toInt()) },
                valueRange = 0f..30f, steps = 29,
            )
            Toggle("Smart rewind", "Step back less after a short pause, more after a long one (up to 30 s after a day away)", settings.smartRewind, vm::setSmartRewind)
            Text("Default speed: ${"%.2f".format(settings.playbackSpeed).trimEnd('0').trimEnd('.')}×", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Text("Books remember their own speed once you change it in the player.", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = settings.playbackSpeed, onValueChange = { vm.setDefaultSpeed((it * 20).toInt() / 20f) }, valueRange = 0.75f..2.5f, steps = 34)
            Toggle("Skip silence", "Trims pauses between sentences", settings.skipSilence, vm::setSkipSilence)
            Toggle("Voice boost", "Makes quiet narrators louder, for the car or the street", settings.voiceBoost, vm::setVoiceBoost)
            Toggle("Mark books finished automatically", "When playback reaches the end", settings.autoMarkFinished, vm::setAutoMarkFinished)
            Toggle("Play the next book in the series", "Uses Plex/Jellyfin collections, then your Up Next list", settings.autoQueueSeries, vm::setAutoQueueSeries)
            Toggle("Remove finished books from Up Next", null, settings.removeFinishedFromList, vm::setRemoveFinishedFromList)

            Spacer(Modifier.height(12.dp))
            Ornament("Downloads")
            Toggle("Download on Wi-Fi only", null, settings.downloadOnWifiOnly, vm::setWifiOnly)
            Toggle("Download the next book automatically", "When you're 80% through a book, download the next one in its series (or Up Next). Only that one book.", settings.autoDownloadNext, vm::setAutoDownloadNext)
            Toggle("Delete downloads of finished books", null, settings.deleteDownloadWhenFinished, vm::setDeleteWhenFinished)
            OutlinedButton(onClick = { nav.downloads() }, modifier = Modifier.padding(vertical = 4.dp)) { Text("Manage downloads") }

            Spacer(Modifier.height(12.dp))
            Ornament("Appearance")
            var editName by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { editName = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your name", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        settings.displayName.ifBlank { "Not set" } + if (settings.customName.isBlank() && settings.accountName.isNotBlank()) " · from your account" else "",
                        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editName = true }) { Text("Change") }
            }
            if (editName) {
                var name by remember { mutableStateOf(settings.customName) }
                AlertDialog(
                    onDismissRequest = { editName = false },
                    title = { Text("Your name") },
                    text = {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            placeholder = { Text(settings.accountName.ifBlank { "Shown in the greeting" }) },
                            supportingText = { if (settings.accountName.isNotBlank()) Text("Leave empty to use “${settings.accountName}” from your account") },
                        )
                    },
                    confirmButton = { TextButton(onClick = { vm.setCustomName(name); editName = false }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Parchment"; ThemeMode.DARK -> "Leather" }) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Text(
                "Leatherbound Listening · set in Libre Baskerville",
                style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    when (dialog) {
        Dialog.PLEX -> PlexDialog(vm, connect, onDismiss = { dialog = Dialog.NONE; vm.cancelConnect() }) {
            context.startActivity(AppViewModel.browserIntent(it))
        }
        Dialog.JELLYFIN -> JellyfinDialog(vm, connect, onDismiss = { dialog = Dialog.NONE; vm.cancelConnect() })
        Dialog.NONE -> if (connect is ConnectState.Working) WorkingDialog((connect as ConnectState.Working).message)
    }
    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${s.name}?") },
            text = {
                Text(
                    if (s.type == SourceType.LOCAL) "Its books, listening progress and Up Next entries will be removed from the app. The audio files in the folder are not touched."
                    else "Its books, downloads, listening progress and Up Next entries will be removed from this device. Nothing changes on the server."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeSource(s); confirmRemove = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    // Close the dialog once a library has been added.
    LaunchedEffect(sources.size, connect) {
        if (dialog != Dialog.NONE && connect == ConnectState.Idle && sources.size > sourcesAtOpen) dialog = Dialog.NONE
    }
}

/** e.g. "Local · 192.168.1.10:32400" or "Plex relay (limited speed)". */
private fun connectionLabel(s: com.leatherbound.listening.data.db.SourceEntity): String {
    val host = android.net.Uri.parse(s.baseUrl).let { "${it.host}${if (it.port > 0) ":${it.port}" else ""}" }
    val relay = s.type == SourceType.PLEX && s.baseUrl.endsWith(":8443") && s.baseUrl.contains("plex.direct")
    val local = Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.)""").containsMatchIn(host.replace('-', '.'))
    return when {
        relay -> "Plex relay (limited speed) · $host"
        local -> "Local network · $host"
        else -> "Remote · $host"
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun WorkingDialog(message: String) {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.size(16.dp)); Text(message)
        }
    })
}

/** Shared tail of both connect flows: progress, errors, and picking a library. */
@Composable
private fun ConnectProgress(vm: AppViewModel, state: ConnectState): Boolean {
    when (state) {
        is ConnectState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.size(16.dp)); Text(state.message)
        }
        is ConnectState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is ConnectState.PickLibrary -> Column {
            Text("Choose a library", style = MaterialTheme.typography.titleMedium)
            state.libraries.forEach { lib ->
                Text(lib.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { vm.chooseLibrary(state.pending, lib) }.padding(vertical = 12.dp))
                HorizontalDivider()
            }
        }
        is ConnectState.PickPlexServer -> Column {
            Text("Choose a server", style = MaterialTheme.typography.titleMedium)
            state.servers.forEach { server ->
                Column(Modifier.fillMaxWidth().clickable { vm.choosePlexServer(server) }.padding(vertical = 12.dp)) {
                    Text(server.name, style = MaterialTheme.typography.bodyLarge)
                    Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        ConnectState.Idle -> return false
    }
    return true
}

@Composable
private fun PlexDialog(vm: AppViewModel, state: ConnectState, onDismiss: () -> Unit, openUrl: (String) -> Unit) {
    var manual by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Plex") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val busy = state !is ConnectState.Idle && state !is ConnectState.Failed
                if (!busy) {
                    if (!manual) {
                        Text("Sign in with your Plex account in the browser. Audiobooks should live in a Music library (author = artist, book = album).")
                        TextButton(onClick = { manual = true }) { Text("Enter server address and token instead") }
                    } else {
                        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.10:32400") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(token, { token = it }, label = { Text("X-Plex-Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                }
                ConnectProgress(vm, state)
            }
        },
        confirmButton = {
            if (state is ConnectState.Idle || state is ConnectState.Failed) {
                TextButton(onClick = {
                    if (manual) vm.connectPlexManually(url, token) else vm.signInToPlex(openUrl)
                }, enabled = !manual || (url.isNotBlank() && token.isNotBlank())) { Text(if (manual) "Connect" else "Sign in with Plex") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JellyfinDialog(vm: AppViewModel, state: ConnectState, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Jellyfin") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state is ConnectState.Idle || state is ConnectState.Failed) {
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.10:8096") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                ConnectProgress(vm, state)
            }
        },
        confirmButton = {
            if (state is ConnectState.Idle || state is ConnectState.Failed) {
                TextButton(onClick = { vm.connectJellyfin(url, user, password) }, enabled = url.isNotBlank() && user.isNotBlank()) { Text("Sign in") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
