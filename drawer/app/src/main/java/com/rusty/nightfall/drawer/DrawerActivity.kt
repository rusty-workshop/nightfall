package com.rusty.nightfall.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/** One launchable app, with its icon already pixelated. */
class AppEntry(val label: String, val short: String, val component: ComponentName, val icon: ImageBitmap)

class DrawerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Drawer(onLaunch = ::launch) }
    }

    override fun onStop() {
        super.onStop()
        finish()   // don't linger in the background once you've left (screen off, home, an app opened)
    }

    private fun launch(app: AppEntry) {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        finish()   // back from the app goes to the watch face, not here
    }
}

private const val ICON_GRID = 20      // icons are redrawn this many pixels across, then scaled up crisp
private const val LABEL_COLS = 40     // label width budget, in font pixels (at 1.25dp each)

/** Draw [d] tiny, harden its edges and posterize it: every app gets a pixel-art icon in the same style. */
fun pixelate(d: Drawable): ImageBitmap {
    val n = ICON_GRID
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, n, n)
    d.draw(android.graphics.Canvas(b))
    val px = IntArray(n * n)
    b.getPixels(px, 0, n, 0, 0, n, n)
    for (i in px.indices) {
        val c = px[i]
        if ((c ushr 24) < 110) { px[i] = 0; continue }
        val r = (((c shr 16) and 0xFF) / 36) * 36 + 18
        val g = (((c shr 8) and 0xFF) / 36) * 36 + 18
        val bl = ((c and 0xFF) / 36) * 36 + 18
        px[i] = (0xFF shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or bl.coerceAtMost(255)
    }
    b.setPixels(px, 0, n, 0, 0, n, n)
    return b.asImageBitmap()
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            val label = ri.loadLabel(pm).toString()
            AppEntry(label, PixelFont.shortLabel(label, LABEL_COLS),
                ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), pixelate(ri.loadIcon(pm)))
        }
        .sortedBy { it.label.lowercase() }
}

private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

@Composable
fun Drawer(onLaunch: (AppEntry) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("drawer", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var pinned by remember { mutableStateOf(prefs.getStringSet("pinned", emptySet())!!.toSet()) }
    var sceneChoice by remember { mutableStateOf(prefs.getString("scene", "auto")!!) }   // "auto" or a scene key
    var now by remember { mutableStateOf(Calendar.getInstance()) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    val scene = if (sceneChoice == "auto") Scenes.forHour(hourNow()) else Scenes.byKey(sceneChoice)
    val tint = Color(scene.tint)
    val haptics = LocalHapticFeedback.current
    val state = rememberScalingLazyListState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = ImageBitmap.imageResource(scene.bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0xB0000000)))))

        val list = apps
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
                PixelText("%d:%02d".format(h, now.get(Calendar.MINUTE)), 3.dp)
            }
            item {
                // tap to change the background scene: Auto (follows the face's schedule) or a fixed one
                val name = (if (sceneChoice == "auto") "AUTO - " else "") + scene.label
                PixelText(name, 1.5.dp, color = tint, modifier = Modifier.combinedClickable(onClick = {
                    val keys = listOf("auto") + Scenes.all.map { it.key }
                    sceneChoice = keys[(keys.indexOf(sceneChoice) + 1) % keys.size]
                    prefs.edit().putString("scene", sceneChoice).apply()
                }).padding(6.dp))
            }
            if (list == null) {
                item { PixelText("LOADING", 2.dp, color = tint) }
            } else {
                val pins = list.filter { it.component.flattenToString() in pinned }
                val togglePin = { a: AppEntry ->
                    val k = a.component.flattenToString()
                    pinned = if (k in pinned) pinned - k else pinned + k
                    prefs.edit().putStringSet("pinned", pinned).apply()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (pins.isNotEmpty()) {
                    item { PixelText("PINNED", 1.5.dp, color = tint) }
                    items(pins.chunked(3)) { row -> AppRow(row, pinned, onLaunch, togglePin) }
                    item { PixelText("ALL APPS", 1.5.dp, color = tint) }
                }
                items(list.chunked(3)) { row -> AppRow(row, pinned, onLaunch, togglePin) }
                item { PixelText("HOLD TO PIN", 1.dp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 4.dp)) }
                item {
                    // swipe-up redirect: on/off here; if the accessibility service isn't enabled yet, open its settings
                    val serviceOn = remember { SwipeRedirectService.isServiceEnabled(ctx) }
                    var redirect by remember { mutableStateOf(SwipeRedirectService.isRedirectOn(ctx)) }
                    val label = when {
                        !serviceOn -> "SWIPE UP: SET UP"
                        redirect -> "SWIPE UP: ON"
                        else -> "SWIPE UP: OFF"
                    }
                    PixelText(label, 1.5.dp, color = if (serviceOn && redirect) tint else Color(0xCCFFFFFF),
                        modifier = Modifier.combinedClickable(onClick = {
                            if (!serviceOn) {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else {
                                redirect = !redirect
                                SwipeRedirectService.setRedirectOn(ctx, redirect)
                            }
                        }).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 28.dp))
                }
            }
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
                modifier = Modifier.width(56.dp)
                    .combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onPin(a) }),
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
