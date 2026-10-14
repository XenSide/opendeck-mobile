package dev.opendeck.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.opendeck.core.Keypad
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.opendeck.core.Layout
import dev.opendeck.mobile.AppViewModel
import dev.opendeck.mobile.Duotones
import dev.opendeck.mobile.StoredDevice

@Composable
fun KeypadScreen(vm: AppViewModel) {
    val device = vm.currentDevice ?: return
    val layout = device.layout
    var showLayout by remember { mutableStateOf(false) }
    var showBar by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isHome = remember { context.isDefaultHome() }
    // Written on touch, read only inside PowerSaveBrightness so a tap doesn't recompose the grid.
    val wake = remember { mutableIntStateOf(0) }
    ImmersiveKeepAwake()
    RotationLock(device.rotationLock)
    PowerSaveBrightness(device.powerSave, device.dimPercent, wake)
    // Auto-hide; changing page restarts the countdown.
    LaunchedEffect(showBar, vm.page) {
        if (showBar) {
            delay(4000)
            showBar = false
        }
    }
    // Horizontal drag of the page in px. Read only in graphicsLayer, so dragging never recomposes the keys.
    val pageOffset = remember { Animatable(0f) }
    var neighbor by remember { mutableIntStateOf(-1) } // page drawn beside the current one, -1 none
    var neighborSide by remember { mutableIntStateOf(1) } // 1 right of the current page, -1 left
    var width by remember { mutableIntStateOf(1) }
    var axis by remember { mutableIntStateOf(AXIS_NONE) }
    val scope = rememberCoroutineScope()
    val minSwipe = with(LocalDensity.current) { MIN_SWIPE.toPx() }
    // Swipe left/right drags the next/previous page in under the finger; down reveals the bar, up hides it.
    // Works on keys and the space around them. total is the finger's offset since the touch went down.
    val onDrag: (Offset) -> Unit = { total ->
        if (axis == AXIS_NONE && total.getDistance() >= minSwipe) axis = if (abs(total.x) > abs(total.y)) AXIS_X else AXIS_Y
        if (axis == AXIS_X) {
            neighborSide = if (total.x < 0) 1 else -1
            val target = vm.page.current + neighborSide
            // No wrap-around: past the first or last page the drag just resists.
            val inRange = target in 0 until vm.page.total
            neighbor = if (inRange) target else -1
            scope.launch { pageOffset.snapTo(if (inRange) total.x else total.x / 4) }
        } else if (axis == AXIS_Y) {
            showBar = total.y > 0
            axis = AXIS_DONE
        }
    }
    val onDragEnd: () -> Unit = {
        if (axis == AXIS_X) {
            val target = neighbor
            scope.launch {
                val dir = -neighborSide
                if (target < 0 || abs(pageOffset.value) < width * COMMIT_FRACTION) {
                    pageOffset.animateTo(0f, tween(SLIDE_MS))
                    neighbor = -1
                    return@launch
                }
                if (dir < 0) vm.nextPage() else vm.previousPage() // sent now, it arrives while the page slides
                pageOffset.animateTo(dir * width.toFloat(), tween(SLIDE_MS))
                vm.showCachedPage(target)
                // Never visited: the neighbour slid in empty, so wait for the desktop's icons before swapping.
                if (vm.cachedPage(target) == null) snapshotFlow { vm.redrawing }.first { !it }
                pageOffset.snapTo(0f)
                neighbor = -1
            }
        }
        axis = AXIS_NONE
    }
    val swipe = Modifier.pointerInput(Unit) {
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
        ) { change, delta ->
            change.consume()
            total += delta
            onDrag(total)
        }
    }

    // Only attached with power saving on: every move event would otherwise resume this coroutine too.
    val touchWakes = if (!device.powerSave) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass and no consume: the touch still reaches keys and the swipe.
                if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDown() }) wake.intValue++
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(device.backgroundColor)).onSizeChanged { width = it.width }
            .then(touchWakes).then(swipe),
    ) {
        KeyGrid(
            device,
            Modifier.graphicsLayer { translationX = pageOffset.value },
            // Lambdas: each cell reads its own entry, so one icon update recomposes one cell.
            icon = { vm.icons[it] },
            title = { vm.titles[it] },
            onPress = { row, col, pressed -> vm.press(row, col, pressed) },
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
        if (neighbor >= 0) {
            val cached = vm.cachedPage(neighbor)
            KeyGrid(
                device,
                Modifier.graphicsLayer { translationX = pageOffset.value + neighborSide * size.width },
                icon = { cached?.get(it) },
                title = { null },
            )
        }
        AnimatedVisibility(showBar, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xE6101010)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.previousPage() }) { Text("‹ Prev") }
                Text("Page ${vm.page.label}", color = Color.White)
                TextButton(onClick = { vm.nextPage() }) { Text("Next ›") }
                Spacer(Modifier.weight(1f))
                if (isHome) TextButton(onClick = { showApps = true }) { Text("Apps") }
                TextButton(onClick = { showLayout = true }) { Text("Layout") }
                TextButton(onClick = { vm.back() }) { Text("Devices") }
                TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
            }
        }
    }

    if (showApps) AppsDialog(onDismiss = { showApps = false })
    if (showLayout) {
        LayoutDialog(
            current = device,
            onDismiss = { showLayout = false },
            onApply = { new ->
                vm.setStyle(new)
                // Re-sending the keypad config reloads every icon, so skip it for style-only changes.
                if (new.layout != layout || new.iconSize != device.iconSize) vm.setLayout(new.layout, new.iconSize)
                showLayout = false
            },
        )
    }
}

/** Hides system bars and keeps the screen on while the keypad is visible. */
@Composable
private fun ImmersiveKeepAwake() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Applies the device's orientation while the keypad is visible; other screens rotate freely. */
@Composable
private fun RotationLock(orientation: Int) {
    val activity = LocalView.current.context.findActivity() ?: return
    DisposableEffect(activity, orientation) {
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** Window-only brightness (no permission needed); the system value returns when the keypad closes. */
@Composable
private fun PowerSaveBrightness(enabled: Boolean, dimPercent: Int, wake: IntState) {
    val window = LocalView.current.context.findActivity()?.window ?: return
    fun set(level: Float) {
        // Every attributes write relayouts the window, so skip no-op writes.
        if (window.attributes.screenBrightness != level) window.attributes = window.attributes.apply { screenBrightness = level }
    }
    val taps = if (enabled) wake.intValue else 0
    LaunchedEffect(enabled, dimPercent, taps) {
        if (!enabled) return@LaunchedEffect set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (taps > 0) {
            set(1f)
            delay(WAKE_MS)
        }
        set(dimPercent / 100f)
    }
    DisposableEffect(window) {
        onDispose { set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

private const val WAKE_MS = 5000L

// Long enough for a swipe to pass the touch slop, short enough that a hold still feels instant.
private const val PRESS_DELAY_MS = 120L
private val MIN_SWIPE = 24.dp
private const val SLIDE_MS = 160
// How far (fraction of the screen) a page must be dragged to change on release.
private const val COMMIT_FRACTION = 0.2f
private const val AXIS_NONE = 0
private const val AXIS_X = 1
private const val AXIS_Y = 2
private const val AXIS_DONE = 3 // vertical swipe already handled

private val ROTATIONS = listOf(
    "No Lock" to ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    "Lock Landscape" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    "Lock Portrait" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The key grid. Without onPress it is a picture only: the page next to the current one during a swipe. */
@Composable
private fun KeyGrid(
    device: StoredDevice,
    modifier: Modifier,
    icon: (Int) -> ImageBitmap?,
    title: (Int) -> String?,
    onPress: ((Int, Int, Boolean) -> Unit)? = null,
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val layout = device.layout
    BoxWithConstraints(modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        // Whole pixels: fractional sizes antialias the key edges into visible seams at gap 0.
        val cell = with(LocalDensity.current) { minOf(maxWidth / layout.cols, maxHeight / layout.rows).toPx().toInt().toDp() }
        Column {
            for (row in 0 until layout.rows) {
                Row {
                    for (col in 0 until layout.cols) {
                        val index = Keypad.index(row, col, layout.cols)
                        KeyCell(
                            size = cell,
                            gapPercent = device.gapPercent,
                            shape = keyShape(cell, device, row, col),
                            icon = { icon(index) },
                            title = { title(index) },
                            onPress = onPress?.let { f -> { pressed: Boolean -> f(row, col, pressed) } },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                        )
                    }
                }
            }
        }
    }
}

/** With outerCornersOnly, a key rounds just the corners it shares with the whole grid. */
private fun keyShape(size: Dp, device: StoredDevice, row: Int, col: Int): Shape {
    val r = size * (device.cornerPercent / 100f)
    if (!device.outerCornersOnly) return RoundedCornerShape(r)
    val top = row == 0
    val bottom = row == device.rows - 1
    val start = col == 0
    val end = col == device.cols - 1
    return RoundedCornerShape(
        topStart = if (top && start) r else 0.dp,
        topEnd = if (top && end) r else 0.dp,
        bottomEnd = if (bottom && end) r else 0.dp,
        bottomStart = if (bottom && start) r else 0.dp,
    )
}

@Composable
private fun KeyCell(
    size: Dp,
    gapPercent: Int,
    shape: Shape,
    icon: () -> ImageBitmap?,
    title: () -> String?,
    onPress: ((Boolean) -> Unit)?,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress ?: { _: Boolean -> })
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val touch = if (onPress == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            // Keys consume their touches, so a swipe that starts on a key is handled here, not by the parent.
            down.consume()
            pressed = true
            try {
                val slop = viewConfiguration.touchSlop
                fun moved(c: PointerInputChange) = (c.position - down.position).getDistance() > slop
                var c: PointerInputChange = down
                // The press is held back until the touch is a tap (up) or a hold (timeout), so a swipe sends nothing.
                val hold = withTimeoutOrNull(PRESS_DELAY_MS) {
                    while (c.pressed && !moved(c)) {
                        c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                    }
                    false
                } ?: true
                when {
                    hold -> {
                        currentOnPress(true)
                        try {
                            while (true) {
                                val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                e.consume()
                                if (!e.pressed || e.isOutOfBounds(this@pointerInput.size, extendedTouchPadding)) break
                            }
                        } finally {
                            // Release on up, on slide-off and on disposal, so keys never stick.
                            currentOnPress(false)
                        }
                    }
                    moved(c) -> {
                        pressed = false
                        try {
                            while (true) {
                                currentOnDrag(c.position - down.position)
                                if (!c.pressed) break
                                c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                            }
                        } finally {
                            currentOnDragEnd() // also on cancel, so a half-dragged page always settles
                        }
                    }
                    !c.pressed -> {
                        currentOnPress(true)
                        currentOnPress(false)
                    }
                    // else: the pointer was cancelled before deciding, so nothing is sent.
                }
            } finally {
                pressed = false
            }
        }
    }
    Box(
        Modifier
            .size(size)
            // First in the chain: hit testing maps every move event through each later node
            // (including the press scale matrix), so keep that path short.
            .then(touch)
            .padding(size * (gapPercent / 200f)) // half the gap on each side
            .graphicsLayer {
                // Shrinking at gap 0 would open a hole in the seamless grid; just dim instead.
                val s = if (pressed && gapPercent > 0) 0.92f else 1f
                scaleX = s
                scaleY = s
                alpha = if (pressed) 0.75f else 1f
            }
            .clip(shape)
            // Icon read in the draw phase: a new icon only redraws, no recomposition or layout.
            // Keys without an icon draw nothing, so the screen background shows through.
            .drawBehind {
                val bitmap = icon() ?: return@drawBehind
                drawRect(Color(0xFF101010))
                drawImage(bitmap, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Low)
                // At gap 0 neighbouring borders would show as dividing lines. Stroke is centred on the clip edge: 2dp shows 1dp.
                if (gapPercent > 0) drawOutline(shape.createOutline(this.size, layoutDirection, this), Color(0xFF2C2C2C), style = Stroke(2.dp.toPx()))
            }
            .semantics { contentDescription = title()?.takeIf { it.isNotBlank() } ?: "Key" },
    )
}

@Composable
private fun LayoutDialog(current: StoredDevice, onDismiss: () -> Unit, onApply: (StoredDevice) -> Unit) {
    var cols by remember { mutableIntStateOf(current.cols) }
    var rows by remember { mutableIntStateOf(current.rows) }
    var iconSize by remember { mutableIntStateOf(current.iconSize) }
    var gap by remember { mutableIntStateOf(current.gapPercent) }
    var corner by remember { mutableIntStateOf(current.cornerPercent) }
    var outerOnly by remember { mutableStateOf(current.outerCornersOnly) }
    var powerSave by remember { mutableStateOf(current.powerSave) }
    var dim by remember { mutableIntStateOf(current.dimPercent) }
    var background by remember { mutableStateOf(current.backgroundColor) }
    var rotation by remember { mutableIntStateOf(current.rotationLock) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Presets", style = MaterialTheme.typography.labelLarge)
                Row {
                    Keypad.PRESETS.forEach { p ->
                        Choice("${p.cols}×${p.rows}", selected = p.cols == cols && p.rows == rows) {
                            cols = p.cols
                            rows = p.rows
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Stepper("Columns", cols) { cols = it }
                Stepper("Rows", rows) { rows = it }
                Spacer(Modifier.height(8.dp))
                Text("Icon size (px)", style = MaterialTheme.typography.labelLarge)
                Row {
                    AppViewModel.ICON_SIZES.forEach { s ->
                        Choice("$s", selected = s == iconSize) { iconSize = s }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PercentSlider("Gap", gap, 0..30) { gap = it }
                PercentSlider("Roundness", corner, 0..50) { corner = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Round outer corners only", modifier = Modifier.weight(1f))
                    Switch(checked = outerOnly, onCheckedChange = { outerOnly = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Power saving", modifier = Modifier.weight(1f))
                    Switch(checked = powerSave, onCheckedChange = { powerSave = it })
                }
                if (powerSave) PercentSlider("Dimmed brightness", dim, 5..100) { dim = it }
                Spacer(Modifier.height(8.dp))
                Text("Background", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Choice("Black", selected = background == 0xFF000000L) { background = 0xFF000000L }
                    for (d in Duotones) {
                        Choice(d.name, selected = background == d.bg) { background = d.bg }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Rotation", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((label, value) in ROTATIONS) Choice(label, selected = rotation == value) { rotation = value }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(
                        current.copy(
                            cols = cols, rows = rows, iconSize = iconSize, gapPercent = gap, cornerPercent = corner,
                            outerCornersOnly = outerOnly, powerSave = powerSave, dimPercent = dim, backgroundColor = background,
                            rotationLock = rotation,
                        ),
                    ) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.padding(end = 4.dp)) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = Modifier.padding(end = 4.dp)) { Text(label) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > Keypad.MIN_DIM) { Text("−") }
        Text("$value", modifier = Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < Keypad.MAX_DIM) { Text("+") }
    }
}

@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Text("$label: $value%", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
    )
}
