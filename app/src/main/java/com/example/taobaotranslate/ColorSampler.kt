package com.example.taobaotranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * How a piece of text looks on screen, measured from a screenshot. Positions
 * are relative to the area that was measured. The background runs left to
 * right from [bg] at [bgStartX] to [bgEnd] at [bgEndX] (equal for a flat colour).
 */
class TextStyle(
    val bg: Int,
    val bgEnd: Int,
    val bgStartX: Float,
    val bgEndX: Float,
    val fg: Int,
    val ink: Rect,
    val lineHeight: Float,
)

object ColorSampler {

    /** Text that sits on a photo or banner. It's left alone: a flat box over a picture looks worse. */
    val ON_IMAGE = TextStyle(0, 0, 0f, 0f, 0, Rect(), 0f)

    private const val FLAT_DISTANCE = 48       // this close to the background counts as background
    private const val INK_DISTANCE = 120       // this far from the background counts as text
    private const val FLAT_SHARE = 0.4f        // share of an element that must be plain background
    private const val FLAT_RING_FRACTION = 0.7f // share of pixels around a block that must match its background
    private const val MIN_TEXT_CONTRAST = 130  // brightness gap between text and background

    /**
     * Measures the text inside [area] (bitmap coordinates): background colour,
     * text colour, where the text actually is and how tall one line is. Returns
     * [ON_IMAGE] for text on a picture, or null when no text is visible yet.
     */
    fun analyze(bmp: Bitmap, area: Rect): TextStyle? {
        val r = Rect(area)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return null
        val w = r.width()
        val h = r.height()
        if (w < 4 || h < 4) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, r.left, r.top, w, h)

        // Background from the left and right thirds, so gradient buttons are matched.
        val third = max(1, w / 3)
        val bgL = dominantInColumns(px, w, h, 0, third)
        val bgR = dominantInColumns(px, w, h, w - third, w)
        val span = max(1f, (w - third).toFloat())
        val expected = IntArray(w) { x -> mix(bgL, bgR, ((x - third / 2f) / span).coerceIn(0f, 1f)) }

        val ink = IntArray(w * h)
        var inkN = 0
        var flat = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val rowInk = BooleanArray(h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val d = colorDistance(c, expected[x])
                if (d <= FLAT_DISTANCE) {
                    flat++
                } else if (d > INK_DISTANCE) {
                    ink[inkN++] = c
                    rowInk[y] = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (flat < w * h * FLAT_SHARE) return ON_IMAGE
        if (inkN == 0) return null

        val bg = mix(bgL, bgR, 0.5f)
        val fg = strengthen(dominant(ink, inkN) ?: Color.BLACK, bg)
        val ox = r.left - area.left
        val oy = r.top - area.top
        val inkRect = Rect(minX + ox, minY + oy, maxX + 1 + ox, maxY + 1 + oy)
        val lineHeight = lineHeight(rowInk) ?: inkRect.height().toFloat()
        val gradient = colorDistance(bgL, bgR) > 12
        return TextStyle(
            if (gradient) bgL else bg, if (gradient) bgR else bg,
            ox + third / 2f, ox + w - third / 2f,
            fg, inkRect, lineHeight
        )
    }

    /**
     * Colours for a text block found by OCR at [r] (bitmap coordinates), sampled
     * from a ring [gapX]/[gapY] pixels outside it. The returned [TextStyle.ink]
     * is the whole block and [TextStyle.lineHeight] is left at 0 for the caller.
     */
    fun sampleAround(bmp: Bitmap, r: Rect, gapX: Int, gapY: Int): TextStyle? {
        val left = r.left.coerceIn(0, bmp.width - 1)
        val top = r.top.coerceIn(0, bmp.height - 1)
        val right = r.right.coerceIn(left + 1, bmp.width)
        val bottom = r.bottom.coerceIn(top + 1, bmp.height)

        val outL = (left - gapX).coerceAtLeast(0)
        val outT = (top - gapY).coerceAtLeast(0)
        val outR = (right + gapX).coerceAtMost(bmp.width - 1)
        val outB = (bottom + gapY).coerceAtMost(bmp.height - 1)
        val midX = (outL + outR) / 2
        val size = 2 * ((outR - outL) / 4 + 1) + 2 * ((outB - outT) / 4 + 1)
        val ring = IntArray(size)
        val leftRing = IntArray(size)
        val rightRing = IntArray(size)
        var n = 0
        var nl = 0
        var nr = 0
        fun add(x: Int, y: Int) {
            val c = bmp.getPixel(x, y)
            ring[n++] = c
            if (x < midX) leftRing[nl++] = c else rightRing[nr++] = c
        }
        for (x in outL..outR step 4) { add(x, outT); add(x, outB) }
        for (y in outT..outB step 4) { add(outL, y); add(outR, y) }
        val bg = dominant(ring, n) ?: return null
        val bgL = dominant(leftRing, nl) ?: bg
        val bgR = dominant(rightRing, nr) ?: bg

        var flat = 0
        for (i in 0 until n) if (minOf(colorDistance(ring[i], bgL), colorDistance(ring[i], bgR)) <= 60) flat++
        if (flat < n * FLAT_RING_FRACTION) return ON_IMAGE

        val w = right - left
        val h = bottom - top
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, left, top, w, h)
        var inkN = 0
        for (c in px) if (minOf(colorDistance(c, bgL), colorDistance(c, bgR)) > INK_DISTANCE) px[inkN++] = c
        val ink = dominant(px, inkN)
        val fg = if (ink != null) strengthen(ink, bg) else if (luma(bg) > 140) Color.BLACK else Color.WHITE

        val gradient = colorDistance(bgL, bgR) > 12
        return TextStyle(
            if (gradient) bgL else bg, if (gradient) bgR else bg,
            (outL + midX) / 2f - r.left, (midX + outR) / 2f - r.left,
            fg, Rect(0, 0, r.width(), r.height()), 0f
        )
    }

    /** Median height of the bands of rows that contain ink, i.e. of one line of text. */
    private fun lineHeight(rowInk: BooleanArray): Float? {
        val bands = ArrayList<Int>()
        var start = -1
        var last = -1
        for (y in rowInk.indices) {
            if (rowInk[y]) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last > 1) {
                bands += last - start + 1
                start = -1
            }
        }
        if (start >= 0) bands += last - start + 1
        val lines = bands.filter { it >= 3 }.sorted()
        return if (lines.isEmpty()) null else lines[lines.size / 2].toFloat()
    }

    private fun dominantInColumns(px: IntArray, w: Int, h: Int, from: Int, to: Int): Int {
        val sample = IntArray((to - from) * h)
        var n = 0
        for (y in 0 until h) for (x in from until to) sample[n++] = px[y * w + x]
        return dominant(sample, n) ?: Color.WHITE
    }

    /** Most common colour among the first [n] entries (bucketed, then averaged). */
    private fun dominant(colors: IntArray, n: Int): Int? {
        if (n == 0) return null
        val counts = IntArray(4096)
        val rs = IntArray(4096)
        val gs = IntArray(4096)
        val bs = IntArray(4096)
        for (i in 0 until n) {
            val c = colors[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            counts[k]++; rs[k] += r; gs[k] += g; bs[k] += b
        }
        var best = 0
        for (k in counts.indices) if (counts[k] > counts[best]) best = k
        val c = counts[best]
        return Color.rgb(rs[best] / c, gs[best] / c, bs[best] / c)
    }

    /** Darkens (or lightens) a pale text colour until it reads as solid ink, keeping its hue. */
    private fun strengthen(c: Int, bg: Int): Int {
        val target = if (luma(c) < luma(bg)) Color.BLACK else Color.WHITE
        var out = c
        repeat(8) {
            if (abs(luma(out) - luma(bg)) >= MIN_TEXT_CONTRAST) return out
            out = mix(out, target, 0.25f)
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun luma(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}
