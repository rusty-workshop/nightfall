package com.rusty.nightfall.drawer

import android.graphics.Bitmap
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

private const val WHITE = 0xFFFFFFFF.toInt()
private const val DIM = 0xB0FFFFFF.toInt()
private const val INK = 0xFF14062A.toInt()

/**
 * Tiles can't use custom fonts, so every piece of pixel text and every icon is drawn to a small bitmap at the
 * watch's real pixel density (so it stays perfectly crisp) and shipped as an inline image resource. The scene
 * background is the same image the drawer uses.
 */
class TileUi(private val density: Float, val widthDp: Float, val tint: Int) {
    val images = LinkedHashMap<String, ResourceBuilders.ImageResource>()
    private var n = 0

    private fun devPx(dpPerPixel: Float) = max(1, (dpPerPixel * density).roundToInt())

    fun image(b: Bitmap, click: ModifiersBuilders.Clickable? = null): LayoutElement {
        val id = "i${n++}"
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        images[id] = ResourceBuilders.ImageResource.Builder().setInlineResource(
            ResourceBuilders.InlineImageResource.Builder().setData(out.toByteArray()).setWidthPx(b.width).setHeightPx(b.height)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED).build()
        ).build()
        val img = LayoutElementBuilders.Image.Builder().setResourceId(id)
            .setWidth(dp(b.width / density)).setHeight(dp(b.height / density))
        if (click != null) img.setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).build())
        return img.build()
    }

    fun text(s: String, px: Float, color: Int = WHITE, shadow: Boolean = true, click: ModifiersBuilders.Clickable? = null) =
        image(PixelFont.bitmap(PixelFont.normalize(s).ifEmpty { " " }, devPx(px), color, if (shadow) 0xD014062A.toInt() else null), click)

    fun icon(grid: List<String>, px: Float, color: Int = WHITE, accent: Int = tint) = image(Icons.bitmap(grid, devPx(px), color, accent))

    fun pixelArt(b: Bitmap, sizeDp: Float): LayoutElement {
        val k = max(1, (sizeDp * density / b.width).roundToInt())
        return image(crispScale(b, k))
    }

    fun paragraph(s: String, px: Float, maxWidthDp: Float, lines: Int, color: Int = WHITE): LayoutElement {
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        PixelFont.wrap(PixelFont.normalize(s), (maxWidthDp / px).toInt(), lines).forEachIndexed { i, l ->
            if (i > 0) col.addContent(space(h = px))
            col.addContent(text(l, px, color))
        }
        return col.build()
    }
}

fun space(w: Float = 0f, h: Float = 0f): LayoutElement = LayoutElementBuilders.Spacer.Builder().setWidth(dp(w)).setHeight(dp(h)).build()

fun column(vararg items: LayoutElement): LayoutElement = LayoutElementBuilders.Column.Builder()
    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).also { b -> items.forEach { b.addContent(it) } }.build()

fun row(vararg items: LayoutElement): LayoutElement = LayoutElementBuilders.Row.Builder()
    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER).also { b -> items.forEach { b.addContent(it) } }.build()

fun load(id: String): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()

fun launch(cls: String): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
    ActionBuilders.LaunchAction.Builder().setAndroidActivity(
        ActionBuilders.AndroidActivity.Builder().setPackageName("com.rusty.nightfall.drawer").setClassName(cls).build()
    ).build()
).build()

/** A pixel-style button: square, 1dp border in the scene tint, filled when [on]. */
fun button(content: LayoutElement, tint: Int, click: ModifiersBuilders.Clickable, on: Boolean = false, w: Float? = null): LayoutElement {
    val b = LayoutElementBuilders.Box.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(ModifiersBuilders.Modifiers.Builder()
            .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(if (on) tint else 0x99000000.toInt())).build())
            .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1f)).setColor(argb(tint)).build())
            .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(6f)).build())
            .setClickable(click).build())
        .addContent(content)
    if (w != null) b.setWidth(dp(w))
    return b.build()
}

abstract class PixelTileService : TileService() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    protected open val freshnessMs: Long = 0

    abstract suspend fun content(req: RequestBuilders.TileRequest, ui: TileUi): LayoutElement

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val scene = SceneState.current(this@PixelTileService)
        val dc = req.deviceConfiguration
        val ui = TileUi(dc.screenDensity, dc.screenWidthDp.toFloat(), scene.tint.toInt())
        val inner = content(req, ui)
        val version = "${javaClass.simpleName}-${System.nanoTime()}"
        cache[version] = scene.bg to ui.images
        while (cache.size > 12) cache.keys.firstOrNull()?.let { cache.remove(it) }

        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(LayoutElementBuilders.Image.Builder().setResourceId("bg").setWidth(expand()).setHeight(expand())
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP).build())
            .addContent(LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(
                    ModifiersBuilders.Background.Builder().setColor(argb(0x99000000.toInt())).build()).build()).build())
            .addContent(inner)
            .build()
        TileBuilders.Tile.Builder().setResourcesVersion(version).setFreshnessIntervalMillis(freshnessMs)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        val (bg, images) = cache[req.version] ?: (SceneState.current(this@PixelTileService).bg to emptyMap())
        val r = ResourceBuilders.Resources.Builder().setVersion(req.version)
            .addIdToImageMapping("bg", ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(bg).build()).build())
        images.forEach { (id, img) -> r.addIdToImageMapping(id, img) }
        r.build()
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Pair<Int, Map<String, ResourceBuilders.ImageResource>>>()
    }
}

// ---------------------------------------------------------------- Day: date, steps, battery, next event
class DayTileService : PixelTileService() {
    override val freshnessMs = 15 * 60_000L

    override suspend fun content(req: RequestBuilders.TileRequest, ui: TileUi): LayoutElement {
        val date = SimpleDateFormat("EEE d MMM", Locale.US).format(Date())
        val steps = Steps.today(this)
        val ev = nextEvent(this)
        val parts = mutableListOf(ui.text(date, 2f), space(h = 8f))
        parts += if (steps != null) column(ui.text("%,d".format(Locale.US, steps), 3.5f), space(h = 3f), ui.text("STEPS", 1.25f, ui.tint))
        else ui.text("STEPS: TAP TO SET UP", 1.25f, DIM, click = launch("com.rusty.nightfall.drawer.PanelActivity"))
        parts += space(h = 8f)
        parts += row(ui.icon(Icons.BATTERY, 1.5f, WHITE, ui.tint), space(w = 4f), ui.text("${battery(this)}%", 2f))
        parts += space(h = 8f)
        if (ev != null) {
            parts += ui.text(if (ev.allDay) "TODAY" else "NEXT ${hourMinute(ev.start)}", 1.25f, ui.tint)
            parts += space(h = 3f)
            parts += ui.paragraph(ev.title, 1.5f, ui.widthDp * 0.62f, 2)
        } else if (!granted(this, android.Manifest.permission.READ_CALENDAR)) {
            parts += ui.text("CALENDAR: SET UP", 1.25f, DIM, click = launch("com.rusty.nightfall.drawer.PanelActivity"))
        } else {
            parts += ui.text("NOTHING NEXT", 1.25f, DIM)
        }
        return column(*parts.toTypedArray())
    }
}

// ---------------------------------------------------------------- Weather: now + next hours
class WeatherTileService : PixelTileService() {
    override val freshnessMs = 30 * 60_000L

    override suspend fun content(req: RequestBuilders.TileRequest, ui: TileUi): LayoutElement {
        val w = WeatherRepo.get(this)
            ?: return column(ui.icon(Icons.CLOUD, 3f), space(h = 8f),
                ui.paragraph(if (Where.get(this) == null) "OPEN NIGHTFALL ONCE TO SET YOUR LOCATION" else "NO WEATHER YET",
                    1.25f, ui.widthDp * 0.6f, 3))
        val (icon, name) = WeatherRepo.look(w.code, w.day)
        val hours = w.hours.map { h ->
            val (hi, _) = WeatherRepo.look(h.code, h.day)
            column(ui.text(h.label, 1f, DIM), space(h = 3f), ui.icon(hi, 1.5f, WHITE, ui.tint), space(h = 3f), ui.text("${h.temp}°", 1.25f))
        }
        val hourRow = LayoutElementBuilders.Row.Builder()
        hours.forEachIndexed { i, h -> if (i > 0) hourRow.addContent(space(w = 9f)); hourRow.addContent(h) }
        return column(
            row(ui.icon(icon, 3.5f, WHITE, ui.tint), space(w = 8f), column(ui.text("${w.temp}°", 3.5f), space(h = 3f), ui.text(name, 1.25f, ui.tint))),
            space(h = 6f),
            ui.text("H ${w.hi}°  L ${w.lo}°", 1.25f, DIM),
            space(h = 10f),
            hourRow.build(),
        )
    }
}

// ---------------------------------------------------------------- Music: whatever's playing on the watch
class MusicTileService : PixelTileService() {
    override suspend fun content(req: RequestBuilders.TileRequest, ui: TileUi): LayoutElement {
        val clicked = req.currentState.lastClickableId
        if (clicked in setOf("pp", "next", "prev")) { NotifListener.media(clicked); delay(350) }
        if (!NotifListener.isEnabled(this)) {
            return column(ui.icon(Icons.PLAY, 3f), space(h = 8f), ui.paragraph("MUSIC NEEDS NOTIFICATION ACCESS (SEE README)", 1.25f, ui.widthDp * 0.6f, 3))
        }
        val np = NotifListener.media.value
        val btn = { grid: List<String>, id: String, on: Boolean -> button(ui.icon(grid, 2.5f, if (on) INK else WHITE, if (on) INK else ui.tint), ui.tint, load(id), on) }
        return column(
            ui.text("NOW PLAYING", 1.25f, ui.tint),
            space(h = 6f),
            ui.paragraph(np?.title?.ifBlank { null } ?: "NOTHING PLAYING", 2f, ui.widthDp * 0.66f, 2),
            space(h = 4f),
            ui.paragraph(np?.artist.orEmpty().ifBlank { " " }, 1.25f, ui.widthDp * 0.6f, 1, DIM),
            space(h = 10f),
            row(btn(Icons.PREV, "prev", false), space(w = 6f), btn(if (np?.playing == true) Icons.PAUSE else Icons.PLAY, "pp", true),
                space(w = 6f), btn(Icons.NEXT, "next", false)),
        )
    }
}

// ---------------------------------------------------------------- Flick: pick the preset the watch beams
class FlickTileService : PixelTileService() {
    override suspend fun content(req: RequestBuilders.TileRequest, ui: TileUi): LayoutElement {
        if (!Flick.installed(this)) return ui.paragraph("FLICK ISN'T INSTALLED", 1.5f, ui.widthDp * 0.6f, 2)
        var presets = Flick.presets(this) ?: return ui.paragraph("UPDATE FLICK TO USE THIS TILE", 1.5f, ui.widthDp * 0.6f, 2)
        val clicked = req.currentState.lastClickableId
        if (clicked == "off" || clicked.startsWith("p:")) {
            val id = clicked.removePrefix("p:").takeIf { clicked != "off" } ?: ""
            Flick.select(this, id)
            presets = presets.map { it.copy(active = it.id == id) }   // show the pick right away
        }
        val active = presets.firstOrNull { it.active }
        val shown = presets.take(6)
        val bw = ui.widthDp * 0.36f
        val grid = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        shown.chunked(2).forEachIndexed { r, pair ->
            if (r > 0) grid.addContent(space(h = 5f))
            val rowB = LayoutElementBuilders.Row.Builder()
            pair.forEachIndexed { i, p ->
                if (i > 0) rowB.addContent(space(w = 5f))
                val label = ui.text(PixelFont.shortLabel(p.name, 30), 1f, if (p.active) INK else WHITE, shadow = !p.active)
                val face = if (p.emoji.isNotBlank()) row(ui.pixelArt(pixelEmoji(p.emoji), 14f), space(w = 4f), label) else label
                rowB.addContent(button(face, ui.tint, load("p:${p.id}"), p.active, bw))
            }
            grid.addContent(rowB.build())
        }
        return column(
            ui.text("FLICK", 1.25f, ui.tint),
            space(h = 3f),
            ui.paragraph(active?.name ?: "NOT BEAMING", 1.5f, ui.widthDp * 0.6f, 1),
            space(h = 8f),
            if (shown.isEmpty()) ui.text("NO PRESETS YET", 1.25f, DIM) else grid.build(),
            space(h = 6f),
            if (active != null) ui.text("STOP", 1.25f, DIM, click = load("off")) else space(),
        )
    }
}
