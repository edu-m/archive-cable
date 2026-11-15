package tv.archivecable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** The channel banner shown for a few seconds after tuning, seeking or pressing INFO. */
class OsdView(context: Context) : View(context) {

    data class Info(
        val channel: ChannelDef,
        val airing: Airing?,
        val next: Airing?,
        val status: String?,
        val isFiller: Boolean,
    )

    private var info: Info? = null
    private var digits: String? = null
    private var seekLabel: String? = null
    private var seekPos = 0L
    private var seekDur = 0L
    private var hideAt = 0L

    private val numPaint = Retro.textPaint(context, 140f, Retro.YELLOW, Paint.Align.CENTER)
    private val callPaint = Retro.textPaint(context, 44f, Retro.WHITE, Paint.Align.CENTER)
    private val titlePaint = Retro.textPaint(context, 60f, Retro.WHITE)
    private val subPaint = Retro.textPaint(context, 40f, Retro.CYAN)
    private val timePaint = Retro.textPaint(context, 38f, Retro.GRAY)
    private val nextPaint = Retro.textPaint(context, 38f, Retro.YELLOW)
    private val clockPaint = Retro.textPaint(context, 44f, Retro.CYAN, Paint.Align.RIGHT)
    private val tagPaint = Retro.textPaint(context, 46f, Retro.NAVY_DARK, Paint.Align.CENTER)
    private val fill = Paint()

    private val ticker = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= hideAt) {
                visibility = GONE
                seekLabel = null
                digits = null
            } else {
                invalidate()
                postDelayed(this, 250)
            }
        }
    }

    init {
        isFocusable = false
        visibility = GONE
    }

    fun showChannel(info: Info, durationMs: Long = 3000) {
        this.info = info
        digits = null
        seekLabel = null
        reveal(durationMs)
    }

    /** Refresh contents without extending the timeout (e.g. listings arrived). */
    fun update(info: Info) {
        if (this.info?.channel?.id == info.channel.id) {
            this.info = info
            invalidate()
        }
    }

    fun showDigits(d: String) {
        digits = d
        reveal(2500)
    }

    fun showSeek(label: String, posMs: Long, durMs: Long, info: Info) {
        this.info = info
        seekLabel = label
        seekPos = posMs
        seekDur = durMs
        reveal(2500)
    }

    fun dismiss() {
        hideAt = 0
        visibility = GONE
    }

    private fun reveal(durationMs: Long) {
        hideAt = System.currentTimeMillis() + durationMs
        visibility = VISIBLE
        removeCallbacks(ticker)
        post(ticker)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inf = info ?: return
        val s = width / 1920f
        canvas.save()
        canvas.scale(s, height / 1080f)
        val now = System.currentTimeMillis()

        val box = RectF(110f, 780f, 1810f, 1010f)
        // Drop shadow + gradient body + double border, like a late-90s digital cable banner.
        fill.color = 0x88000000.toInt()
        canvas.drawRect(box.left + 12, box.top + 12, box.right + 12, box.bottom + 12, fill)
        Retro.gradientPanel(canvas, box, 0xEE1B3A8F.toInt(), 0xEE000A3A.toInt(), Retro.CELL_LIGHT, 4f)
        canvas.drawRect(RectF(box.left + 8, box.top + 8, box.right - 8, box.bottom - 8), Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x664A6FD0
        })

        // Channel number block.
        val numBox = RectF(box.left + 20, box.top + 20, box.left + 300, box.bottom - 20)
        Retro.bevel(canvas, numBox, Retro.NAVY_DARK, Retro.CELL_SHADOW, Retro.CELL_LIGHT, 3f, sunken = true)
        val numText = digits?.let { it.padEnd(2, '-') } ?: Retro.channelNumber(inf.channel.number)
        canvas.drawText(numText, numBox.centerX(), numBox.top + 118, numPaint)
        if (digits == null) {
            val pill = RectF(numBox.left + 24, numBox.bottom - 62, numBox.right - 24, numBox.bottom - 14)
            fill.color = inf.channel.color
            canvas.drawRect(pill, fill)
            canvas.drawText(inf.channel.name, pill.centerX(), pill.bottom - 11, callPaint)
        }

        val x = numBox.right + 34
        val right = box.right - 30
        canvas.drawText(Retro.longTime(now), right, box.top + 62, clockPaint)
        val titleWidth = right - x - clockPaint.measureText(Retro.longTime(now)) - 30

        val a = inf.airing
        if (digits != null) {
            titlePaint.textSize = 60f
            canvas.drawText("${I18n.t("channel")} ${digits}_", x, box.top + 80, titlePaint)
        } else if (a == null) {
            canvas.drawText(Retro.fit(inf.channel.title, titlePaint, titleWidth, 60f, 40f), x, box.top + 72, titlePaint)
            canvas.drawText(inf.status ?: I18n.t("acquiring_listings"), x, box.top + 128, subPaint)
        } else {
            // Long titles (e.g. VHS tapes listing everything on them) shrink before they get cut off.
            canvas.drawText(Retro.fit(a.program.title, titlePaint, titleWidth, 60f, 38f), x, box.top + 72, titlePaint)
            val sub = when {
                inf.isFiller -> "${I18n.t("commercial_break")}   ${inf.channel.title}"
                else -> listOfNotNull(a.program.episode, a.program.year, inf.channel.title).joinToString("  *  ")
            }
            canvas.drawText(Retro.ellipsize(sub, subPaint, right - x), x, box.top + 122, subPaint)

            val barTop = box.top + 145
            if (seekLabel != null && seekDur > 0) {
                val label = "${Retro.clock(seekPos)} / ${Retro.clock(seekDur)}"
                canvas.drawText(label, x, barTop + 30, timePaint)
                val bx = x + timePaint.measureText("000:00 / 000:00") + 20
                Retro.progressBar(canvas, RectF(bx, barTop + 6, right, barTop + 30), seekPos.toFloat() / seekDur, Retro.CYAN)
            } else {
                val range = "${Retro.longTime(a.start)} - ${Retro.longTime(a.end)}"
                canvas.drawText(range, x, barTop + 30, timePaint)
                val bx = x + timePaint.measureText("$range ") + 24
                val left = I18n.t("min_left", ((a.end - now).coerceAtLeast(0) + 59_999) / 60_000)
                val lw = timePaint.measureText(left)
                Retro.progressBar(canvas, RectF(bx, barTop + 6, right - lw - 20, barTop + 30), a.progress(now))
                canvas.drawText(left, right - lw, barTop + 30, timePaint)
            }
            inf.next?.let { n ->
                val nextText = "${I18n.t("next")}  ${Retro.longTime(n.start)}  ${n.program.title}"
                canvas.drawText(Retro.ellipsize(nextText, nextPaint, right - x), x, box.bottom - 22, nextPaint)
            }
        }

        // Seek tag on the top edge of the banner.
        seekLabel?.let { label ->
            val tw = tagPaint.measureText(label) + 40
            val tag = RectF(box.right - tw - 20, box.top - 58, box.right - 20, box.top - 4)
            Retro.bevel(canvas, tag, Retro.YELLOW, 0xFFFFF2A8.toInt(), 0xFF9C7E00.toInt(), 3f)
            canvas.drawText(label, tag.centerX(), tag.bottom - 14, tagPaint)
        }
        canvas.restore()
    }
}
