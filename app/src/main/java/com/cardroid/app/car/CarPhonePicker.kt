package com.cardroid.app.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardroid.app.helper.HelperClient
import com.cardroid.app.shizuku.ShizukuHelperStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone-side "Open on car display" section for MainActivity: enabled while
 * CarDisplayState.displayId >= 0; opens a searchable picker of CarApps.load()
 * and sends the choice through the helper.
 */
@Composable
fun CarDisplaySection(modifier: Modifier = Modifier) {
    val displayId by CarDisplayState.displayId.collectAsState()
    val lastError by CarDisplayState.lastError.collectAsState()
    val active = displayId >= 0
    var pickerOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        if (!active) {
            pickerOpen = false
            status = null
        }
    }

    Column(modifier.fillMaxWidth()) {
        ShizukuHelperSection()
        Spacer(Modifier.height(10.dp))
        CarSurface {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                    ).semantics { contentDescription = "Car display ${if (active) "active" else "inactive"}" },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Open on car display", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            !active -> "No car display yet"
                            lastError != null -> "Display $displayId · ${lastError}"
                            status != null -> status!!
                            else -> "Active on display $displayId"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { pickerOpen = true }, enabled = active, modifier = Modifier.fillMaxWidth()) {
                Text("Choose an app")
            }
        }
    }

    if (pickerOpen && active) {
        CarAppPickerDialog(
            onDismiss = { pickerOpen = false },
            onPick = { app ->
                pickerOpen = false
                status = if (CarDisplayState.launch(app.target)) "Sent ${app.label} to the car display." else "Could not reach the helper."
            },
        )
    }
}

@Composable
private fun ShizukuHelperSection() {
    val context = LocalContext.current
    val starter = remember(context) {
        ShizukuHelperStarter.get(context.applicationContext).also { it.initialize() }
    }
    val starterState by starter.state.collectAsState()
    val helperState by HelperClient.status.collectAsState()
    val helperRunning by starter.helperRunning.collectAsState()
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // Probe the real process on card show/resume and when Shizuku becomes usable; the starter rate-limits.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, starterState) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) starter.refreshHelperRunning()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        starter.refreshHelperRunning()
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val orphanRunning = helperRunning == true && helperState == HelperClient.State.NotRunning
    val connected = helperState is HelperClient.State.Connected || helperState is HelperClient.State.DisplayActive
    val (summary, action, enabled) = when (starterState) {
        ShizukuHelperStarter.State.Unavailable -> Triple("Shizuku is not running", "Open Shizuku", true)
        ShizukuHelperStarter.State.Unsupported -> Triple("This Shizuku API is too old", "Open Shizuku", true)
        ShizukuHelperStarter.State.NeedsPermission -> Triple("Shizuku permission is required", "Grant permission", true)
        ShizukuHelperStarter.State.PermissionDenied -> Triple("Permission denied; allow CarDroid in Shizuku", "Open Shizuku", true)
        ShizukuHelperStarter.State.Starting -> Triple("Starting or stopping the helper…", "Working…", false)
        is ShizukuHelperStarter.State.Started -> when (helperState) {
            HelperClient.State.Outdated -> Triple("Helper protocol is outdated; restart it", "Restart", true)
            HelperClient.State.NotRunning ->
                if (orphanRunning) Triple("Helper is running \u2014 tap Start Bluetooth to connect", "Stop", true)
                else Triple("Helper process is no longer running", "Start", true)
            HelperClient.State.Connecting -> Triple("Helper started; waiting for the phone client", "Stop", true)
            is HelperClient.State.Connected, is HelperClient.State.DisplayActive -> Triple("Helper is running; car display is available", "Stop", true)
        }
        ShizukuHelperStarter.State.Ready -> when (helperState) {
            is HelperClient.State.Connected, is HelperClient.State.DisplayActive -> Triple("Helper is running; car display is available", "Stop", true)
            HelperClient.State.Connecting -> Triple("Helper client is connecting", "Stop", true)
            HelperClient.State.Outdated -> Triple("Helper protocol is outdated; restart it", "Restart", true)
            HelperClient.State.NotRunning ->
                if (orphanRunning) Triple("Helper is running \u2014 tap Start Bluetooth to connect", "Stop", true)
                else Triple("Shizuku is ready; helper is stopped", "Start", true)
        }
        is ShizukuHelperStarter.State.Error -> Triple("Error: ${(starterState as ShizukuHelperStarter.State.Error).message}", "Retry", true)
    }

    CarSurface {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(
                    if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                ).semantics { contentDescription = "Phone helper ${if (connected) "connected" else "not connected"}" },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Phone helper · Shizuku", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = enabled,
            onClick = {
                when (starterState) {
                    ShizukuHelperStarter.State.Unavailable,
                    ShizukuHelperStarter.State.Unsupported,
                    ShizukuHelperStarter.State.PermissionDenied -> starter.openShizuku(context)
                    ShizukuHelperStarter.State.NeedsPermission -> starter.requestPermission()
                    ShizukuHelperStarter.State.Starting -> Unit
                    is ShizukuHelperStarter.State.Started -> when (helperState) {
                        HelperClient.State.Outdated -> starter.restart()
                        HelperClient.State.NotRunning -> if (orphanRunning) starter.stop() else starter.start()
                        HelperClient.State.Connecting,
                        is HelperClient.State.Connected, is HelperClient.State.DisplayActive -> starter.stop()
                    }
                    ShizukuHelperStarter.State.Ready -> when (helperState) {
                        HelperClient.State.Outdated -> starter.restart()
                        is HelperClient.State.Connected, is HelperClient.State.DisplayActive,
                        HelperClient.State.Connecting -> starter.stop()
                        HelperClient.State.NotRunning -> if (orphanRunning) starter.stop() else starter.start()
                    }
                    is ShizukuHelperStarter.State.Error -> starter.start()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(action) }
    }
}

@Composable
private fun CarSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
        modifier = Modifier.fillMaxWidth(),
        content = { Column(modifier = Modifier.padding(16.dp), content = content) },
    )
}

@Composable
private fun CarAppPickerDialog(onDismiss: () -> Unit, onPick: (CarApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<CarApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { CarApps.load(context) } }
    val shown = remember(apps, query) { CarLauncherLogic.search(apps.orEmpty(), { it.label }, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Open on car display") },
        text = {
            Column {
                Text("Pick an app to show on the car.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                    when {
                        apps == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
                        shown.isEmpty() -> Text("No matching apps", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn {
                            items(shown, key = { it.target }) { app -> PickerRow(app) { onPick(app) } }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PickerRow(app: CarApp, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { 36.dp.roundToPx() }
    val icon by rememberAppIcon(app.target, px)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp)) {
            icon?.let { Image(it, contentDescription = null, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(14.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
