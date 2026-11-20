package com.rusty.nightfall.drawer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The same 5x7 pixel font as the Nightfall watch face, extended to the full alphabet. */
object PixelFont {
    private val glyphs: Map<Char, List<String>> = mapOf(
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
        ':' to listOf(".", "#", ".", ".", ".", "#", "."),
        '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
        '-' to listOf("...", "...", "...", "###", "...", "...", "..."),
        '\'' to listOf("#", "#", ".", ".", ".", ".", "."),
        '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '!' to listOf("#", "#", "#", "#", "#", ".", "#"),
        '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        '/' to listOf("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."),
        '%' to listOf("##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"),
        '&' to listOf(".##..", "#..#.", ".##..", ".#...", "#.#.#", "#..#.", ".##.#"),
        ',' to listOf("..", "..", "..", "..", "..", ".#", "#."),
        '(' to listOf(".#", "#.", "#.", "#.", "#.", "#.", ".#"),
        ')' to listOf("#.", ".#", ".#", ".#", ".#", ".#", "#."),
        '°' to listOf("###", "#.#", "###", "...", "...", "...", "..."),
        '<' to listOf("...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#"),
        '>' to listOf("#...", ".#..", "..#.", "...#", "..#.", ".#..", "#..."),
    )

    /** Upper-cased glyphs for [text]; characters the font doesn't have are dropped. */
    fun glyphsFor(text: String): List<List<String>> = text.uppercase().mapNotNull { glyphs[it] }

    /** Free text (notifications, track names) -> what the font can draw: accents stripped, quotes folded, upper case. */
    fun normalize(text: String): String {
        val plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.map {
            when (it) {
                '’', '‘', '`', '"', '“', '”' -> '\''
                '—', '–', '_' -> '-'
                '\n', '\t' -> ' '
                else -> it
            }
        }.joinToString("").uppercase().filter { glyphs.containsKey(it) }.replace(Regex(" +"), " ").trim()
    }

    /** Word-wrap [text] (already normalized) into lines of at most [maxCols] font pixels; the last line gets "..." if cut. */
    fun wrap(text: String, maxCols: Int, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        fun fits(s: String) = width(glyphsFor(s)) <= maxCols
        for (word0 in text.split(' ')) {
            var word = word0
            while (!fits(word) && word.length > 1) {          // a word too long for a line: hard-split it
                var cut = word.length - 1
                while (cut > 1 && !fits(word.take(cut))) cut--
                if (cur.isNotEmpty()) { lines += cur; cur = "" }
                lines += word.take(cut); word = word.drop(cut)
            }
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (fits(next)) cur = next else { lines += cur; cur = word }
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && !fits("$last...")) last = last.dropLast(1)
        kept[kept.lastIndex] = "${last.trimEnd()}..."
        return kept
    }

    /** Render [text] to a bitmap, [p] device pixels per font pixel, with an optional hard shadow one pixel down. */
    fun bitmap(text: String, p: Int, color: Int, shadow: Int? = 0xD014062A.toInt()): android.graphics.Bitmap {
        val gs = glyphsFor(text)
        val cols = width(gs).coerceAtLeast(1)
        val b = android.graphics.Bitmap.createBitmap(cols * p, 8 * p, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val paint = android.graphics.Paint()
        for ((dy, col) in listOfNotNull(shadow?.let { 1 to it }, 0 to color)) {
            paint.color = col
            var x0 = 0
            for (g in gs) {
                for (y in g.indices) for (x in g[y].indices) {
                    if (g[y][x] == '#') c.drawRect(((x0 + x) * p).toFloat(), ((y + dy) * p).toFloat(),
                        ((x0 + x + 1) * p).toFloat(), ((y + dy + 1) * p).toFloat(), paint)
                }
                x0 += g[0].length + 1
            }
        }
        return b
    }

    /** Width in font pixels, including the 1px gap between glyphs. */
    fun width(gs: List<List<String>>): Int = (gs.sumOf { it[0].length + 1 } - 1).coerceAtLeast(0)

    /** Fit [label] into [maxCols] font pixels: drop brand prefixes, then cut to a whole word or trim. */
    fun shortLabel(label: String, maxCols: Int): String {
        var s = label.uppercase()
        for (p in listOf("SAMSUNG ", "GALAXY ", "GOOGLE ", "WEAR OS ", "WEAR ")) if (s.startsWith(p) && s.length > p.length) s = s.removePrefix(p)
        s = s.filter { glyphs.containsKey(it) }.trim()
        while (s.isNotEmpty() && width(glyphsFor(s)) > maxCols) {
            s = if (' ' in s) s.substringBeforeLast(' ') else s.dropLast(1)
        }
        return s
    }
}

/** Text drawn in the pixel font, each font pixel a [px] square, with a hard drop shadow one pixel down. */
@Composable
fun PixelText(
    text: String,
    px: Dp,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    shadow: Color = Color(0xD014062A),
) {
    val gs = remember(text) { PixelFont.glyphsFor(text) }
    val cols = PixelFont.width(gs)
    Canvas(modifier.size(px * cols.coerceAtLeast(1), px * 8)) {
        val p = px.toPx()
        val cell = Size(p + 0.5f, p + 0.5f)   // overlap a hair so no seams show between cells
        for ((dy, c) in listOf(1 to shadow, 0 to color)) {
            var x0 = 0
            for (g in gs) {
                for (y in g.indices) for (x in g[y].indices) {
                    if (g[y][x] == '#') drawRect(c, Offset((x0 + x) * p, (y + dy) * p), cell)
                }
                x0 += g[0].length + 1
            }
        }
    }
}

/** Several lines of wrapped pixel text. [text] is normalized first, so any string is safe to pass. */
@Composable
fun PixelParagraph(
    text: String,
    px: Dp,
    maxCols: Int,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    center: Boolean = false,
) {
    val lines = remember(text, maxCols, maxLines) { PixelFont.wrap(PixelFont.normalize(text), maxCols, maxLines) }
    androidx.compose.foundation.layout.Column(
        modifier,
        horizontalAlignment = if (center) androidx.compose.ui.Alignment.CenterHorizontally else androidx.compose.ui.Alignment.Start,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(px),
    ) {
        for (l in lines) PixelText(l, px, color = color)
    }
}

/** A small pixel-art icon: '#' in [color], '+' in [accent]. */
@Composable
fun PixelIcon(grid: List<String>, px: Dp, color: Color = Color.White, accent: Color = color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(px * grid[0].length, px * grid.size)) {
        val p = px.toPx()
        val cell = Size(p + 0.5f, p + 0.5f)
        for (y in grid.indices) for (x in grid[y].indices) {
            when (grid[y][x]) {
                '#' -> drawRect(color, Offset(x * p, y * p), cell)
                '+' -> drawRect(accent, Offset(x * p, y * p), cell)
            }
        }
    }
}

val PixelDefaultPx = 2.dp
