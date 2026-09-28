package app.olauncher.helper

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A battery that shows its own level: an outline, a fill as wide as the charge, and a bolt while
 * charging. It sits where the weather widget has its condition icon, so the two widgets share
 * a headline rhythm - glyph, then the number - instead of the battery value starting a column
 * to the left of the temperature. Drawn in the widget's text colour; stroke weight matches the
 * app's 24-unit outline icons (1.7 units).
 *
 * @param level 0-100, or -1 when unknown (outline only)
 */
class BatteryGlyph(private val level: Int, private val charging: Boolean, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL_AND_STROKE
    }
    private val body = RectF()
    private val cap = RectF()
    private val fill = RectF()
    private val bolt = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        val left = bounds.left + (bounds.width() - s) / 2f
        val top = bounds.top + (bounds.height() - s) / 2f
        val stroke = s * 1.7f / 24f
        paint.strokeWidth = stroke
        val bodyHeight = s * .5f
        body.set(left + stroke / 2f, top + (s - bodyHeight) / 2f, left + s * .86f, top + (s + bodyHeight) / 2f)
        cap.set(body.right + stroke * .9f, body.centerY() - s * .1f, body.right + stroke * .9f + s * .07f, body.centerY() + s * .1f)
        val inset = stroke / 2f + s * .06f
        val innerWidth = body.width() - inset * 2f
        val fraction = batteryFillFraction(level)
        fill.set(body.left + inset, body.top + inset, body.left + inset + innerWidth * fraction, body.bottom - inset)
        // The bolt of ic_bolt (M13,2 L5,14 H11 L10,22 L19,9 H13 Z, a 14x20 box in 24 units), scaled
        // to stand a little taller than the body, as a charging battery's bolt does.
        val h = bodyHeight * 1.18f
        val scale = h / 20f
        val ox = body.centerX() - 7f * scale - 5f * scale
        val oy = body.centerY() - h / 2f - 2f * scale
        bolt.reset()
        bolt.moveTo(ox + 13f * scale, oy + 2f * scale)
        bolt.lineTo(ox + 5f * scale, oy + 14f * scale)
        bolt.lineTo(ox + 11f * scale, oy + 14f * scale)
        bolt.lineTo(ox + 10f * scale, oy + 22f * scale)
        bolt.lineTo(ox + 19f * scale, oy + 9f * scale)
        bolt.lineTo(ox + 13f * scale, oy + 9f * scale)
        bolt.close()
        clear.strokeWidth = stroke * 1.4f
    }

    override fun draw(canvas: Canvas) {
        val radius = body.height() * .24f
        // A layer only while charging, where the bolt has to cut its outline out of the fill.
        val layer = if (charging) canvas.saveLayer(RectF(bounds), null) else -1
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(body, radius, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(cap, cap.width() / 2f, cap.width() / 2f, paint)
        if (fill.width() > 0f) {
            val r = (radius - paint.strokeWidth).coerceAtLeast(0f)
            canvas.drawRoundRect(fill, r, r, paint)
        }
        if (charging) {
            canvas.drawPath(bolt, clear)
            canvas.drawPath(bolt, paint)
            canvas.restoreToCount(layer)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * How much of the body the fill covers. Unknown draws none; any charge at all draws at least a
 * sliver, so 1% never reads as an empty (dead) battery.
 */
internal fun batteryFillFraction(level: Int): Float = when {
    level < 0 -> 0f
    level == 0 -> 0f
    else -> maxOf(.06f, level.coerceAtMost(100) / 100f)
}
