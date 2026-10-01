package app.thingsfinder.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.roundToInt

/**
 * Printable box label, a simplified port of includes/label.php: a tag with a
 * double accent frame, the QR code, a dashed divider, the box name and place
 * name on light accent chips, and a decorative corner hole. Default size is
 * the web default, 50×30 mm at 300 dpi. Long names shrink first, then
 * ellipsize — same rule as the web label.
 *
 * The add-item / remove-item labels use the same layout: the box or place
 * name on top, and "Scan to add" / "Scan to remove" with a plus or minus
 * chip underneath.
 */
object LabelRenderer {
    /** The icon in the soft accent chip in front of each text line. */
    enum class Glyph { Box, Pin, Plus, Minus }

    private const val INK = 0xFF2B2620.toInt()
    private const val MUTED = 0xFF5B5245.toInt()
    private const val ACCENT = 0xFFB5652B.toInt()
    private const val ACCENT_SOFT = 0xFFF4E4D5.toInt()
    private const val PAPER = 0xFFFFFFFF.toInt()

    fun mmToPx(mm: Float, dpi: Int): Int = (mm / 25.4f * dpi).roundToInt()

    /** The box's own label: box name, then its place. */
    fun render(
        qrContent: String,
        boxName: String,
        placeName: String,
        widthMm: Float = 50f,
        heightMm: Float = 30f,
        dpi: Int = 300,
    ): Bitmap = render(qrContent, boxName, Glyph.Box, placeName, Glyph.Pin, widthMm, heightMm, dpi)

    /**
     * A label for an add-item / remove-item code: [name] (a box, or a place
     * when [isPlace]) above [caption], e.g. "Scan to add".
     */
    fun renderAction(qrContent: String, name: String, isPlace: Boolean, caption: String, add: Boolean): Bitmap =
        render(qrContent, name, if (isPlace) Glyph.Pin else Glyph.Box, caption, if (add) Glyph.Plus else Glyph.Minus)

    fun render(
        qrContent: String,
        title: String,
        titleGlyph: Glyph,
        subtitle: String,
        subtitleGlyph: Glyph,
        widthMm: Float = 50f,
        heightMm: Float = 30f,
        dpi: Int = 300,
    ): Bitmap {
        val w = mmToPx(widthMm.coerceIn(20f, 200f), dpi.coerceIn(100, 600))
        val h = mmToPx(heightMm.coerceIn(15f, 200f), dpi.coerceIn(100, 600))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(PAPER)

        val unit = h / 30f // one "mm" at the default size, scaled for other sizes
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }

        // Double frame.
        stroke.strokeWidth = 0.6f * unit
        val outer = RectF(0.8f * unit, 0.8f * unit, w - 0.8f * unit, h - 0.8f * unit)
        c.drawRoundRect(outer, 2.2f * unit, 2.2f * unit, stroke)
        stroke.strokeWidth = 0.25f * unit
        val inner = RectF(outer.left + 1.1f * unit, outer.top + 1.1f * unit, outer.right - 1.1f * unit, outer.bottom - 1.1f * unit)
        c.drawRoundRect(inner, 1.4f * unit, 1.4f * unit, stroke)

        // Corner hole (decorative), top right.
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = 0.35f * unit }
        c.drawCircle(inner.right - 2.2f * unit, inner.top + 2.2f * unit, 1.1f * unit, hole)

        // QR code on the left, square, filling the inner height.
        val pad = 1.4f * unit
        val qrSize = (inner.height() - 2 * pad).roundToInt()
        val qr = QrCodes.bitmap(qrContent, qrSize, dark = INK, light = PAPER, margin = 0)
        val qrLeft = inner.left + pad
        val qrTop = inner.top + pad
        c.drawBitmap(qr, qrLeft, qrTop, null)

        // Dashed divider.
        val dividerX = qrLeft + qrSize + pad
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ACCENT
            strokeWidth = 0.25f * unit
            pathEffect = DashPathEffect(floatArrayOf(0.9f * unit, 0.7f * unit), 0f)
        }
        c.drawLine(dividerX, inner.top + pad, dividerX, inner.bottom - pad, dash)

        // Text column.
        val textLeft = dividerX + pad
        val textRight = inner.right - pad - 2.5f * unit // leave room for the hole
        val chip = 4.2f * unit
        val textStart = textLeft + chip + 1.2f * unit
        val textWidth = (textRight - textStart).coerceAtLeast(unit)
        val midY = inner.centerY()

        drawChip(c, textLeft, midY - 1.0f * unit - chip, chip, unit, titleGlyph)
        drawChip(c, textLeft, midY + 1.0f * unit, chip, unit, subtitleGlyph)

        val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = Typeface.DEFAULT_BOLD }
        val regular = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; typeface = Typeface.DEFAULT }
        drawFitted(c, title, bold, textStart, midY - 1.0f * unit - chip / 2, textWidth, maxSize = 4.2f * unit, minSize = 2.2f * unit)
        drawFitted(c, subtitle, regular, textStart, midY + 1.0f * unit + chip / 2, textWidth, maxSize = 3.2f * unit, minSize = 1.9f * unit)
        return bmp
    }

    /** A soft accent square with a filled glyph (box, location pin, plus or minus), like the web label's icon chips. */
    private fun drawChip(c: Canvas, left: Float, top: Float, size: Float, unit: Float, glyph: Glyph) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_SOFT }
        c.drawRoundRect(RectF(left, top, left + size, top + size), 0.9f * unit, 0.9f * unit, bg)
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        val s = size / 24f // glyphs drawn on a 24-unit grid, like the feather icons
        when (glyph) {
            Glyph.Box -> {
                c.drawRect(left + 4 * s, top + 10 * s, left + 20 * s, top + 20 * s, fg) // body
                c.drawRect(left + 2.5f * s, top + 5 * s, left + 21.5f * s, top + 9.5f * s, fg) // lid
            }
            Glyph.Plus, Glyph.Minus -> {
                c.drawRect(left + 5 * s, top + 10.25f * s, left + 19 * s, top + 13.75f * s, fg) // bar
                if (glyph == Glyph.Plus) c.drawRect(left + 10.25f * s, top + 5 * s, left + 13.75f * s, top + 19 * s, fg)
            }
            Glyph.Pin -> drawPin(c, left, top, s, fg)
        }
    }

    private fun drawPin(c: Canvas, left: Float, top: Float, s: Float, fg: Paint) {
        val cx = left + 12 * s
        c.drawCircle(cx, top + 10 * s, 6 * s, fg)
        val tip = Path().apply {
            moveTo(cx - 5.2f * s, top + 13 * s)
            lineTo(cx + 5.2f * s, top + 13 * s)
            lineTo(cx, top + 21 * s)
            close()
        }
        c.drawPath(tip, fg)
        c.drawCircle(cx, top + 10 * s, 2.3f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_SOFT })
    }

    /** Shrink from [maxSize] to [minSize] until the text fits; if it still doesn't, ellipsize. Vertically centred on [centerY]. */
    private fun drawFitted(c: Canvas, text: String, paint: TextPaint, x: Float, centerY: Float, width: Float, maxSize: Float, minSize: Float) {
        var size = maxSize
        paint.textSize = size
        while (size > minSize && paint.measureText(text) > width) {
            size -= 0.1f * (maxSize / 4f)
            paint.textSize = size
        }
        val shown = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        c.drawText(shown, x, centerY - (fm.ascent + fm.descent) / 2, paint)
    }
}
