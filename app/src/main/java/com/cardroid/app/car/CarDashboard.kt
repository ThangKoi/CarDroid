package com.cardroid.app.car

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DashboardButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) Car.Accent.copy(alpha = .16f) else Car.Surface)
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Car.Accent else Car.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun DashboardWidgets(apps: List<CarApp>, pinned: Set<String>, prefs: CarLauncherPrefs,
    onLaunch: (CarApp) -> Unit, onLongPress: (CarApp, Rect) -> Unit) {
    val widgets by prefs.widgets.collectAsState()
    val columns by prefs.columns.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your dashboard", color = Car.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Date, time and your everyday shortcuts", color = Car.TextDim, fontSize = 15.sp)
            }
            DashboardButton(if (editing) "Done" else "Customize", editing) { editing = !editing }
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                CarLauncherPrefs.WIDGETS.forEach { id -> DashboardButton(id.replaceFirstChar { it.uppercase() }, id in widgets) { prefs.toggleWidget(id) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                (1..3).forEach { count -> DashboardButton("$count column${if (count > 1) "s" else ""}", count == columns) { prefs.setColumns(count) } }
            }
        }
        if (widgets.isEmpty()) Text("Add widgets with Customize to make this page yours.", color = Car.TextDim, modifier = Modifier.padding(24.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(widgets, key = { it }) { id ->
                Column(Modifier.fillMaxWidth().heightIn(min = 176.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (id == "date") com.cardroid.app.CarDroidPalette.MintContainer else Car.Surface).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(id.uppercase(), color = Car.Accent, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        if (editing && widgets.indexOf(id) > 0) DashboardButton("Move earlier") { prefs.moveWidget(id) }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (id) {
                        "date" -> {
                            Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)), color = Car.TextDim, fontSize = 20.sp)
                            Text(SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(now)), color = Car.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                            Text(SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(now)), color = Car.TextDim, fontSize = 16.sp)
                        }
                        "clock" -> {
                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), color = Car.Text, fontSize = 52.sp, fontWeight = FontWeight.Light)
                            Text(java.util.TimeZone.getDefault().displayName, color = Car.TextDim, fontSize = 14.sp)
                        }
                        "favorites" -> {
                            val favorites = apps.filter { it.target in pinned }.take(4)
                            if (favorites.isEmpty()) Text("Long-press an app on the Apps page to add favorites.", color = Car.TextDim, fontSize = 16.sp)
                            favorites.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth().height(128.dp)) {
                                    row.forEach { app -> Box(Modifier.weight(1f)) { AppCell(app, true, false, onLaunch, onLongPress) } }
                                }
                            }
                        }
                        "connection" -> {
                            val available by CarDisplayState.tasksAvailable.collectAsState()
                            Text(if (available) "Car display ready" else "Launcher active", color = Car.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(if (available) "Recent apps are available from the dock." else "Open your apps from the Apps page.", color = Car.TextDim, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
