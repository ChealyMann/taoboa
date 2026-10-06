package com.example.taobaotranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen, touch-transparent view that paints translated text on top of the
 * original Chinese text. Each [Box] is one OCR text block in screen coordinates,
 * already padded by [PAD_X]/[PAD_Y]; [Box.lineHeight] is the height of one line
 * of the original text. The background runs from [Box.bg] (left) to [Box.bgEnd].
 */
class OverlayView(context: Context) : View(context) {

    data class Box(
        val rect: RectF, val text: String, val bg: Int, val bgEnd: Int, val fg: Int, val lineHeight: Float
    )

    private class Prepared(val rect: RectF, val layout: StaticLayout, val bgPaint: Paint)

    private var prepared: List<Prepared> = emptyList()
    private val screenPos = IntArray(2)
    // Condensed fits longer English into the space of the shorter Chinese;
    // medium weight keeps it solid at small sizes.
    private val typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 500, false)
    private val minTextPx = sp(9f)
    private val maxTextPx = sp(20f)

    fun setBoxes(boxes: List<Box>) {
        prepared = boxes.map { prepare(it, bottomLimit(it, boxes)) }
        invalidate()
    }

    /** How far down [box] may grow before it would cover the next block below it. */
    private fun bottomLimit(box: Box, all: List<Box>): Float {
        var limit = if (height > 0) height.toFloat() else Float.MAX_VALUE
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
        val width = (box.rect.width() - 2 * PAD_X).toInt().coerceAtLeast(40)
        val fitHeight = box.rect.height() - 2 * PAD_Y
        // Start at the size of the original text, then shrink until the
        // translation fits inside the original block.
        var size = (box.lineHeight * 0.9f).coerceIn(minTextPx, maxTextPx)
        var layout = buildLayout(box, size, width, Int.MAX_VALUE)
        while (layout.height > fitHeight && size > minTextPx) {
            size = (size - 2f).coerceAtLeast(minTextPx)
            layout = buildLayout(box, size, width, Int.MAX_VALUE)
        }
        // English is usually longer than Chinese. If it still doesn't fit, grow
        // down into free space only, and end with "…" rather than cover the
        // block below.
        val room = maxBottom - box.rect.top - 2 * PAD_Y
        if (layout.height > room) {
            val lineHeight = layout.height.toFloat() / layout.lineCount
            val lines = (room / lineHeight).toInt().coerceAtLeast(1)
            layout = buildLayout(box, size, width, lines)
        }
        val rect = RectF(box.rect)
        rect.bottom = max(rect.bottom, rect.top + layout.height + 2 * PAD_Y)
        return Prepared(rect, layout, backgroundPaint(box, rect))
    }

    private fun backgroundPaint(box: Box, rect: RectF): Paint = Paint().apply {
        color = box.bg
        if (box.bgEnd != box.bg) {
            // The two colours were sampled around the left and right halves.
            val quarter = rect.width() / 4f
            shader = LinearGradient(
                rect.left + quarter, 0f, rect.right - quarter, 0f,
                box.bg, box.bgEnd, Shader.TileMode.CLAMP
            )
        }
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
        for (p in prepared) {
            // Square edges: the box matches the page colour, so it vanishes into it.
            canvas.drawRect(p.rect, p.bgPaint)
            canvas.save()
            // Centre vertically so short labels sit where the original text was.
            val dy = (p.rect.height() - p.layout.height) / 2f
            canvas.translate(p.rect.left + PAD_X, p.rect.top + dy)
            p.layout.draw(canvas)
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
