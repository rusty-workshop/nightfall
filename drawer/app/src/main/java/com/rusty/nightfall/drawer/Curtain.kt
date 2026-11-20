package com.rusty.nightfall.drawer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * The last good frame of each Nightfall screen, captured while it sat at rest, plus where its clock is
 * (so the curtain can redraw the clock with the real time instead of showing a stale one).
 */
class Snap(val bitmap: Bitmap, val scene: String, val clock: RectF, val clockPx: Float)

object Snapshots {
    private val map = ConcurrentHashMap<Surface, Snap>()

    fun put(ctx: Context, s: Surface, snap: Snap) {
        map[s] = snap
        Thread {   // keep them across restarts of the process
            runCatching {
                File(ctx.filesDir, "snap_${s.name}.png").outputStream().use { snap.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(ctx.filesDir, "snap_${s.name}.txt").writeText(
                    "${snap.scene}\n${snap.clock.left} ${snap.clock.top} ${snap.clock.right} ${snap.clock.bottom}\n${snap.clockPx}")
            }
        }.start()
    }

    fun get(ctx: Context, s: Surface): Snap? = map[s] ?: runCatching {
        val png = File(ctx.filesDir, "snap_${s.name}.png")
        val meta = File(ctx.filesDir, "snap_${s.name}.txt").readLines()
        val r = meta[1].split(' ').map { it.toFloat() }
        Snap(BitmapFactory.decodeFile(png.path)!!, meta[0], RectF(r[0], r[1], r[2], r[3]), meta[2].toFloat()).also { map[s] = it }
    }.getOrNull()
}

/**
 * A full-screen accessibility overlay: above every app and above Samsung's quick panel, shown in a single frame
 * (the window is added once, up front, and only toggled visible). It paints the Nightfall screen that's about to
 * appear, slides it in the way the finger was going, and hides again the moment the real screen has drawn.
 */
class Curtain(private val ctx: Context) : View(ctx) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var snap: Snap? = null
    private var sceneBg: Bitmap? = null
    private var sceneKey = ""
    private val paint = Paint().apply { isFilterBitmap = false }
    private val shade = Paint()
    private var anim: ValueAnimator? = null
    private var attached = false
    var showing = false
        private set

    fun attach() {
        if (attached) return
        visibility = GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply { windowAnimations = 0; title = "NightfallCurtain" }
        runCatching { wm.addView(this, lp); attached = true }
    }

    fun detach() { if (attached) runCatching { wm.removeView(this) }; attached = false }

    /** dx/dy: where it slides in from, as a fraction of the screen (e.g. dy = 0.35 = from a bit below). */
    fun show(s: Surface, dx: Float, dy: Float) {
        val scene = SceneState.current(ctx)
        if (scene.key != sceneKey) { sceneKey = scene.key; sceneBg = BitmapFactory.decodeResource(ctx.resources, scene.bg) }
        snap = Snapshots.get(ctx, s)?.takeIf { it.scene == scene.key }
        anim?.cancel()
        showing = true
        visibility = VISIBLE
        translationX = dx * width
        translationY = dy * height
        invalidate()
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 140
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { val f = it.animatedValue as Float; translationX = dx * width * f; translationY = dy * height * f }
            start()
        }
    }

    fun hide() {
        if (!showing) return
        showing = false
        anim?.cancel()
        visibility = GONE
        translationX = 0f; translationY = 0f
    }

    /** The Nightfall backdrop exactly as the activity draws it: scene (crop, no smoothing) + top-to-bottom shade. */
    private fun drawBackdrop(c: Canvas, clip: RectF? = null) {
        val bg = sceneBg ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val scale = maxOf(w / bg.width, h / bg.height)
        val dw = bg.width * scale; val dh = bg.height * scale
        c.save()
        clip?.let { c.clipRect(it) }
        c.drawColor(android.graphics.Color.BLACK)
        c.drawBitmap(bg, Rect(0, 0, bg.width, bg.height), RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
        shade.shader = LinearGradient(0f, 0f, 0f, h, 0x55000000, 0xB8000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, shade)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val s = snap
        if (s == null) { drawBackdrop(c); drawClock(c, RectF(0f, height * 0.30f, width.toFloat(), height * 0.30f + 48f), 6f); return }
        c.drawBitmap(s.bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        // the snapshot's clock may be minutes old: paint the backdrop over it and draw the real time
        val pad = s.clockPx * 2
        val patch = RectF(0f, s.clock.top - pad, width.toFloat(), s.clock.bottom + pad)
        drawBackdrop(c, patch)
        drawClock(c, s.clock, s.clockPx)
    }

    private fun drawClock(c: Canvas, at: RectF, px: Float) {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val text = "%d:%02d".format(h, now.get(Calendar.MINUTE))
        val b = PixelFont.bitmap(text, 1, android.graphics.Color.WHITE)   // 1 px per font pixel, scaled crisply below
        val k = at.height() / 8f                                         // the clock's real font-pixel size on screen
        val w = b.width * k
        val left = (width - w) / 2f
        c.drawBitmap(b, null, RectF(left, at.top, left + w, at.top + b.height * k), paint)
    }
}
