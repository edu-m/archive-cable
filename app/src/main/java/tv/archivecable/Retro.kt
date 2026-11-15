package tv.archivecable

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared 90s set-top-box styling: palette, pixel font, bevelled panels, CRT scanlines. */
object Retro {
    const val NAVY_DARK = 0xFF000A3A.toInt()
    const val NAVY = 0xFF001C6E.toInt()
    const val CELL = 0xFF1B3A8F.toInt()
    const val CELL_LIGHT = 0xFF4A6FD0.toInt()
    const val CELL_SHADOW = 0xFF07164A.toInt()
    const val CELL_PAST = 0xFF14275E.toInt()
    const val HEADER = 0xFF0E7C86.toInt()
    const val HEADER_LIGHT = 0xFF4FC3CC.toInt()
    const val YELLOW = 0xFFFFD83A.toInt()
    const val CYAN = 0xFF7FE7FF.toInt()
    const val WHITE = 0xFFF4F4F4.toInt()
    const val GRAY = 0xFF9AA4C0.toInt()
    const val RED = 0xFFFF2A2A.toInt()
    const val GREEN = 0xFF3DDC5A.toInt()
    const val BAR_BG = 0xFF0A1238.toInt()

    @Volatile private var font: Typeface? = null

    fun typeface(ctx: Context): Typeface =
        font ?: (ResourcesCompat.getFont(ctx, R.font.vt323) ?: Typeface.MONOSPACE).also { font = it }

    fun textPaint(ctx: Context, size: Float, color: Int = WHITE, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            textSize = size
            this.color = color
            textAlign = align
            setShadowLayer(0.01f, 3f, 3f, 0xFF000000.toInt())
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint().apply { style = Paint.Style.STROKE }

    /** Raised (or sunken) 3D panel, Windows-95 / cable-box style. */
    fun bevel(c: Canvas, r: RectF, face: Int, light: Int, shadow: Int, width: Float = 3f, sunken: Boolean = false) {
        fill.shader = null
        fill.color = face
        c.drawRect(r, fill)
        stroke.strokeWidth = width
        stroke.color = if (sunken) shadow else light
        c.drawLine(r.left, r.top + width / 2, r.right, r.top + width / 2, stroke)
        c.drawLine(r.left + width / 2, r.top, r.left + width / 2, r.bottom, stroke)
        stroke.color = if (sunken) light else shadow
        c.drawLine(r.left, r.bottom - width / 2, r.right, r.bottom - width / 2, stroke)
        c.drawLine(r.right - width / 2, r.top, r.right - width / 2, r.bottom, stroke)
    }

    fun gradientPanel(c: Canvas, r: RectF, top: Int, bottom: Int, border: Int, borderWidth: Float = 3f) {
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(r, fill)
        fill.shader = null
        stroke.strokeWidth = borderWidth
        stroke.color = border
        c.drawRect(r, stroke)
    }

    /** Completion bar with a hard-edged, segmented 90s fill. */
    fun progressBar(c: Canvas, r: RectF, fraction: Float, color: Int = YELLOW, segmented: Boolean = true) {
        fill.shader = null
        fill.color = BAR_BG
        c.drawRect(r, fill)
        val inner = RectF(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2)
        val fillRight = inner.left + inner.width() * fraction.coerceIn(0f, 1f)
        fill.color = color
        if (!segmented) {
            c.drawRect(inner.left, inner.top, fillRight, inner.bottom, fill)
        } else {
            val seg = (inner.height() * 1.6f).coerceAtLeast(6f)
            var x = inner.left
            while (x < fillRight) {
                c.drawRect(x, inner.top, minOf(x + seg - 2, fillRight), inner.bottom, fill)
                x += seg
            }
        }
        stroke.strokeWidth = 1.5f
        stroke.color = 0xFF5A6A9A.toInt()
        c.drawRect(r, stroke)
    }

    private val scan = Paint().apply { color = 0x22000000 }

    fun scanlines(c: Canvas, w: Float, h: Float, spacing: Float = 4f) {
        var y = 0f
        while (y < h) {
            c.drawRect(0f, y, w, y + spacing / 2, scan)
            y += spacing
        }
    }

    fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        if (width <= 0) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private var timeFmt = SimpleDateFormat("h:mm", Locale.US)
    private var ampmFmt = SimpleDateFormat("a", Locale.US)
    private var dayFmt = SimpleDateFormat("EEE MM/dd", Locale.US)

    fun onLanguageChanged() {
        val lang = I18n.current
        timeFmt = SimpleDateFormat(if (lang.clock24h) "H:mm" else "h:mm", lang.locale)
        ampmFmt = SimpleDateFormat("a", Locale.US)
        dayFmt = SimpleDateFormat(if (lang.clock24h) "EEE dd/MM" else "EEE MM/dd", lang.locale)
    }

    /** "7:30p" or "19:30" */
    fun shortTime(ms: Long): String {
        val d = Date(ms)
        if (I18n.current.clock24h) return timeFmt.format(d)
        return timeFmt.format(d) + ampmFmt.format(d).first().lowercaseChar()
    }

    /** "7:30 PM" or "19:30" */
    fun longTime(ms: Long): String {
        val d = Date(ms)
        if (I18n.current.clock24h) return timeFmt.format(d)
        return timeFmt.format(d) + " " + ampmFmt.format(d)
    }

    fun day(ms: Long): String = dayFmt.format(Date(ms)).replace(".", "").uppercase(I18n.current.locale)

    /** Shrink [paint] (down to [minSize]) until [text] fits in [width]; returns the possibly ellipsized text. */
    fun fit(text: String, paint: TextPaint, width: Float, maxSize: Float, minSize: Float): String {
        paint.textSize = maxSize
        while (paint.textSize > minSize && paint.measureText(text) > width) paint.textSize -= 2f
        return ellipsize(text, paint, width)
    }

    fun duration(ms: Long): String {
        val totalMin = ((ms + 30_000) / 60_000).coerceAtLeast(1)
        return if (totalMin >= 60) "${totalMin / 60}h ${"%02d".format(totalMin % 60)}m" else "$totalMin min"
    }

    fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    fun floorToHalfHour(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        cal.set(Calendar.MINUTE, if (cal.get(Calendar.MINUTE) >= 30) 30 else 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun channelNumber(n: Int) = "%02d".format(n)
}
