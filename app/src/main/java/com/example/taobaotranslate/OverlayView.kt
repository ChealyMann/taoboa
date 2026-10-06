package com.example.taobaotranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Full-screen, touch-transparent view that paints translated text on top of the
 * original Chinese text. Each [Box] is one OCR text block in screen coordinates.
 */
class OverlayView(context: Context) : View(context) {

    data class Box(val rect: RectF, val text: String, val bg: Int, val fg: Int)

    private class Prepared(val rect: RectF, val layout: StaticLayout, val bg: Int)

    private var prepared: List<Prepared> = emptyList()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setBoxes(boxes: List<Box>) {
        prepared = boxes.map { prepare(it) }
        invalidate()
    }

    private fun prepare(box: Box): Prepared {
        val width = (box.rect.width() - 8f).toInt().coerceAtLeast(40)
        var size = (box.rect.height() * 0.5f).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        var layout = buildLayout(box, size, width)
        // Shrink until the translated text fits inside the original text's box.
        while (layout.height + 4 > box.rect.height() && size > MIN_TEXT_PX) {
            size = (size - 2f).coerceAtLeast(MIN_TEXT_PX)
            layout = buildLayout(box, size, width)
        }
        // English is usually longer than Chinese: if it still doesn't fit at the
        // minimum size, grow the box downward so nothing is cut off.
        val needed = layout.height + 4f
        val rect = RectF(box.rect)
        if (needed > rect.height()) rect.bottom = rect.top + needed
        return Prepared(rect, layout, box.bg)
    }

    private fun buildLayout(box: Box, sizePx: Float, width: Int): StaticLayout {
        // A fresh paint per layout: StaticLayout keeps a reference to it.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = box.fg
            textSize = sizePx
        }
        return StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        for (p in prepared) {
            bgPaint.color = p.bg
            canvas.drawRoundRect(p.rect, 6f, 6f, bgPaint)
            canvas.save()
            canvas.translate(p.rect.left + 4f, p.rect.top + 2f)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        const val MIN_TEXT_PX = 22f
        const val MAX_TEXT_PX = 56f
    }
}
