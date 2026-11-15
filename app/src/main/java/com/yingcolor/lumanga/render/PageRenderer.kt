package com.yingcolor.lumanga.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * One translated block: what to write, where, and which outline to fill.
 *
 * Ported from manga-translator-android 3.8.5 (AGPL-3.0): the drawing path below is its
 * BubbleRenderer.drawBubble / drawTextInRect, using its BubbleShapePaths, BubbleTextScaling,
 * BubbleTextPlacement, VerticalTextLayout and VerticalTextRenderer unchanged. Only the data
 * source is ours.
 */
class PageJob(
    val text: String,
    /** Text footprint in page pixels. */
    val rect: RectF,
    /** Balloon or lettering outline in page pixels, or null when the model gave none. */
    val contour: FloatArray?,
    /** Vertical setting, taken from the source text. */
    val vertical: Boolean,
    /** Colour sampled from the page under the block. */
    val fill: Int
)

object PageRenderer {
    private const val DARK_TEXT = 0xFF1B1B1B.toInt()

    fun draw(canvas: Canvas, jobs: List<PageJob>, pageWidth: Int, pageHeight: Int) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD
            )
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        val shape = RectF()
        for (job in jobs) {
            val text = BubbleTextScaling.prepareTextForLayout(job.text)
            if (text.isBlank()) continue
            fillPaint.color = job.fill
            shape.set(job.rect)
            val normalized = job.contour?.let { outline ->
                FloatArray(outline.size) { index ->
                    if (index % 2 == 0) outline[index] / pageWidth else outline[index] / pageHeight
                }
            }
            BubbleShapePaths.buildPath(
                outPath = path,
                rect = shape,
                maskContour = normalized,
                sourceWidth = pageWidth,
                sourceHeight = pageHeight,
                originX = 0f,
                originY = 0f,
                scaleX = 1f,
                scaleY = 1f
            )
            canvas.drawPath(path, fillPaint)
            val textRect = BubbleTextScaling.resolveTextRect(path)
            if (textRect.width() <= 0f || textRect.height() <= 0f) continue
            textPaint.color = if (luminance(job.fill) > 140) DARK_TEXT else Color.WHITE
            drawText(canvas, text, textRect, textPaint, job.vertical)
        }
    }

    private fun drawText(canvas: Canvas, text: String, rect: RectF, paint: TextPaint, vertical: Boolean) {
        if (vertical) {
            val width = rect.width().toInt().coerceAtLeast(1)
            val height = rect.height().toInt().coerceAtLeast(1)
            val converted = VerticalTextSymbolConverter.convert(text)
            val size = BubbleTextScaling.findAutoVerticalTextSize(width, height) { candidate ->
                VerticalTextLayoutCalculator.build(paint, converted, width, height, candidate)
            }
            val layout = VerticalTextLayoutCalculator.build(paint, converted, width, height, size)
            canvas.save()
            canvas.clipRect(rect)
            VerticalTextRenderer.draw(canvas, converted, rect, paint, layout)
            canvas.restore()
        } else {
            val width = rect.width().toInt().coerceAtLeast(1)
            val height = rect.height().toInt().coerceAtLeast(1)
            val size = BubbleTextScaling.findAutoHorizontalTextSize(
                text = text,
                maxWidth = width,
                maxHeight = height,
                buildLayout = { value, lineWidth, candidate ->
                    paint.textSize = candidate
                    StaticLayout.Builder.obtain(value, 0, value.length, paint, lineWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1f)
                        .build()
                },
                layoutFits = BubbleTextScaling::layoutFits
            )
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            val left = BubbleTextPlacement.horizontalTextLeft(rect, layout.width)
            val top = BubbleTextPlacement.horizontalTextTop(rect, layout.height, false)
            canvas.save()
            canvas.clipRect(rect)
            canvas.translate(left, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
}
