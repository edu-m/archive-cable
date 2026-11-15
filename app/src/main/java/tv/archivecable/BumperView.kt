package tv.archivecable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Visual identity of a channel's idents ("bumpers"), set per channel in channels_<lang>.json. */
enum class BumperStyle {
    NEWS, LEADER, TERMINAL, WARP, TOON, RETRO, VHS, NOIR, CURTAIN, NEON;

    companion object {
        fun parse(value: String?): BumperStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RETRO
    }
}

/**
 * Animated channel idents played between programs, like real cable networks did:
 * "NOW SHOWING" when a show starts and "WE'LL BE RIGHT BACK" before a commercial break.
 * They cover the moment the next tape is loading, so the schedule never drifts.
 */
class BumperView(context: Context) : View(context) {

    enum class Kind { NEXT, BREAK }

    private var style = BumperStyle.RETRO
    private var kind = Kind.NEXT
    private var channel: ChannelDef? = null
    private var title = ""
    private var sub: String? = null
    private var startedAt = 0L
    private var durationMs = 0L
    private var onDone: (() -> Unit)? = null
    private var seed = 0
    /** Seconds at which the ident ends; pushed back while the next tape is still loading. */
    private var endT = 0f
    private var videoReady = true
    private lateinit var stars: Array<FloatArray>

    val isPlaying get() = visibility == VISIBLE

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pixel = Retro.typeface(context)
    private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isFocusable = false
        visibility = GONE
    }

    fun play(style: BumperStyle, kind: Kind, channel: ChannelDef, title: String, sub: String?, durationMs: Long,
             waitForVideo: Boolean = true, onDone: () -> Unit) {
        this.style = style
        this.kind = kind
        this.channel = channel
        this.title = title
        this.sub = sub
        this.durationMs = durationMs
        this.onDone = onDone
        endT = durationMs / 1000f
        videoReady = !waitForVideo
        seed = (SystemClock.elapsedRealtime() and 0xFFFF).toInt()
        val rnd = Random(seed)
        stars = Array(260) { floatArrayOf(rnd.nextFloat() * 2 * PI.toFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat()) }
        startedAt = SystemClock.elapsedRealtime()
        visibility = VISIBLE
        invalidate()
    }

    /** The next program has put its first frame on screen; the ident may finish. */
    fun markVideoReady() {
        videoReady = true
    }

    /** Stop immediately without invoking the completion callback (e.g. the viewer changed channel). */
    fun cancel() {
        onDone = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val t = elapsed / 1000f
        // Never cut to black: hold the ident (still animating) until the next tape shows a frame.
        if (t > durationMs / 1000f + MAX_HOLD_S) videoReady = true
        if (!videoReady && t > endT - OUTRO_S) endT = t + OUTRO_S
        if (t >= endT) {
            visibility = GONE
            val cb = onDone
            onDone = null
            cb?.invoke()
            return
        }
        val d = endT
        canvas.save()
        canvas.scale(width / 1920f, height / 1080f)
        canvas.clipRect(0f, 0f, 1920f, 1080f)
        when (style) {
            BumperStyle.NEWS -> news(canvas, t)
            BumperStyle.LEADER -> leader(canvas, t)
            BumperStyle.TERMINAL -> terminal(canvas, t)
            BumperStyle.WARP -> warp(canvas, t)
            BumperStyle.TOON -> toon(canvas, t, d)
            BumperStyle.RETRO -> retro(canvas, t)
            BumperStyle.VHS -> vhs(canvas, t)
            BumperStyle.NOIR -> noir(canvas, t)
            BumperStyle.CURTAIN -> curtain(canvas, t)
            BumperStyle.NEON -> neon(canvas, t)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private val ch get() = channel!!
    private val label get() = I18n.t(if (kind == Kind.NEXT) "now_showing" else "right_back")

    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return 1 - (1 - c) * (1 - c) * (1 - c)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                    face: Typeface = pixel, maxWidth: Float = 1700f, outline: Int = 0, glow: Int = 0, alpha: Int = 255) {
        text.typeface = face
        text.textSize = size
        while (text.textSize > size * 0.5f && text.measureText(s) > maxWidth) text.textSize -= 2f
        val shown = Retro.ellipsize(s, text, maxWidth)
        if (outline != 0) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = size / 9
            text.color = outline
            text.alpha = alpha
            text.clearShadowLayer()
            c.drawText(shown, x, y, text)
        }
        text.style = Paint.Style.FILL
        text.color = color
        text.alpha = alpha
        if (glow != 0) text.setShadowLayer(size / 4, 0f, 0f, glow) else text.setShadowLayer(0.01f, 4f, 4f, Color.BLACK)
        c.drawText(shown, x, y, text)
        text.clearShadowLayer()
    }

    private fun alphaIn(t: Float, from: Float, len: Float = 0.3f) = (255 * ((t - from) / len).coerceIn(0f, 1f)).toInt()

    private fun breakLine(): String? = sub

    // ---- styles -------------------------------------------------------------------------------

    private fun news(c: Canvas, t: Float) {
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = LinearGradient(0f, 0f, 0f, 1080f, 0xFF0A1F5C.toInt(), 0xFF01040F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        // Spinning wireframe globe.
        val gx = 1480f; val gy = 470f; val r = 300f
        stroke.color = 0xAA4FC3F7.toInt(); stroke.strokeWidth = 3f
        c.drawCircle(gx, gy, r, stroke)
        for (lat in -2..2) {
            val yy = gy + r * sin(lat * PI / 6).toFloat()
            val rw = r * cos(lat * PI / 6).toFloat()
            c.drawOval(gx - rw, yy - rw * 0.12f, gx + rw, yy + rw * 0.12f, stroke)
        }
        for (k in 0 until 6) {
            val a = t * 0.9f + k * PI.toFloat() / 6
            val rw = abs(r * cos(a))
            c.drawOval(gx - rw, gy - r, gx + rw, gy + r, stroke)
        }
        // Red band sweeping in with the network name.
        val bx = -1920f + 1920f * ease(t / 0.5f)
        fill.color = 0xFFC0392B.toInt()
        c.drawRect(bx, 250f, bx + 1500f, 420f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawRect(bx, 420f, bx + 1500f, 428f, fill)
        txt(c, ch.title.uppercase(I18n.current.locale), bx + 750f, 370f, 96f, Color.WHITE, maxWidth = 1400f)
        txt(c, label, 760f, 580f, 64f, Retro.YELLOW, alpha = alphaIn(t, 0.5f), maxWidth = 1400f)
        txt(c, title, 760f, 690f, 80f, Color.WHITE, alpha = alphaIn(t, 0.8f), maxWidth = 1400f)
        breakLine()?.let { txt(c, it, 760f, 780f, 48f, Retro.CYAN, alpha = alphaIn(t, 1.0f), maxWidth = 1400f) }
        // Ticker.
        fill.color = 0xFF7A1010.toInt()
        c.drawRect(0f, 950f, 1920f, 1030f, fill)
        val tick = "  *  ${ch.title.uppercase(I18n.current.locale)}  *  ${Retro.longTime(System.currentTimeMillis())}  *  ARCHIVE CABLE"
        text.typeface = pixel; text.textSize = 52f
        val tw = text.measureText(tick)
        var x = -((t * 260f) % tw)
        text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
        while (x < 1920f) { c.drawText(tick, x, 1006f, text); x += tw }
        text.textAlign = Paint.Align.CENTER
    }

    private fun leader(c: Canvas, t: Float) {
        val counts = if (kind == Kind.NEXT) 3 else 2
        val rnd = Random(seed + (t * 24).toInt())
        if (t < counts) {
            fill.alpha = 255 // shader output is modulated by paint alpha
            fill.shader = RadialGradient(960f, 540f, 1100f, 0xFFD9C49A.toInt(), 0xFF5C4630.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 1920f, 1080f, fill)
            fill.shader = null
            val frac = t - floor(t)
            fill.color = 0x553A2A18
            c.drawArc(960f - 520, 540f - 520, 960f + 520, 540f + 520, -90f, frac * 360f, true, fill)
            stroke.color = 0xFF2A1E10.toInt(); stroke.strokeWidth = 10f
            c.drawCircle(960f, 540f, 400f, stroke)
            c.drawCircle(960f, 540f, 340f, stroke)
            stroke.strokeWidth = 5f
            c.drawLine(0f, 540f, 1920f, 540f, stroke)
            c.drawLine(960f, 0f, 960f, 1080f, stroke)
            txt(c, (counts - floor(t).toInt()).toString(), 960f, 690f, 420f, 0xFF20160A.toInt(), face = serif)
        } else {
            val flicker = 220 + rnd.nextInt(35)
            fill.color = Color.rgb(12, 10, 8)
            c.drawRect(0f, 0f, 1920f, 1080f, fill)
            txt(c, ch.title.uppercase(I18n.current.locale), 960f, 380f, 70f, Color.argb(flicker, 230, 220, 200), face = serif)
            txt(c, label, 960f, 520f, 50f, Color.argb(flicker, 200, 190, 170), face = serif)
            txt(c, title, 960f, 640f, 84f, Color.argb(flicker, 250, 245, 230), face = serif)
            breakLine()?.let { txt(c, it, 960f, 740f, 46f, Color.argb(flicker, 200, 190, 170), face = serif) }
        }
        // Film grain and scratches.
        fill.color = 0x66000000
        repeat(200) {
            val x = rnd.nextFloat() * 1920; val y = rnd.nextFloat() * 1080
            c.drawRect(x, y, x + 3, y + 3, fill)
        }
        stroke.color = 0x88FFFFFF.toInt(); stroke.strokeWidth = 2f
        repeat(2) { val x = rnd.nextFloat() * 1920; c.drawLine(x, 0f, x + rnd.nextFloat() * 20 - 10, 1080f, stroke) }
    }

    private fun terminal(c: Canvas, t: Float) {
        c.drawColor(0xFF020A04.toInt())
        val green = 0xFF39FF6A.toInt()
        val pct = (ease(t / 2.2f) * 100).toInt()
        val bar = "#".repeat(pct / 10) + ".".repeat(10 - pct / 10)
        val lines = listOf(
            "ARCHIVE CABLE BIOS v1.0  (C) 1994",
            "C:\\> RUN ${ch.name.replace(" ", "")}.EXE",
            "$label...",
            "[$bar] $pct%",
            "> ${title.uppercase(I18n.current.locale)}",
        ) + listOfNotNull(breakLine()?.let { "> ${it.uppercase(I18n.current.locale)}" })
        var budget = (t * 70).toInt()
        text.typeface = pixel; text.textSize = 64f; text.textAlign = Paint.Align.LEFT
        text.color = green; text.setShadowLayer(14f, 0f, 0f, green)
        var y = 260f
        var cursorX = 160f; var cursorY = y
        for (line in lines) {
            if (budget <= 0) break
            val shown = Retro.ellipsize(line.take(budget), text, 1620f)
            c.drawText(shown, 160f, y, text)
            budget -= line.length
            cursorX = 160f + text.measureText(shown); cursorY = y
            y += 100f
        }
        if ((t * 2.5f) % 1f < 0.55f) c.drawRect(cursorX + 8, cursorY - 52, cursorX + 40, cursorY + 6, Paint().apply { color = green })
        text.clearShadowLayer(); text.textAlign = Paint.Align.CENTER
        Retro.scanlines(c, 1920f, 1080f, 6f)
    }

    private fun warp(c: Canvas, t: Float) {
        c.drawColor(Color.BLACK)
        val accel = if (t < 1.6f) 0.15f + t * t * 0.9f else 0.12f
        val travel = if (t < 1.6f) 0.15f * t + 0.3f * t * t * t else 1.4f + (t - 1.6f) * 0.12f
        stroke.strokeCap = Paint.Cap.ROUND
        for (s in stars) {
            val z = (s[1] + travel * s[2]) % 1f
            val rr = z * z * 1300f
            val len = (rr * accel * 0.8f).coerceAtMost(500f)
            val cx = cos(s[0]); val sy = sin(s[0])
            stroke.color = if (s[2] > 1.1f) 0xFFAEE6FF.toInt() else Color.WHITE
            stroke.strokeWidth = 1.5f + z * 4f
            c.drawLine(960 + cx * (rr - len), 540 + sy * (rr - len), 960 + cx * rr, 540 + sy * rr, stroke)
        }
        if (t in 1.55f..2.0f) c.drawColor(Color.argb((255 * (1 - (t - 1.55f) / 0.45f)).toInt(), 255, 255, 255))
        if (t > 1.6f) {
            val spaced = ch.title.uppercase(I18n.current.locale).toCharArray().joinToString(" ")
            txt(c, spaced, 960f, 400f, 76f, 0xFF7FE7FF.toInt(), glow = 0xFF2A7FFF.toInt())
            txt(c, label, 960f, 540f, 54f, Retro.YELLOW, alpha = alphaIn(t, 1.9f))
            txt(c, title, 960f, 650f, 80f, Color.WHITE, alpha = alphaIn(t, 2.1f))
            breakLine()?.let { txt(c, it, 960f, 740f, 46f, Retro.CYAN, alpha = alphaIn(t, 2.2f)) }
        }
    }

    private fun toon(c: Canvas, t: Float, d: Float) {
        c.drawColor(0xFFFFD83A.toInt())
        // Spinning sunburst.
        fill.color = 0xFFFF9F1C.toInt()
        val rays = 16
        for (i in 0 until rays step 2) {
            val a0 = t * 0.6f + i * 2 * PI.toFloat() / rays
            val a1 = a0 + 2 * PI.toFloat() / rays
            val p = Path().apply {
                moveTo(960f, 540f); lineTo(960 + cos(a0) * 2000, 540 + sin(a0) * 2000)
                lineTo(960 + cos(a1) * 2000, 540 + sin(a1) * 2000); close()
            }
            c.drawPath(p, fill)
        }
        // Bouncing balls.
        val colors = intArrayOf(0xFFE63946.toInt(), 0xFF2A9D8F.toInt(), 0xFF457B9D.toInt(), 0xFF8E44AD.toInt(), 0xFF27AE60.toInt(), 0xFFF72585.toInt())
        for (i in colors.indices) {
            fill.color = colors[i]
            val x = 180f + i * 312f
            val y = 1000f - abs(sin(t * 3.2f + i * 0.9f)) * 380f
            c.drawCircle(x, y, 46f + (i % 3) * 12f, fill)
            stroke.color = Color.BLACK; stroke.strokeWidth = 6f
            c.drawCircle(x, y, 46f + (i % 3) * 12f, stroke)
        }
        // Wobbly letters.
        val name = ch.title.uppercase(I18n.current.locale)
        text.typeface = pixel; text.textSize = 120f
        val total = text.measureText(name).coerceAtMost(1800f)
        val scale = if (text.measureText(name) > 1800f) 1800f / text.measureText(name) else 1f
        text.textSize = 120f * scale
        var x = 960f - total / 2
        name.forEachIndexed { i, ch ->
            val s = ch.toString()
            val w = text.measureText(s)
            txt(c, s, x + w / 2, 330f + sin(t * 7f + i * 0.6f) * 26f, 120f * scale, colors[i % colors.size], outline = Color.BLACK, maxWidth = 400f)
            x += w
        }
        val box = RectF(260f, 470f, 1660f, 760f)
        fill.color = Color.WHITE
        c.drawRoundRect(box, 40f, 40f, fill)
        stroke.color = Color.BLACK; stroke.strokeWidth = 8f
        c.drawRoundRect(box, 40f, 40f, stroke)
        txt(c, label, 960f, 560f, 56f, 0xFFE63946.toInt(), maxWidth = 1300f)
        txt(c, title, 960f, 670f, 76f, Color.BLACK, maxWidth = 1300f)
        breakLine()?.let { txt(c, it, 960f, 740f, 40f, 0xFF457B9D.toInt(), maxWidth = 1300f) }
        // Iris out.
        val closeFrom = d - 0.6f
        if (t > closeFrom) {
            val rr = 1200f * (1 - (t - closeFrom) / 0.6f)
            val iris = Path().apply { addCircle(960f, 540f, rr.coerceAtLeast(0f), Path.Direction.CW); fillType = Path.FillType.INVERSE_WINDING }
            fill.color = Color.BLACK
            c.drawPath(iris, fill)
        }
    }

    private fun retro(c: Canvas, t: Float) {
        c.drawColor(0xFF0E5E6F.toInt())
        val arcs = intArrayOf(0xFFE63946.toInt(), 0xFFF4A261.toInt(), 0xFFFFD83A.toInt(), 0xFF2A9D8F.toInt(), 0xFF457B9D.toInt(), 0xFF8E44AD.toInt())
        arcs.forEachIndexed { i, col ->
            val rr = (980f - i * 95f) * ease(t * 1.4f - i * 0.12f)
            fill.color = col
            c.drawArc(960 - rr, 1080 - rr, 960 + rr, 1080 + rr, 180f, 180f, true, fill)
        }
        fill.color = 0xFF0E5E6F.toInt()
        val inner = 980f - arcs.size * 95f
        c.drawArc(960 - inner, 1080 - inner, 960 + inner, 1080 + inner, 180f, 180f, true, fill)
        // Twinkling stars.
        fill.color = Color.WHITE
        for (i in 0 until 9) {
            val sx = 160f + (i * 211f) % 1600f
            val sy = 90f + (i * 97f) % 260f
            val s = 10f + 16f * abs(sin(t * 3f + i))
            val p = Path().apply {
                moveTo(sx, sy - s); lineTo(sx + s / 4, sy - s / 4); lineTo(sx + s, sy); lineTo(sx + s / 4, sy + s / 4)
                lineTo(sx, sy + s); lineTo(sx - s / 4, sy + s / 4); lineTo(sx - s, sy); lineTo(sx - s / 4, sy - s / 4); close()
            }
            c.drawPath(p, fill)
        }
        txt(c, ch.title.uppercase(I18n.current.locale), 960f, 300f, 100f, Color.WHITE, outline = Color.BLACK)
        txt(c, label, 960f, 420f, 56f, Retro.YELLOW, outline = Color.BLACK, alpha = alphaIn(t, 0.5f))
        txt(c, title, 960f, 530f, 80f, Color.WHITE, outline = Color.BLACK, alpha = alphaIn(t, 0.7f))
        breakLine()?.let { txt(c, it, 960f, 620f, 46f, Color.WHITE, outline = Color.BLACK, alpha = alphaIn(t, 0.9f)) }
    }

    private fun vhs(c: Canvas, t: Float) {
        val rnd = Random(seed + (t * 30).toInt())
        if (t < 0.35f) {
            c.drawColor(Color.DKGRAY)
            fill.color = Color.WHITE
            repeat(1500) { val x = rnd.nextFloat() * 1920; val y = rnd.nextFloat() * 1080; c.drawRect(x, y, x + 6, y + 4, fill) }
            return
        }
        c.drawColor(0xFF0018B0.toInt())
        val jitter = rnd.nextFloat() * 8 - 4
        text.typeface = pixel; text.textAlign = Paint.Align.LEFT; text.textSize = 80f; text.color = Color.WHITE
        text.setShadowLayer(0.01f, 4f, 4f, Color.BLACK)
        c.drawText("PLAY \u25B6", 120f + jitter, 160f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("SP", 1800f + jitter, 160f, text)
        c.drawText("0:00:%02d".format((t * 1).toInt()), 1800f + jitter, 990f, text)
        text.textAlign = Paint.Align.CENTER
        text.clearShadowLayer()
        txt(c, ch.title.uppercase(I18n.current.locale), 960f + jitter, 430f, 84f, Color.WHITE)
        txt(c, label, 960f + jitter, 540f, 56f, Retro.YELLOW)
        txt(c, title, 960f + jitter, 650f, 76f, Color.WHITE)
        breakLine()?.let { txt(c, it, 960f + jitter, 740f, 44f, Retro.CYAN) }
        // Tracking noise bands rolling down the picture.
        fill.color = 0xCCFFFFFF.toInt()
        for (k in 0 until 2) {
            val by = (t * 420f + k * 560f) % 1200f - 60f
            repeat(90) {
                val x = rnd.nextFloat() * 1920; val y = by + rnd.nextFloat() * 36
                c.drawRect(x, y, x + 20 + rnd.nextFloat() * 70, y + 3, fill)
            }
        }
    }

    private fun noir(c: Canvas, t: Float) {
        c.drawColor(0xFF070707.toInt())
        c.save()
        c.rotate(-18f, 960f, 540f)
        fill.color = 0x26FFFFFF
        val off = (t * 40f) % 130f
        for (i in -6..14) c.drawRect(-400f + i * 130f + off, -600f, -400f + i * 130f + off + 70f, 1700f, fill)
        c.restore()
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = RadialGradient(960f, 540f, 1100f, 0x00000000, 0xEE000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        val a = alphaIn(t, 0.2f, 0.8f)
        txt(c, ch.title, 960f, 380f, 84f, 0xFFEDEDED.toInt(), face = serif, alpha = a)
        stroke.color = Color.argb(a, 200, 200, 200); stroke.strokeWidth = 3f
        c.drawLine(660f, 420f, 1260f, 420f, stroke)
        txt(c, label, 960f, 520f, 44f, 0xFFB0B0B0.toInt(), face = serif, alpha = alphaIn(t, 0.8f, 0.8f))
        txt(c, title, 960f, 640f, 78f, Color.WHITE, face = serif, alpha = alphaIn(t, 1.2f, 0.8f))
        breakLine()?.let { txt(c, it, 960f, 730f, 42f, 0xFFB0B0B0.toInt(), face = serif, alpha = alphaIn(t, 1.4f, 0.8f)) }
    }

    private fun curtain(c: Canvas, t: Float) {
        c.drawColor(Color.BLACK)
        val opening = kind == Kind.NEXT
        val open = if (opening) ease((t - 0.3f) / 1.4f) else 1 - ease(t / 1.1f)
        // Spotlight on stage.
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = RadialGradient(960f, 600f, 560f, Color.argb((150 * open).toInt(), 255, 240, 200), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        val gold = 0xFFE8C35A.toInt()
        if (opening) {
            txt(c, I18n.t("feature_presentation"), 960f, 440f, 92f, gold, face = serif, alpha = alphaIn(t, 1.1f, 0.5f))
            txt(c, title, 960f, 580f, 76f, Color.WHITE, face = serif, alpha = alphaIn(t, 1.4f, 0.5f))
            txt(c, ch.title, 960f, 680f, 44f, 0xFFCFC6B0.toInt(), face = serif, alpha = alphaIn(t, 1.6f, 0.5f))
        }
        // Velvet curtains.
        val panel = 960f * (1 - open * 0.88f)
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = LinearGradient(0f, 0f, 90f, 0f, intArrayOf(0xFF5A0000.toInt(), 0xFFB3121B.toInt(), 0xFF5A0000.toInt()), null, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, panel, 1080f, fill)
        c.drawRect(1920f - panel, 0f, 1920f, 1080f, fill)
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = LinearGradient(0f, 0f, 0f, 140f, 0xFF8A0A12.toInt(), 0xFF3A0000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 140f, fill)
        fill.shader = null
        fill.color = gold
        c.drawRect(0f, 140f, 1920f, 152f, fill)
        if (!opening) {
            txt(c, I18n.t("intermission"), 960f, 560f, 110f, gold, face = serif, outline = 0xFF3A0000.toInt(), alpha = alphaIn(t, 0.9f, 0.4f))
            breakLine()?.let { txt(c, it, 960f, 680f, 48f, Color.WHITE, face = serif, alpha = alphaIn(t, 1.2f, 0.4f)) }
        }
    }

    private fun neon(c: Canvas, t: Float) {
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = LinearGradient(0f, 0f, 0f, 640f, 0xFF0B0018.toInt(), 0xFF3A0A5E.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 640f, fill)
        // Striped sunset.
        fill.alpha = 255 // shader output is modulated by paint alpha
        fill.shader = LinearGradient(0f, 420f, 0f, 640f, 0xFFFFD83A.toInt(), 0xFFFF2E88.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(960f, 640f, 230f, fill)
        fill.shader = null
        fill.color = 0xFF2A0846.toInt()
        for (i in 0 until 5) c.drawRect(700f, 560f + i * 18f, 1220f, 566f + i * 18f + i * 2f, fill)
        // Perspective grid floor.
        fill.color = 0xFF0B0018.toInt()
        c.drawRect(0f, 640f, 1920f, 1080f, fill)
        stroke.color = 0xFFFF2EC4.toInt(); stroke.strokeWidth = 3f
        for (i in -12..12) c.drawLine(960f + i * 40f, 640f, 960f + i * 300f, 1080f, stroke)
        for (k in 0 until 10) {
            val z = ((k + (t * 1.8f) % 1f) / 10f)
            val y = 640f + z * z * 440f
            c.drawLine(0f, y, 1920f, y, stroke)
        }
        // Memphis shapes.
        stroke.strokeWidth = 8f
        stroke.color = 0xFF2EF2FF.toInt()
        val zig = Path().apply { moveTo(180f, 200f + sin(t * 2) * 30); for (i in 1..6) lineTo(180f + i * 40f, (if (i % 2 == 0) 200f else 150f) + sin(t * 2) * 30) }
        c.drawPath(zig, stroke)
        stroke.color = Retro.YELLOW
        c.save(); c.rotate(t * 90f, 1700f, 220f)
        c.drawPath(Path().apply { moveTo(1700f, 160f); lineTo(1760f, 270f); lineTo(1640f, 270f); close() }, stroke)
        c.restore()
        stroke.color = 0xFFFF2EC4.toInt()
        c.drawCircle(300f, 520f + cos(t * 2.5f) * 20, 34f, stroke)
        c.drawRect(1580f, 470f + sin(t * 2.2f) * 25, 1650f, 540f + sin(t * 2.2f) * 25, stroke)
        txt(c, ch.title.uppercase(I18n.current.locale), 960f, 190f, 104f, 0xFFFFE6FA.toInt(), glow = 0xFFFF2EC4.toInt())
        txt(c, label, 960f, 290f, 56f, 0xFF2EF2FF.toInt(), glow = 0xFF2EF2FF.toInt(), alpha = alphaIn(t, 0.4f))
        txt(c, title, 960f, 390f, 72f, Color.WHITE, alpha = alphaIn(t, 0.6f), maxWidth = 1500f)
        breakLine()?.let { txt(c, it, 960f, 470f, 42f, Retro.YELLOW, alpha = alphaIn(t, 0.8f)) }
        Retro.scanlines(c, 1920f, 1080f, 5f)
    }

    companion object {
        private const val OUTRO_S = 0.7f
        private const val MAX_HOLD_S = 10f

        /** How long each kind of bumper runs, per style. */
        fun durationMs(style: BumperStyle, kind: Kind, shortForm: Boolean): Long = when {
            kind == Kind.BREAK -> if (style == BumperStyle.LEADER) 3200 else 3000
            style == BumperStyle.LEADER -> 5200
            style == BumperStyle.WARP -> 5000
            shortForm -> 3500
            else -> 4600
        }
    }
}
