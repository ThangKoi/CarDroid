package com.cardroid.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cardroid.app.car.CarDisplaySection
import com.cardroid.app.car.CarDisplayState
import com.cardroid.lib.CarPlaySession
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val permissionRevision = MutableStateFlow(0)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Keep the Compose permission state in sync with the system dialog result.
        permissionRevision.value += 1
    }

    override fun onResume() {
        super.onResume()
        // Accessibility and WRITE_SETTINGS are granted from system settings; refresh
        // when the Activity returns to the foreground.
        permissionRevision.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarDroidSettings.initialize(applicationContext)
        CarDroidLog.attachFile(applicationContext)
        AutoStartReceiver.startBluetoothIfAllowed(this, "app opened")
        // Keep the platform bars aligned with the Compose cockpit palette even though
        // the legacy manifest theme still supplies the Activity shell.
        window.statusBarColor = android.graphics.Color.rgb(11, 16, 22)
        window.navigationBarColor = android.graphics.Color.rgb(11, 16, 22)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val revision by permissionRevision.collectAsState()
            val permissions = remember(revision) { PermissionSnapshot.from(this@MainActivity, requiredPermissions()) }

            CarDroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CarDroidScreen(
                        permissionSnapshot = permissions,
                        onRequestPerms = {
                            val missing = PermissionSnapshot.from(this@MainActivity, requiredPermissions()).missing
                            if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
                        },
                        onEnableTouch = {
                            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onShare = {
                            startService(Intent(this, CarDroidService::class.java).setAction(CarDroidService.ACTION_START_BT))
                        },
                        onStartBt = {
                            startService(Intent(this, CarDroidService::class.java).setAction(CarDroidService.ACTION_START_BT))
                        },
                        onStop = {
                            startService(Intent(this, CarDroidService::class.java).setAction(CarDroidService.ACTION_STOP))
                        },
                        onEnableBluetooth = {
                            runCatching {
                                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        },
                        bluetoothEnabled = { bluetoothEnabledSafely() },
                    )
                }
            }
        }
    }

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun bluetoothEnabledSafely(): Boolean {
        // isEnabled throws on Android 12+ until BLUETOOTH_CONNECT is granted.
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { btAdapter()?.isEnabled == true }.getOrDefault(false)
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.toTypedArray()
    }
}

private data class PermissionSnapshot(val missing: List<String>) {
    val allGranted: Boolean get() = missing.isEmpty()
    val bluetoothReady: Boolean get() = missing.none {
        it == Manifest.permission.BLUETOOTH_CONNECT ||
            it == Manifest.permission.BLUETOOTH_ADVERTISE ||
            it == Manifest.permission.BLUETOOTH_SCAN ||
            (Build.VERSION.SDK_INT < 31 && it == Manifest.permission.ACCESS_FINE_LOCATION)
    }

    companion object {
        fun from(activity: ComponentActivity, permissions: Array<String>): PermissionSnapshot = PermissionSnapshot(
            permissions.distinct().filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            },
        )
    }
}

private data class ConnectionStatus(val title: String, val detail: String)

private fun describeConnection(state: CarPlaySession.State): ConnectionStatus = when (state) {
    CarPlaySession.State.Idle -> ConnectionStatus("Connect to your car", "Open CarPlay on the car first")
    CarPlaySession.State.LinkUp -> ConnectionStatus("Connecting", "Link established")
    is CarPlaySession.State.MfiCertReceived -> ConnectionStatus("Connecting", "Checking the receiver")
    is CarPlaySession.State.Authenticating -> ConnectionStatus("Connecting", "Verifying the receiver")
    CarPlaySession.State.AuthComplete -> ConnectionStatus("Connecting", "Receiver accepted")
    CarPlaySession.State.Identified -> ConnectionStatus("Connecting", "Opening CarPlay")
    CarPlaySession.State.EaSessionOpen -> ConnectionStatus("Connecting", "CarPlay session open")
    CarPlaySession.State.WifiHandoffRequested -> ConnectionStatus("Connecting", "Waiting for Wi-Fi")
    is CarPlaySession.State.WifiHandoffReceived -> ConnectionStatus("Connecting", "Joining ${state.ssid}")
    is CarPlaySession.State.CarPlayAirPlayStart -> ConnectionStatus(
        if (state.endpoint.receiverName.isBlank()) "CarPlay is ready" else "${state.endpoint.receiverName} connected",
        "Car display starts automatically with the helper",
    )
    is CarPlaySession.State.Failed -> ConnectionStatus(
        "Couldn't connect",
        state.reason.ifBlank { "The receiver stopped the handshake" }.take(80),
    )
}

/** The single action a first-time user should take next. */
private data class NextStep(
    val title: String,
    val detail: String,
    val action: String,
    val kind: Kind,
) {
    enum class Kind { Permissions, Bluetooth, Connect, Connecting, Retry, Ready }
}

private fun nextStep(
    permissions: PermissionSnapshot,
    bluetoothOn: Boolean,
    state: CarPlaySession.State,
): NextStep = when {
    !permissions.allGranted -> NextStep(
        "Allow permissions",
        if (permissions.missing.size == 1) "1 permission needed" else "${permissions.missing.size} permissions needed",
        "Allow",
        NextStep.Kind.Permissions,
    )
    !bluetoothOn -> NextStep("Turn on Bluetooth", "Required to reach the car", "Turn on", NextStep.Kind.Bluetooth)
    state is CarPlaySession.State.Failed ->
        NextStep("Couldn't connect", state.reason.ifBlank { "The receiver stopped the handshake" }.take(80), "Try again", NextStep.Kind.Retry)
    state is CarPlaySession.State.CarPlayAirPlayStart ->
        NextStep("CarPlay is ready", "The helper starts the car launcher automatically", "Connect", NextStep.Kind.Ready)
    state == CarPlaySession.State.Idle ->
        NextStep("Connect to your car", "Open CarPlay on the car first", "Connect", NextStep.Kind.Connect)
    else -> describeConnection(state).let { NextStep(it.title, it.detail, "Cancel", NextStep.Kind.Connecting) }
}

private enum class Sheet { Car, Advanced }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarDroidScreen(
    permissionSnapshot: PermissionSnapshot,
    onRequestPerms: () -> Unit,
    onStartBt: () -> Unit,
    onShare: () -> Unit,
    onEnableTouch: () -> Unit,
    onStop: () -> Unit,
    onEnableBluetooth: () -> Unit,
    bluetoothEnabled: () -> Boolean,
) {
    val log by CarDroidLog.lines.collectAsState()
    val state by CarDroidLog.state.collectAsState()
    val useHardcodedKeys by CarDroidSettings.useHardcodedMfiKeys.collectAsState()
    val autoStartHelper by CarDroidSettings.autoStartHelperViaShizuku.collectAsState()
    val autoStartBluetooth by CarDroidSettings.autoStartBluetooth.collectAsState()
    val touchEnabled by RemoteControlService.enabled.collectAsState()
    val carDisplayId by CarDisplayState.displayId.collectAsState()
    val step = nextStep(permissionSnapshot, bluetoothEnabled(), state)
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val onAction: () -> Unit = when (step.kind) {
        NextStep.Kind.Permissions -> onRequestPerms
        NextStep.Kind.Bluetooth -> onEnableBluetooth
        NextStep.Kind.Connect, NextStep.Kind.Retry -> onStartBt
        NextStep.Kind.Connecting -> onStop
        NextStep.Kind.Ready -> onShare
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_cardroid_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(CarDroidPalette.IconBackground),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CarDroid", style = MaterialTheme.typography.headlineSmall)
                            Text(CarDroidName.forPhone(androidx.compose.ui.platform.LocalContext.current),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        PreviewPill()
                    }
                },
                actions = {
                    TextButton(onClick = { sheet = Sheet.Advanced }) { Text("Advanced") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepCard(
                    step = step,
                    connecting = step.kind == NextStep.Kind.Connecting,
                    onAction = onAction,
                    onDisconnect = onStop,
                )
                if (step.kind == NextStep.Kind.Ready) {
                    TouchCard(enabled = touchEnabled, onOpen = onEnableTouch)
                }
                CarDisplayRow(displayId = carDisplayId, onClick = { sheet = Sheet.Car })
            }
        }
    }

    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (sheet) {
                    Sheet.Car -> {
                        Text("Car display", style = MaterialTheme.typography.headlineSmall)
                        CarDisplaySection()
                    }
                    Sheet.Advanced -> AdvancedSheet(
                        log = log,
                        useHardcodedKeys = useHardcodedKeys,
                        onHardcodedKeys = { CarDroidSettings.setUseHardcodedMfiKeys(it) },
                        autoStartBluetooth = autoStartBluetooth,
                        onAutoStartBluetooth = { CarDroidSettings.setAutoStartBluetooth(it) },
                        autoStartHelper = autoStartHelper,
                        onAutoStartHelper = { CarDroidSettings.setAutoStartHelperViaShizuku(it) },
                        onClearLog = { CarDroidLog.clear() },
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: NextStep,
    connecting: Boolean,
    onAction: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val dot = when (step.kind) {
        NextStep.Kind.Retry, NextStep.Kind.Permissions -> MaterialTheme.colorScheme.error
        NextStep.Kind.Ready, NextStep.Kind.Connecting -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    ProductCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            StatusDot(dot)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (connecting) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(step.action, style = MaterialTheme.typography.titleSmall)
        }
        if (step.kind == NextStep.Kind.Ready) {
            TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
}

@Composable
private fun TouchCard(enabled: Boolean, onOpen: () -> Unit) {
    ProductCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Touch control", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "On" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { onOpen() })
        }
    }
}

@Composable
private fun CarDisplayRow(displayId: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (displayId >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Car display", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (displayId >= 0) "Ready" else "Set up apps on the car screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AdvancedSheet(
    log: List<String>,
    useHardcodedKeys: Boolean,
    onHardcodedKeys: (Boolean) -> Unit,
    autoStartBluetooth: Boolean,
    onAutoStartBluetooth: (Boolean) -> Unit,
    autoStartHelper: Boolean,
    onAutoStartHelper: (Boolean) -> Unit,
    onClearLog: () -> Unit,
) {
    Text("Advanced", style = MaterialTheme.typography.headlineSmall)
    SwitchRow("Extracted MFi keys", useHardcodedKeys, onHardcodedKeys)
    SwitchRow("Start Bluetooth on open", autoStartBluetooth, onAutoStartBluetooth)
    SwitchRow("Start helper with Shizuku", autoStartHelper, onAutoStartHelper)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClearLog, enabled = log.isNotEmpty()) { Text("Clear") }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(170.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (log.isEmpty()) {
                Text(
                    "No events yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                log.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewPill() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            "PREVIEW",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier.size(10.dp).semantics { contentDescription = "Status" },
    ) {}
}

@Composable
private fun ProductCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        content = { Column(modifier = Modifier.padding(18.dp), content = content) },
    )
}

private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
    Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertising"
    Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
    Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi‑Fi"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> permission.substringAfterLast('.')
}

@Suppress("unused")
private fun requireBluetoothPerm(activity: ComponentActivity): Boolean =
    Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
