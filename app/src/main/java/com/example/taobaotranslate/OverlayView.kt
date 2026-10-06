package com.example.taobaotranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen, touch-transparent view that paints translated text on top of the
 * original Chinese text. Everything is in screen coordinates.
 */
class OverlayView(context: Context) : View(context) {

    /**
     * One translated text block. [rect] covers the original text plus [PAD_X]/[PAD_Y].
     * The translation may widen the box up to [maxRight] and lengthen it down to
     * [maxBottom]. The background runs left to right from [bg] at [bgStartX] to
     * [bgEnd] at [bgEndX] (one flat colour when they're equal). Nothing is drawn
     * outside [clip], the scrolling list the text belongs to.
     */
    data class Box(
        val rect: RectF,
        val text: String,
        val fg: Int,
        val lineHeight: Float,
        val bg: Int,
        val bgEnd: Int = bg,
        val bgStartX: Float = rect.left,
        val bgEndX: Float = rect.right,
        val maxRight: Float = rect.right,
        val maxBottom: Float = Float.MAX_VALUE,
        val clip: Rect? = null,
    ) {
        fun offset(dx: Float, dy: Float): Box = copy(
            rect = RectF(rect).apply { offset(dx, dy) },
            bgStartX = bgStartX + dx,
            bgEndX = bgEndX + dx,
            maxRight = maxRight + dx,
            maxBottom = maxBottom + dy,
        )
    }

    /** A translation laid out at the origin, ready to be drawn anywhere. */
    private class Prepared(val width: Float, val height: Float, val layout: StaticLayout, val bgPaint: Paint)

    private class Placed(var x: Float, var y: Float, val clip: Rect?, val prep: Prepared) {
        fun centreIn(area: Rect) = area.contains((x + prep.width / 2).toInt(), (y + prep.height / 2).toInt())
    }

    private var placed: List<Placed> = emptyList()
    private var occluders: List<Rect> = emptyList()
    // Layouts are reused while the page scrolls, so moving boxes costs nothing.
    private val cache = LruCache<List<Any>, Prepared>(300)
    private val screenPos = IntArray(2)
    // Condensed fits longer English into the space of the shorter Chinese;
    // medium weight keeps it solid at small sizes.
    private val typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 500, false)
    private val minTextPx = sp(9f)
    private val maxTextPx = sp(20f)

    /** Shows [boxes]; nothing is painted over [occluders] (windows in front of Taobao). */
    fun setBoxes(boxes: List<Box>, occluders: List<Rect> = emptyList()) {
        this.occluders = occluders
        placed = boxes.map { Placed(it.rect.left, it.rect.top, it.clip, prepare(it, bottomLimit(it, boxes))) }
        invalidate()
    }

    /** Moves the boxes inside [area] along with content that just scrolled. */
    fun shift(area: Rect, dx: Float, dy: Float) {
        for (p in placed) {
            if (p.centreIn(area)) {
                p.x += dx
                p.y += dy
            }
        }
        invalidate()
    }

    /** Removes the boxes inside [area], for content moving too fast to follow. */
    fun hide(area: Rect) {
        placed = placed.filterNot { it.centreIn(area) }
        invalidate()
    }

    /** How far down [box] may grow: the end of its element, or the next block below it. */
    private fun bottomLimit(box: Box, all: List<Box>): Float {
        var limit = box.maxBottom
        for (other in all) {
            val o = other.rect
            if (other !== box && o.top > box.rect.centerY() &&
                o.left < box.rect.right && o.right > box.rect.left
            ) {
                limit = min(limit, o.top)
            }
        }
        return max(limit, box.rect.bottom)
    }

    private fun prepare(box: Box, maxBottom: Float): Prepared {
        val r = box.rect
        val key = listOf(
            box.text, r.width().toInt(), r.height().toInt(), (box.maxRight - r.left).toInt(),
            (maxBottom - r.top).toInt(), box.fg, box.bg, box.bgEnd,
            (box.bgStartX - r.left).toInt(), (box.bgEndX - r.left).toInt(), box.lineHeight.toInt()
        )
        cache.get(key)?.let { return it }

        // The translation may use the element's full width, not just the width
        // of the Chinese, which is usually narrower than the English.
        val maxWidth = (max(r.right, box.maxRight) - r.left - 2 * PAD_X).toInt().coerceAtLeast(40)
        val fitHeight = r.height() - 2 * PAD_Y
        // Start at the size of the original text, then shrink until the
        // translation fits the original block.
        var size = (box.lineHeight * 0.9f).coerceIn(minTextPx, maxTextPx)
        var layout = buildLayout(box, size, maxWidth, Int.MAX_VALUE)
        while (layout.height > fitHeight && size > minTextPx) {
            size = (size - 2f).coerceAtLeast(minTextPx)
            layout = buildLayout(box, size, maxWidth, Int.MAX_VALUE)
        }
        // If it still doesn't fit, grow down into free space only, and end with
        // "…" rather than cover anything below.
        val room = maxBottom - r.top - 2 * PAD_Y
        if (layout.height > room) {
            val lineHeight = layout.height.toFloat() / layout.lineCount
            val lines = (room / lineHeight).toInt().coerceAtLeast(1)
            layout = buildLayout(box, size, maxWidth, lines)
        }

        var textWidth = 0f
        for (i in 0 until layout.lineCount) textWidth = max(textWidth, layout.getLineWidth(i))
        val width = max(r.width(), textWidth + 2 * PAD_X)
        val height = max(r.height(), layout.height + 2 * PAD_Y)
        val paint = Paint().apply {
            color = box.bg
            if (box.bgEnd != box.bg) {
                shader = LinearGradient(
                    box.bgStartX - r.left, 0f, box.bgEndX - r.left, 0f,
                    box.bg, box.bgEnd, Shader.TileMode.CLAMP
                )
            }
        }
        return Prepared(width, height, layout, paint).also { cache.put(key, it) }
    }

    private fun buildLayout(box: Box, sizePx: Float, width: Int, maxLines: Int): StaticLayout {
        // A fresh paint per layout: StaticLayout keeps a reference to it.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = box.fg
            textSize = sizePx
            typeface = this@OverlayView.typeface
        }
        return StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        // Boxes are in screen coordinates; undo any offset of this window.
        getLocationOnScreen(screenPos)
        canvas.translate(-screenPos[0].toFloat(), -screenPos[1].toFloat())
        // Never paint over the keyboard, status bar or pop-ups from other apps.
        for (o in occluders) canvas.clipOutRect(o)
        for (p in placed) {
            canvas.save()
            p.clip?.let { canvas.clipRect(it) }
            canvas.translate(p.x, p.y)
            // Square edges: the box matches the page colour, so it vanishes into it.
            canvas.drawRect(0f, 0f, p.prep.width, p.prep.height, p.prep.bgPaint)
            // Centre vertically so short labels sit where the original text was.
            canvas.translate(PAD_X, (p.prep.height - p.prep.layout.height) / 2f)
            p.prep.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        /** Margin, in screen pixels, that each box covers beyond the detected text. */
        const val PAD_X = 6f
        const val PAD_Y = 4f
    }
}
