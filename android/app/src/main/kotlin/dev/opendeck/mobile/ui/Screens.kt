package dev.opendeck.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.dp
import dev.opendeck.mobile.AppViewModel
import dev.opendeck.mobile.Screen
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

private const val NOTICE_MS = 5000L

@Composable
fun App(vm: AppViewModel) {
    val screen = vm.screen
    BackHandler(enabled = screen != Screen.Hosts) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Keypad is fullscreen: insets (e.g. the camera cutout in landscape) would push the grid off-centre.
        Box(Modifier.fillMaxSize().then(if (screen == Screen.Keypad) Modifier else Modifier.safeDrawingPadding())) {
            when (screen) {
                Screen.Hosts -> HostsScreen(vm)
                is Screen.Busy -> BusyScreen(screen.text, onCancel = vm::disconnect)
                is Screen.Pairing -> PairingScreen(vm, screen.waiting)
                Screen.Devices -> DevicesScreen(vm)
                Screen.Keypad -> KeypadScreen(vm)
            }
            val notice = vm.notice
            LaunchedEffect(notice) {
                if (notice != null) {
                    delay(NOTICE_MS)
                    vm.notice = null
                }
            }
            if (notice != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notice,
                            modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        TextButton(onClick = { vm.notice = null }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostsScreen(vm: AppViewModel) {
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    var showAdd by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isHome = remember { context.isDefaultHome() }
    val saved = vm.stored.hosts
    val online = vm.discovered.map { it.workstationID }.toSet()
    val extra = vm.discovered.filter { d -> saved.none { it.workstationID == d.workstationID } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("OpenDeck", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.scanning) "Searching the local network…" else "Not searching",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { vm.startDiscovery() }, enabled = !vm.scanning) { Text("Search again") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (saved.isEmpty() && extra.isEmpty()) {
                item {
                    Text(
                        "No computers yet. Make sure the Stream Deck app is running on a computer in the same network, " +
                            "or add one by address.",
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(saved, key = { "saved:" + it.workstationID }) { h ->
                val status = buildString {
                    append(if (h.workstationID in online) "Online" else "Saved")
                    if (h.isAuthenticated) append(" · paired")
                    append(" · ${h.devices.size} device(s)")
                }
                ListRow(h.name, status) { vm.connectSaved(h) }
            }
            items(extra, key = { "found:" + it.workstationID }) { d ->
                ListRow(d.name, "Discovered · ${d.endpoints.firstOrNull() ?: ""}") { vm.connectDiscovered(d) }
            }
        }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add host") }
        if (isHome) OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Apps") }
    }
    if (showApps) AppsDialog(onDismiss = { showApps = false })
    if (showAdd) {
        TextInputDialog(
            title = "Add host",
            label = "host[:port] or QR link",
            help = "Enter the computer's address (default port 28198), or paste the link encoded in the pairing QR code.",
            confirm = "Connect",
            onDismiss = { showAdd = false },
            onSubmit = { text -> vm.addHost(text).also { if (it == null) showAdd = false } },
        )
    }
}

internal fun Context.isDefaultHome(): Boolean =
    packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == packageName

/** App drawer for when OpenDeck is the home app, so the phone is never trapped in it. */
@Composable
internal fun AppsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo }
            .sortedBy { it.first.lowercase() }
    }
    fun launch(intent: Intent) {
        onDismiss()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Apps") },
        text = {
            LazyVerticalGrid(GridCells.Fixed(4)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListRow("Home app settings", "Switch back to another launcher") { launch(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }
                items(apps, key = { it.second.packageName + "/" + it.second.name }) { (label, info) ->
                    // Loaded per visible cell, so opening the drawer doesn't decode every icon up front.
                    val icon = remember(info) { info.loadIcon(pm).toBitmap(144, 144).asImageBitmap() }
                    Column(
                        Modifier
                            .clickable {
                                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.packageName, info.name))
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ListRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

/** onSubmit returns an error message to show, or null to accept. */
@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    help: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(help, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BusyScreen(text: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun PairingScreen(vm: AppViewModel, waiting: Boolean) {
    val hostName = vm.currentHost?.name ?: "computer"
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with $hostName", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                vm.pairingCode,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (waiting) {
            Text("Confirm code ${vm.pairingCode} on your computer.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.cancelPairing() }) { Text("Cancel") }
        } else {
            Text(
                "This computer has not approved this phone yet. Tap Pair, then accept the request in the Stream Deck app " +
                    "after checking that it shows the same code.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.pair() }) { Text("Pair") }
            TextButton(onClick = { vm.disconnect() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun DevicesScreen(vm: AppViewModel) {
    val host = vm.currentHost
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(host?.name ?: "", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        }
        Text("Virtual devices", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(host?.devices ?: emptyList(), key = { it.id }) { d ->
                ListRow(d.name.ifBlank { d.id }, "${d.cols} × ${d.rows} keys, ${d.iconSize} px icons") { vm.openDevice(d.id) }
            }
        }
        Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) { Text("New device") }
    }
    if (showNew) {
        TextInputDialog(
            title = "New device",
            label = "Name",
            help = "Creates another virtual Stream Deck on this computer.",
            confirm = "Create",
            onDismiss = { showNew = false },
            onSubmit = { name ->
                if (name.isBlank()) {
                    "Enter a name."
                } else {
                    vm.createDevice(name)
                    showNew = false
                    null
                }
            },
        )
    }
}
