package com.rusty.nightfall.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("drawer", Context.MODE_PRIVATE)

/** The scene everything uses: the one picked in the drawer, or "auto" (the face's time-of-day schedule). */
object SceneState {
    fun choice(ctx: Context): String = prefs(ctx).getString("scene", "auto")!!
    fun current(ctx: Context): SceneInfo {
        val c = choice(ctx)
        return if (c == "auto") Scenes.forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) else Scenes.byKey(c)
    }
}

/** One launchable app, with its icon already pixelated. */
class AppEntry(val label: String, val short: String, val component: ComponentName, val icon: ImageBitmap)

private const val ICON_GRID = 20

/** Draw [d] tiny, harden its edges and posterize it: every icon comes out in the same pixel style. */
fun pixelateBitmap(d: Drawable, n: Int = ICON_GRID): Bitmap {
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, n, n)
    d.draw(Canvas(b))
    return posterize(b)
}

fun posterize(b: Bitmap): Bitmap {
    val w = b.width; val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val c = px[i]
        if ((c ushr 24) < 110) { px[i] = 0; continue }
        fun q(v: Int) = ((v / 36) * 36 + 18).coerceAtMost(255)
        px[i] = (0xFF shl 24) or (q((c shr 16) and 0xFF) shl 16) or (q((c shr 8) and 0xFF) shl 8) or q(c and 0xFF)
    }
    val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

fun pixelate(d: Drawable): ImageBitmap = pixelateBitmap(d).asImageBitmap()

/** An emoji (e.g. a Flick preset's) drawn small with the system font, then pixelated like the app icons. */
fun pixelEmoji(emoji: String, n: Int = 16): Bitmap {
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = n * 0.8f; textAlign = Paint.Align.CENTER }
    val fm = p.fontMetrics
    Canvas(b).drawText(emoji, n / 2f, n / 2f - (fm.ascent + fm.descent) / 2f, p)
    return posterize(b)
}

/** Scale a small bitmap up by whole pixels (nearest neighbour) so it stays crisp anywhere. */
fun crispScale(b: Bitmap, k: Int): Bitmap = Bitmap.createScaledBitmap(b, b.width * k, b.height * k, false)

/** Apps, loaded once and kept warm (the swipe service keeps this process alive), so the drawer opens full. */
object AppsCache {
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loading = false

    fun warm(ctx: Context, force: Boolean = false) {
        if (loading || (!force && _apps.value != null)) return
        loading = true
        val app = ctx.applicationContext
        scope.launch {
            try { _apps.value = load(app) } finally { loading = false }
        }
    }

    private fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val label = ri.loadLabel(pm).toString()
                AppEntry(label, PixelFont.shortLabel(label, 40),
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), pixelate(ri.loadIcon(pm)))
            }
            .sortedBy { it.label.lowercase() }
    }

    private val iconCache = HashMap<String, ImageBitmap>()

    /** Pixel icon for any package (used for notification senders). */
    fun iconFor(ctx: Context, pkg: String): ImageBitmap? = synchronized(iconCache) {
        iconCache[pkg] ?: runCatching { pixelate(ctx.packageManager.getApplicationIcon(pkg)) }.getOrNull()?.also { iconCache[pkg] = it }
    }
}

/** 9x9 pixel icons: '#' main colour, '+' accent. */
object Icons {
    val SUN = listOf("....+....", ".+.....+.", "...+++...", "..+++++..", "+.+++++.+", "..+++++..", "...+++...", ".+.....+.", "....+....")
    val MOON = listOf("..####...", ".###.....", "###......", "###......", "###......", "###......", ".###.....", "..####...", ".........")
    val CLOUD = listOf(".........", "...###...", "..#####..", ".#######.", "#########", "#########", ".#######.", ".........", ".........")
    val PARTLY = listOf(".+...+...", "...+++...", "+.+++++..", "..+++###.", ".+++#####", "..#######", ".########", ".........", ".........")
    val RAIN = listOf("...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", ".+..+..+.", "+..+..+..", ".........")
    val SNOW = listOf("...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", ".+...+...", "...+...+.", ".+...+...")
    val STORM = listOf("...###...", "..#####..", ".#######.", "#########", ".###+###.", "...++....", "....++...", "...++....", "...+.....")
    val FOG = listOf(".........", "#######..", ".........", "..#######", ".........", "#######..", ".........", "..#######", ".........")
    val WIFI = listOf(".........", "..#####..", ".#.....#.", "#..###..#", "..#...#..", "....#....", "...###...", "....#....", ".........")
    val BLUETOOTH = listOf("....#....", "....##...", "..#.#.#..", "...###...", "....#....", "...###...", "..#.#.#..", "....##...", "....#....")
    val BATTERY = listOf("...###...", "..#####..", "..#...#..", "..#+++#..", "..#+++#..", "..#+++#..", "..#+++#..", "..#####..", ".........")
    val GEAR = listOf("....#....", ".#.###.#.", "..#####..", ".##...##.", "###...###", ".##...##.", "..#####..", ".#.###.#.", "....#....")
    val TORCH = listOf("..#####..", "..#+++#..", "..#+++#..", "...###...", "...#.#...", "...#.#...", "...#.#...", "...###...", ".........")
    val DND = listOf("..#####..", ".#.....#.", "#.......#", "#.#####.#", "#.......#", "#.......#", ".#.....#.", "..#####..", ".........")
    val PLUS = listOf(".........", "....#....", "....#....", "....#....", ".#######.", "....#....", "....#....", "....#....", ".........")
    val MINUS = listOf(".........", ".........", ".........", ".........", ".#######.", ".........", ".........", ".........", ".........")
    val PANEL = listOf("##.##.##.", "##.##.##.", ".........", "##.##.##.", "##.##.##.", ".........", "##.##.##.", "##.##.##.", ".........")
    val PLAY = listOf("..#......", "..##.....", "..###....", "..####...", "..#####..", "..####...", "..###....", "..##.....", "..#......")
    val PAUSE = listOf(".........", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".........")
    val NEXT = listOf(".........", "#...#...#", "##..##..#", "###.###.#", "#########", "###.###.#", "##..##..#", "#...#...#", ".........")
    val PREV = NEXT.map { it.reversed() }
    val BELL = listOf("....#....", "...###...", "..#####..", "..#####..", "..#####..", ".#######.", "#########", ".........", "....#....")

    fun bitmap(grid: List<String>, p: Int, color: Int, accent: Int = color): Bitmap {
        val b = Bitmap.createBitmap(grid[0].length * p, grid.size * p, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint()
        for (y in grid.indices) for (x in grid[y].indices) {
            val ch = grid[y][x]
            if (ch == '.') continue
            paint.color = if (ch == '+') accent else color
            c.drawRect((x * p).toFloat(), (y * p).toFloat(), ((x + 1) * p).toFloat(), ((y + 1) * p).toFloat(), paint)
        }
        return b
    }
}
