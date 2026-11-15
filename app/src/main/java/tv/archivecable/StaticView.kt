package tv.archivecable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Analog snow while tuning, or SMPTE colour bars with a "please stand by" slate. */
class StaticView(context: Context) : View(context) {

    enum class Mode { OFF, SNOW, BARS }

    var mode: Mode = Mode.OFF
        private set
    private var message: String? = null
    private var subMessage: String? = null

    private val noiseW = 240
    private val noiseH = 135
    private val noise = Bitmap.createBitmap(noiseW, noiseH, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(noiseW * noiseH)
    private var seed = 0x2545F491L
    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint()
    private val dst = Rect()
    private val title = Retro.textPaint(context, 72f, Retro.WHITE, Paint.Align.CENTER)
    private val sub = Retro.textPaint(context, 40f, Retro.YELLOW, Paint.Align.CENTER)
    private val boxPaint = Paint().apply { color = 0xCC000000.toInt() }

    init {
        isFocusable = false
        visibility = GONE
    }

    fun show(mode: Mode, message: String? = null, subMessage: String? = null) {
        this.mode = mode
        this.message = message
        this.subMessage = subMessage
        visibility = if (mode == Mode.OFF) GONE else VISIBLE
        invalidate()
    }

    fun hide() = show(Mode.OFF)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (mode) {
            Mode.OFF -> return
            Mode.SNOW -> {
                fillNoise()
                noise.setPixels(pixels, 0, noiseW, 0, 0, noiseW, noiseH)
                dst.set(0, 0, width, height)
                canvas.drawBitmap(noise, null, dst, bitmapPaint)
                postInvalidateOnAnimation()
            }
            Mode.BARS -> drawBars(canvas, w, h)
        }
        val scale = h / 1080f
        val msg = message ?: return
        title.textSize = 72f * scale
        sub.textSize = 40f * scale
        val boxW = maxOf(title.measureText(msg), subMessage?.let { sub.measureText(it) } ?: 0f) + 80f * scale
        val boxH = (if (subMessage != null) 170f else 110f) * scale
        val box = RectF(w / 2 - boxW / 2, h / 2 - boxH / 2, w / 2 + boxW / 2, h / 2 + boxH / 2)
        canvas.drawRect(box, boxPaint)
        canvas.drawText(msg, w / 2, box.top + 80f * scale, title)
        subMessage?.let { canvas.drawText(it, w / 2, box.top + 135f * scale, sub) }
    }

    private fun fillNoise() {
        var s = seed
        // Rolling horizontal band, like a detuned set.
        val band = ((System.nanoTime() / 16_000_000L) % noiseH).toInt()
        for (y in 0 until noiseH) {
            val boost = if (kotlin.math.abs(y - band) < 6) 40 else 0
            val row = y * noiseW
            for (x in 0 until noiseW) {
                s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
                val v = ((s ushr 24).toInt() and 0xFF).coerceAtMost(215) + boost
                val g = v.coerceAtMost(255)
                pixels[row + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        seed = s
    }

    private fun drawBars(c: Canvas, w: Float, h: Float) {
        val top = intArrayOf(
            0xFFC0C0C0.toInt(), 0xFFC0C000.toInt(), 0xFF00C0C0.toInt(), 0xFF00C000.toInt(),
            0xFFC000C0.toInt(), 0xFFC00000.toInt(), 0xFF0000C0.toInt(),
        )
        val mid = intArrayOf(
            0xFF0000C0.toInt(), 0xFF131313.toInt(), 0xFFC000C0.toInt(), 0xFF131313.toInt(),
            0xFF00C0C0.toInt(), 0xFF131313.toInt(), 0xFFC0C0C0.toInt(),
        )
        val bw = w / 7f
        val h1 = h * 0.67f
        val h2 = h * 0.75f
        for (i in 0 until 7) {
            fill.color = top[i]; c.drawRect(i * bw, 0f, (i + 1) * bw, h1, fill)
            fill.color = mid[i]; c.drawRect(i * bw, h1, (i + 1) * bw, h2, fill)
        }
        val bottom = intArrayOf(0xFF00214C.toInt(), 0xFFFFFFFF.toInt(), 0xFF32006A.toInt(), 0xFF131313.toInt())
        val bx = w / 6f * 1.25f
        for (i in 0 until 4) {
            fill.color = bottom[i]; c.drawRect(i * bx, h2, (i + 1) * bx, h, fill)
        }
        fill.color = 0xFF131313.toInt(); c.drawRect(4 * bx, h2, w, h, fill)
    }
}
