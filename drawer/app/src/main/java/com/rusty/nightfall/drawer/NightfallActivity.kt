package com.rusty.nightfall.drawer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

enum class Surface { DRAWER, INBOX, PANEL }

/**
 * The single host for Nightfall's drawer, notification inbox and control panel. It is never finished on its own:
 * leaving sends it to the back, so every later open is a "hot" start (no splash screen, no reload, instant).
 */
class NightfallActivity : ComponentActivity() {
    private val surface = mutableStateOf(Surface.DRAWER)
    /** Bumped on every open: each open starts fresh at the top, which is also what the curtain's snapshot shows. */
    private val openId = mutableIntStateOf(0)
    private val resumes = mutableIntStateOf(0)
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { runCatching { Steps.register(this@NightfallActivity); Where.refresh(this@NightfallActivity) } }
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface.value = surfaceOf(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        setContent {
            Host(surface.value, resumes.intValue, openId.intValue, onCapture = ::capture, onLeave = ::leave, onLaunch = ::launchApp, onAskPerms = {
                askPerms.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.READ_CALENDAR,
                    Manifest.permission.ACCESS_COARSE_LOCATION))
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        surface.value = surfaceOf(intent)
        openId.intValue++
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        AppsCache.warm(this)
        lifecycleScope.launch { runCatching { Where.refresh(this@NightfallActivity) } }
    }

    /** Grab this screen's current frame as the curtain's snapshot for next time (it's at rest, at the top). */
    private fun capture(s: Surface, clock: android.graphics.RectF) {
        val v = window.decorView
        if (v.width == 0 || v.height == 0 || !hasWindowFocus()) return
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) Snapshots.put(this, s, Snap(bmp, SceneState.current(this).key, clock, clock.height() / 8f))
            }, android.os.Handler(mainLooper))
        }
    }

    fun leave() {
        if (!SwipeRedirectService.leaveToFace()) moveTaskToBack(true)
    }

    private fun launchApp(app: AppEntry) {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    companion object {
        fun intent(ctx: Context, s: Surface): Intent = Intent(ctx, NightfallActivity::class.java)
            .putExtra("surface", s.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun surfaceOf(i: Intent?): Surface =
            i?.getStringExtra("surface")?.let { runCatching { Surface.valueOf(it) }.getOrNull() }
                ?: if (i?.component?.className?.endsWith("PanelActivity") == true) Surface.PANEL else Surface.DRAWER
    }
}

private val LocalListState = compositionLocalOf<ScalingLazyListState?> { null }
private val LocalClockSink = compositionLocalOf<(android.graphics.RectF) -> Unit> { {} }

@Composable
private fun Host(surface: Surface, resumes: Int, openId: Int, onCapture: (Surface, android.graphics.RectF) -> Unit,
                 onLeave: () -> Unit, onLaunch: (AppEntry) -> Unit, onAskPerms: () -> Unit) {
    val ctx = LocalContext.current
    var sceneChoice by remember { mutableStateOf(SceneState.choice(ctx)) }
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(resumes) {
        while (true) {
            now = Calendar.getInstance()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val scene = remember(sceneChoice, now) { SceneState.current(ctx) }
    val tint = Color(scene.tint)

    // drag sideways to leave (right everywhere; left too in the inbox, since it came in from the left)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val leaveAt = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(surface, resumes) { offset.snapTo(0f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(ImageBitmap.imageResource(scene.bg), null, contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0xB8000000)))))
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(surface) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offset.value
                            if (v > leaveAt || (surface == Surface.INBOX && v < -leaveAt)) onLeave()
                            else scope.launch { offset.animateTo(0f, tween(150)) }
                        },
                        onHorizontalDrag = { change, d ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + d).let { if (surface == Surface.INBOX) it else it.coerceAtLeast(0f) }) }
                        },
                    )
                }
        ) {
            key(openId, surface) {
                val listState = rememberScalingLazyListState()
                var clockRect by remember { mutableStateOf<android.graphics.RectF?>(null) }
                CompositionLocalProvider(LocalListState provides listState, LocalClockSink provides { clockRect = it }) {
                    when (surface) {
                        Surface.DRAWER -> DrawerScreen(tint, scene.label, sceneChoice, now, onLaunch) {
                            val keys = listOf("auto") + Scenes.all.map { it.key }
                            sceneChoice = keys[(keys.indexOf(sceneChoice) + 1) % keys.size]
                            prefs(ctx).edit().putString("scene", sceneChoice).apply()
                        }
                        Surface.INBOX -> InboxScreen(tint, now)
                        Surface.PANEL -> PanelScreen(tint, now, resumes, onAskPerms)
                    }
                }
                LaunchedEffect(Unit) {
                    // two frames after this open is composed it's on screen: the curtain can go
                    withFrameNanos { }; withFrameNanos { }
                    SwipeRedirectService.surfaceShown()
                    // at rest and still at the top a moment later: that's the snapshot for next time
                    delay(500)
                    val r = clockRect
                    if (r != null && !listState.isScrollInProgress && listState.centerItemIndex == 1 &&
                        listState.centerItemScrollOffset == 0 && offset.value == 0f) onCapture(surface, r)
                }
            }
        }
    }
}

@Composable
private fun Clock(now: Calendar, px: Float = 3f) {
    val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val sink = LocalClockSink.current
    PixelText("%d:%02d".format(h, now.get(Calendar.MINUTE)), px.dp, modifier = Modifier.onGloballyPositioned {
        val b = it.boundsInWindow()
        sink(android.graphics.RectF(b.left, b.top, b.right, b.bottom))
    })
}

@Composable
private fun Screen(content: ScalingLazyListScope.() -> Unit) {
    val state = LocalListState.current ?: rememberScalingLazyListState()
    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** A tappable pixel-text row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PixelButtonText(label: String, color: Color, px: Float = 1.5f, onClick: () -> Unit) {
    PixelText(label, px.dp, color = color, modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
}

// ================================================================ drawer
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerScreen(tint: Color, sceneLabel: String, sceneChoice: String, now: Calendar, onLaunch: (AppEntry) -> Unit, onCycleScene: () -> Unit) {
    val ctx = LocalContext.current
    val apps by AppsCache.apps.collectAsState()
    var pinned by remember { mutableStateOf(prefs(ctx).getStringSet("pinned", emptySet())!!.toSet()) }
    val haptics = LocalHapticFeedback.current
    Screen {
        item { Clock(now) }
        item { PixelButtonText((if (sceneChoice == "auto") "AUTO - " else "") + sceneLabel, tint, onClick = onCycleScene) }
        val list = apps
        if (list == null) {
            item { PixelText("LOADING", 2.dp, color = tint) }
        } else {
            val pins = list.filter { it.component.flattenToString() in pinned }
            val togglePin = { a: AppEntry ->
                val k = a.component.flattenToString()
                pinned = if (k in pinned) pinned - k else pinned + k
                prefs(ctx).edit().putStringSet("pinned", pinned).apply()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            if (pins.isNotEmpty()) {
                item { PixelText("PINNED", 1.5.dp, color = tint) }
                items(pins.chunked(3)) { row -> AppRow(row, pinned, onLaunch, togglePin) }
                item { PixelText("ALL APPS", 1.5.dp, color = tint) }
            }
            items(list.chunked(3)) { row -> AppRow(row, pinned, onLaunch, togglePin) }
            item { PixelText("HOLD TO PIN", 1.dp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(row: List<AppEntry>, pinned: Set<String>, onLaunch: (AppEntry) -> Unit, onPin: (AppEntry) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Spacer(Modifier.weight(1f))
        for (a in row) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp).combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onPin(a) }),
            ) {
                Box {
                    Image(a.icon, a.label, filterQuality = FilterQuality.None, modifier = Modifier.size(40.dp))
                    if (a.component.flattenToString() in pinned) {
                        Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Color(0xFFFFE070)))
                    }
                }
                Spacer(Modifier.height(3.dp))
                PixelText(a.short, 1.25.dp)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ================================================================ notification inbox
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxScreen(tint: Color, now: Calendar) {
    val ctx = LocalContext.current
    val items by NotifListener.items.collectAsState()
    val haptics = LocalHapticFeedback.current
    val enabled = remember(now) { NotifListener.isEnabled(ctx) }
    Screen {
        item { Clock(now, 2.5f) }
        item { PixelText(if (items.isEmpty()) "INBOX" else "INBOX - ${items.size}", 1.5.dp, color = tint) }
        if (!enabled) {
            item { PixelParagraph("NOTIFICATION ACCESS IS OFF. SEE THE NIGHTFALL README TO TURN IT ON.", 1.25.dp, 110, 4, center = true) }
        } else if (items.isEmpty()) {
            item { PixelText("ALL CLEAR", 2.dp, modifier = Modifier.padding(vertical = 12.dp)) }
        }
        items(items, key = { it.key }) { n ->
            Row(
                Modifier.fillMaxWidth(0.86f)
                    .background(Color(0x99000000))
                    .border(1.dp, tint.copy(alpha = 0.55f))
                    .combinedClickable(
                        onClick = { runCatching { n.open?.send() } },
                        onLongClick = { if (n.clearable) { NotifListener.dismiss(n.key); haptics.performHapticFeedback(HapticFeedbackType.LongPress) } },
                    )
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppsCache.iconFor(ctx, n.pkg)?.let { Image(it, null, filterQuality = FilterQuality.None, modifier = Modifier.size(22.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        PixelText(PixelFont.shortLabel(n.app, 50), 1.dp, color = tint)
                        Spacer(Modifier.width(4.dp))
                        PixelText(hourMinute(n.time), 1.dp, color = Color(0x99FFFFFF))
                    }
                    if (n.title.isNotBlank()) PixelParagraph(n.title, 1.5.dp, 74, 2)
                    if (n.text.isNotBlank()) PixelParagraph(n.text, 1.25.dp, 88, 4, color = Color(0xDDFFFFFF))
                }
            }
        }
        if (items.any { it.clearable }) item { PixelButtonText("CLEAR ALL", tint) { NotifListener.dismissAll() } }
        if (items.isNotEmpty()) item { PixelText("HOLD TO DISMISS", 1.dp, color = Color(0x99FFFFFF)) }
        item {
            PixelButtonText("SAMSUNG'S VIEW", Color(0xCCFFFFFF), 1.25f) { SwipeRedirectService.openSamsung(Surface.INBOX, ctx as Activity) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ================================================================ control panel
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelTile(icon: List<String>, label: String, tint: Color, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp).combinedClickable(onClick = onClick)) {
        Box(
            Modifier.size(42.dp).background(if (active) tint.copy(alpha = 0.85f) else Color(0x88000000))
                .border(1.dp, tint.copy(alpha = if (active) 1f else 0.5f)),
            contentAlignment = Alignment.Center,
        ) { PixelIcon(icon, 2.5.dp, color = if (active) Color(0xFF14062A) else Color.White, accent = if (active) Color(0xFF14062A) else tint) }
        Spacer(Modifier.height(3.dp))
        PixelText(label, 1.dp)
    }
}

@Composable
private fun PanelRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.weight(1f)); content(); Spacer(Modifier.weight(1f))
    }
}

private fun open(ctx: Context, vararg actions: String) {
    for (a in actions) {
        if (runCatching { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun PanelScreen(tint: Color, now: Calendar, resumes: Int, onAskPerms: () -> Unit) {
    val ctx = LocalContext.current
    var dnd by remember(resumes) { mutableStateOf(NotifListener.dndOn(ctx)) }
    val canWrite = remember(resumes) { Settings.System.canWrite(ctx) }
    var bright by remember(resumes) { mutableStateOf(runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)) }
    val listener = remember(resumes) { NotifListener.isEnabled(ctx) }
    val swipes = remember(resumes) { SwipeRedirectService.isServiceEnabled(ctx) }
    val missing = remember(resumes) {
        listOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filterNot { granted(ctx, it) }
    }
    var toggles by remember(resumes) { mutableStateOf(Surface.entries.associateWith { SwipeRedirectService.isOn(ctx, it) }) }

    fun setBright(v: Int) {
        if (!canWrite) { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return }
        val nv = v.coerceIn(8, 255)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, nv)
        bright = nv
    }

    Screen {
        item { Clock(now, 2.5f) }
        item { PixelText("BATTERY ${battery(ctx)}%", 1.5.dp, color = tint) }
        item {
            PanelRow {
                PanelTile(Icons.DND, if (dnd) "DND ON" else "DND", tint, active = dnd) {
                    if (NotifListener.setDnd(!dnd)) dnd = !dnd
                }
                PanelTile(Icons.TORCH, "TORCH", tint) { ctx.startActivity(Intent(ctx, FlashlightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                PanelTile(Icons.PANEL, "SAMSUNG", tint) { SwipeRedirectService.openSamsung(Surface.PANEL, ctx as Activity) }
            }
        }
        item {
            PanelRow {
                PanelTile(Icons.MINUS, "DIMMER", tint) { setBright(bright - 32) }
                PanelTile(Icons.SUN, if (canWrite) "${bright * 100 / 255}%" else "BRIGHT", tint) { setBright(if (bright < 200) 255 else 96) }
                PanelTile(Icons.PLUS, "BRIGHTER", tint) { setBright(bright + 32) }
            }
        }
        item {
            PanelRow {
                PanelTile(Icons.WIFI, "WI-FI", tint) { open(ctx, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) }
                PanelTile(Icons.BLUETOOTH, "BLUETOOTH", tint) { open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) }
                PanelTile(Icons.GEAR, "SETTINGS", tint) { open(ctx, Settings.ACTION_SETTINGS) }
            }
        }
        item { PixelText("SWIPES", 1.5.dp, color = tint, modifier = Modifier.padding(top = 8.dp)) }
        if (!swipes) {
            item { PixelButtonText("SWIPES: SET UP", Color(0xCCFFFFFF)) { open(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) } }
        } else {
            for ((s, label) in listOf(Surface.DRAWER to "UP: DRAWER", Surface.INBOX to "RIGHT: INBOX", Surface.PANEL to "DOWN: PANEL")) {
                item {
                    val on = toggles[s] == true
                    PixelButtonText("$label ${if (on) "ON" else "OFF"}", if (on) tint else Color(0xAAFFFFFF), 1.25f) {
                        SwipeRedirectService.setOn(ctx, s, !on)
                        toggles = toggles + (s to !on)
                    }
                }
            }
        }
        if (missing.isNotEmpty()) item { PixelButtonText("TILES: ALLOW ACCESS", tint, 1.25f, onAskPerms) }
        if (!listener) item { PixelParagraph("INBOX, DND + MUSIC NEED NOTIFICATION ACCESS (SEE README)", 1.dp, 120, 3, center = true) }
        if (!canWrite) item { PixelParagraph("BRIGHTNESS NEEDS 'MODIFY SETTINGS' (SEE README)", 1.dp, 120, 3, center = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ================================================================ flashlight
class FlashlightActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        })
    }

    override fun onStop() { super.onStop(); finish() }
}
