package tv.archivecable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * A 90s digital-cable style program guide: live preview window and program details on top,
 * a 90-minute channel grid below with a moving "now" sweep line and completion bars.
 * Everything is drawn in a virtual 1920x1080 space and scaled to the screen.
 */
class GuideView(context: Context, private val repo: ChannelRepository) : View(context) {

    var onTune: ((channelIndex: Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    /** Hidden SETUP menu (LEFT x3 while already at the current program): language, picture, transitions. */
    data class SetupValues(
        val language: String,
        val aspect: AspectMode,
        val transitions: Boolean,
        /** Automatic volume leveling. */
        val audioLeveling: Boolean,
        /** Whole-picture size in percent (CRT overscan compensation). */
        val overscan: Int,
        /** Whole-picture shift in tenths of a percent of the screen width/height. */
        val offsetX: Int,
        val offsetY: Int,
        val decades: DecadeFilter,
    )
    var setupProvider: (() -> SetupValues)? = null
    /** Live preview while browsing (picture size), and restore on cancel. */
    var onSetupPreview: ((SetupValues) -> Unit)? = null
    var onSetupSave: ((SetupValues) -> Unit)? = null
    var versionName: String = ""
    private var setupOriginal: SetupValues? = null
    private var setupPending: SetupValues? = null
    private var setupRow = 0
    private var setupTop = 0

    // ---- Touch (phones/tablets) ---------------------------------------------------------------
    /** True after a touch, false after a remote key: switches hints and shows on-screen buttons. */
    private var touchMode = false
    private class Hit(val rect: RectF, val row: Int, val start: Long)
    private val hits = ArrayList<Hit>()
    private val setupRowRects = ArrayList<Pair<Int, RectF>>()
    private var setupBox = RectF()
    private var saveButton = RectF()
    private var cancelButton = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x * 1920f / width, e.y * 1080f / height)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val dx = (e2.x - (e1?.x ?: e2.x)) * 1920f / width
            val dy = (e2.y - (e1?.y ?: e2.y)) * 1080f / height
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < SWIPE_MIN) return false
            onSwipe(dx, dy)
            return true
        }
    })

    private var leftBumps = 0
    private var lastLeftBump = 0L
    private var pickerOpen = false

    private var selRow = 0
    private var topRow = 0
    private var selTime = 0L
    private var windowStart = 0L

    private val channels get() = repo.channels

    private val fill = Paint()
    private val linePaint = Paint().apply { color = Retro.RED; strokeWidth = 4f }
    private val clockPaint = Retro.textPaint(context, 52f, Retro.YELLOW, Paint.Align.RIGHT)
    private val dayPaint = Retro.textPaint(context, 34f, Retro.CYAN, Paint.Align.RIGHT)
    private val infoChan = Retro.textPaint(context, 44f, Retro.YELLOW)
    private val infoTitle = Retro.textPaint(context, 64f, Retro.WHITE)
    private val infoSub = Retro.textPaint(context, 40f, Retro.CYAN)
    private val infoTime = Retro.textPaint(context, 38f, Retro.GRAY)
    private val infoDesc = Retro.textPaint(context, 34f, 0xFFD8DCEB.toInt()).apply { clearShadowLayer() }
    private val headerText = Retro.textPaint(context, 40f, Retro.WHITE)
    private val nowTabText = Retro.textPaint(context, 30f, Retro.WHITE, Paint.Align.CENTER)
    private val chanNum = Retro.textPaint(context, 50f, Retro.YELLOW)
    private val chanName = Retro.textPaint(context, 36f, Retro.WHITE)
    private val cellText = Retro.textPaint(context, 38f, Retro.WHITE)
    private val cellTextSel = Retro.textPaint(context, 38f, Retro.NAVY_DARK).apply { clearShadowLayer() }
    private val cellSub = Retro.textPaint(context, 28f, Retro.GRAY)
    private val cellSubSel = Retro.textPaint(context, 28f, 0xFF3A2E00.toInt()).apply { clearShadowLayer() }
    private val hintText = Retro.textPaint(context, 30f, Retro.GRAY)
    private val brand = Retro.textPaint(context, 40f, Retro.WHITE)
    private val pickerTitle = Retro.textPaint(context, 48f, Retro.WHITE)

    private val ticker = object : Runnable {
        override fun run() {
            if (visibility != VISIBLE) return
            val now = System.currentTimeMillis()
            // Keep the window from drifting into the past as time passes.
            if (windowStart < Retro.floorToHalfHour(now)) windowStart = Retro.floorToHalfHour(now)
            if (selTime < now) selTime = now
            invalidate()
            postDelayed(this, 1000)
        }
    }

    init {
        isFocusable = false
        visibility = GONE
    }

    fun open(currentIndex: Int) {
        val now = System.currentTimeMillis()
        selRow = currentIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        selTime = now
        windowStart = Retro.floorToHalfHour(now)
        topRow = (selRow - 2).coerceIn(0, (channels.size - VISIBLE_ROWS).coerceAtLeast(0))
        visibility = VISIBLE
        removeCallbacks(ticker)
        post(ticker)
        invalidate()
    }

    fun close() {
        visibility = GONE
        if (pickerOpen) setupOriginal?.let { onSetupPreview?.invoke(it) }
        pickerOpen = false
        leftBumps = 0
        removeCallbacks(ticker)
    }

    val isOpen get() = visibility == VISIBLE

    /** Where the live video should sit, in view pixels. */
    fun previewRect(viewW: Int, viewH: Int): Rect {
        val sx = viewW / 1920f
        val sy = viewH / 1080f
        return Rect((PREVIEW.left * sx).toInt(), (PREVIEW.top * sy).toInt(), (PREVIEW.right * sx).toInt(), (PREVIEW.bottom * sy).toInt())
    }

    fun refresh() = invalidate()

    /** One box in the grid. Short-form channels (slot 0) are shown as half-hour blocks of several programs. */
    private class Cell(val start: Long, val end: Long, val title: String, val sub: String?, val current: Airing?)

    private fun isBlockChannel(ch: ChannelDef) = ch.slotMinutes == 0

    private fun isLong(a: Airing) = a.end - a.start >= 20 * 60_000L

    private fun cellAt(ch: ChannelDef, sched: Schedule, t: Long): Cell? {
        val now = System.currentTimeMillis()
        if (!isBlockChannel(ch)) {
            val a = sched.airingAt(t) ?: return null
            return Cell(a.start, a.end, a.program.title, a.program.episode, a.takeIf { now in it.start until it.end })
        }
        val at = sched.airingAt(t) ?: return null
        if (isLong(at)) return Cell(at.start, at.end, at.program.title, at.program.episode, at.takeIf { now in it.start until it.end })
        // Short items are grouped into the half hour, trimmed so they never overlap a long program's own cell.
        var start = Retro.floorToHalfHour(t)
        var end = start + 30 * 60_000L
        for (a in sched.airings(start, end)) if (isLong(a)) {
            if (a.start <= t) start = maxOf(start, a.end) else end = minOf(end, a.start)
        }
        val airings = sched.airings(start, end).filter { !isLong(it) }
        if (airings.isEmpty()) return null
        val lead = sched.airingAt(maxOf(start, minOf(now, end - 1)))?.takeIf { !isLong(it) } ?: airings.first()
        val others = airings.filter { it.start != lead.start }.map { it.program.title }.distinct()
        val sub = others.take(6).joinToString("  *  ").ifEmpty { null }
        return Cell(start, end, lead.program.title, sub, lead.takeIf { now in it.start until it.end })
    }

    private fun cells(ch: ChannelDef, sched: Schedule, from: Long, to: Long): List<Cell> {
        val out = ArrayList<Cell>()
        var t = from
        while (t < to && out.size < 64) {
            val cell = cellAt(ch, sched, t) ?: break
            out += cell
            t = cell.end
        }
        return out
    }

    private fun selectedCell(): Cell? = channels.getOrNull(selRow)?.let { ch -> repo.schedule(ch.id)?.let { cellAt(ch, it, selTime) } }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return false
        if (!touchMode) { touchMode = true; invalidate() }
        gestures.onTouchEvent(event)
        return true
    }

    private fun onTap(x: Float, y: Float) {
        if (pickerOpen) {
            when {
                saveButton.contains(x, y) -> handlePickerKey(KeyEvent.KEYCODE_ENTER)
                cancelButton.contains(x, y) -> handlePickerKey(KeyEvent.KEYCODE_BACK)
                !setupBox.contains(x, y) -> handlePickerKey(KeyEvent.KEYCODE_BACK)
                else -> setupRowRects.firstOrNull { it.second.contains(x, y) }?.let { (row, r) ->
                    if (row != setupRow) setupRow = row
                    else handlePickerKey(if (x < r.centerX()) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            invalidate()
            return
        }
        if (PREVIEW.contains(x, y)) { onTune?.invoke(selRow); return }
        val hit = hits.lastOrNull { it.rect.contains(x, y) } ?: return
        val now = System.currentTimeMillis()
        if (hit.start < 0) {
            // Channel name: tap once to select the row, again to watch it.
            if (hit.row == selRow) { onTune?.invoke(hit.row); return }
            selRow = hit.row
            selTime = now
        } else {
            // Program: tap once to see its details, again to watch the channel.
            if (hit.row == selRow && selectedCell()?.start == hit.start) { onTune?.invoke(hit.row); return }
            selRow = hit.row
            selTime = maxOf(hit.start, now)
            ensureVisible()
        }
        invalidate()
    }

    private fun onSwipe(dx: Float, dy: Float) {
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (pickerOpen) {
            if (horizontal) handlePickerKey(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            else repeat(3) { handlePickerKey(if (dy < 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        } else if (horizontal) {
            if (dx < 0) openSetup() else {
                val now = System.currentTimeMillis()
                selTime = now
                windowStart = Retro.floorToHalfHour(now)
            }
        } else {
            moveRow(if (dy < 0) 3 else -3) // finger up scrolls down the channel list
        }
        invalidate()
    }

    private fun openSetup() {
        setupProvider?.invoke()?.let {
            setupOriginal = it
            setupPending = it
            setupRow = 0
            setupTop = 0
            pickerOpen = true
        }
    }

    fun handleKey(keyCode: Int): Boolean {
        val now = System.currentTimeMillis()
        if (touchMode) touchMode = false
        if (pickerOpen) return handlePickerKey(keyCode)
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) leftBumps = 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-VISIBLE_ROWS)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(VISIBLE_ROWS)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val cell = selectedCell()
                selTime = cell?.end ?: (selTime + 30 * 60_000L)
                selTime = selTime.coerceAtMost(now + MAX_AHEAD_MS)
                ensureVisible()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val cell = selectedCell()
                selTime = ((cell?.start ?: selTime) - 1).coerceAtLeast(now)
                ensureVisible()
                // Pushing against the left edge three times reveals the language menu.
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && selectedCell()?.start == cell?.start) {
                    leftBumps = if (now - lastLeftBump < 1500) leftBumps + 1 else 1
                    lastLeftBump = now
                    if (leftBumps >= 3) {
                        leftBumps = 0
                        openSetup()
                    }
                } else leftBumps = 0
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onTune?.invoke(selRow)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE -> onClose?.invoke()
            else -> return false
        }
        invalidate()
        return true
    }

    private fun handlePickerKey(keyCode: Int): Boolean {
        val cur = setupPending ?: return false.also { pickerOpen = false }
        fun update(next: SetupValues) {
            setupPending = next
            onSetupPreview?.invoke(next)
        }
        fun change(dir: Int) = update(when (setupRow) {
            ROW_LANGUAGE -> {
                val langs = I18n.LANGUAGES
                val i = langs.indexOfFirst { it.code == cur.language }.coerceAtLeast(0)
                cur.copy(language = langs[Math.floorMod(i + dir, langs.size)].code)
            }
            ROW_PICTURE -> {
                val modes = AspectMode.entries
                cur.copy(aspect = modes[Math.floorMod(cur.aspect.ordinal + dir, modes.size)])
            }
            ROW_TRANSITIONS -> cur.copy(transitions = !cur.transitions)
            ROW_AUDIO -> cur.copy(audioLeveling = !cur.audioLeveling)
            ROW_OVERSCAN -> cur.copy(overscan = (cur.overscan + dir).coerceIn(Settings.OVERSCAN_MIN, Settings.OVERSCAN_MAX))
            ROW_OFFSET_X -> cur.copy(offsetX = (cur.offsetX + dir * Settings.OFFSET_STEP).coerceIn(-Settings.OFFSET_MAX, Settings.OFFSET_MAX))
            ROW_OFFSET_Y -> cur.copy(offsetY = (cur.offsetY + dir * Settings.OFFSET_STEP).coerceIn(-Settings.OFFSET_MAX, Settings.OFFSET_MAX))
            else -> cur.copy(decades = toggleDecadeRow(cur.decades, setupRow, null))
        })
        fun reset() = update(when (setupRow) {
            ROW_PICTURE -> cur.copy(aspect = AspectMode.AUTO)
            ROW_TRANSITIONS -> cur.copy(transitions = true)
            ROW_AUDIO -> cur.copy(audioLeveling = true)
            ROW_OVERSCAN -> cur.copy(overscan = 100)
            ROW_OFFSET_X -> cur.copy(offsetX = 0)
            ROW_OFFSET_Y -> cur.copy(offsetY = 0)
            ROW_LANGUAGE -> cur
            else -> cur.copy(decades = toggleDecadeRow(cur.decades, setupRow, true))
        })
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> setupRow = Math.floorMod(setupRow - 1, SETUP_ROWS)
            KeyEvent.KEYCODE_DPAD_DOWN -> setupRow = Math.floorMod(setupRow + 1, SETUP_ROWS)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> setupRow = (setupRow - VISIBLE_SETUP_ROWS).coerceAtLeast(0)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> setupRow = (setupRow + VISIBLE_SETUP_ROWS).coerceAtMost(SETUP_ROWS - 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> change(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> change(1)
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> reset()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                pickerOpen = false
                onSetupSave?.invoke(cur)
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                pickerOpen = false
                setupOriginal?.let { onSetupPreview?.invoke(it) }
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {} // swallow: no channel entry while in setup
            else -> return false
        }
        invalidate()
        return true
    }

    /** Tick/untick the decade (or "undated") on [row]; [force] sets instead of toggling. Never leaves nothing on air. */
    private fun toggleDecadeRow(f: DecadeFilter, row: Int, force: Boolean?): DecadeFilter {
        val next = if (row == ROW_UNDATED) {
            f.copy(undated = force ?: !f.undated)
        } else {
            val d = DecadeFilter.ALL[row - ROW_DECADE_FIRST]
            val on = force ?: (d !in f.decades)
            f.copy(decades = if (on) f.decades + d else f.decades - d)
        }
        return if (next.decades.isEmpty() && !next.undated) f else next
    }

    private fun decadeLabel(row: Int): String =
        if (row == ROW_UNDATED) I18n.t("undated") else {
            val d = DecadeFilter.ALL[row - ROW_DECADE_FIRST]
            when (d) {
                DecadeFilter.ALL.first() -> I18n.t("decade_first", d, d % 100)
                DecadeFilter.ALL.last() -> I18n.t("decade_last", d, d % 100)
                else -> I18n.t(if (d >= 2000) "decade_2000s" else "decade", d, d % 100)
            }
        }

    private fun pct(tenths: Int) = (if (tenths > 0) "+" else if (tenths < 0) "-" else "") +
        "${kotlin.math.abs(tenths) / 10}.${kotlin.math.abs(tenths) % 10}%"

    private fun drawPicker(c: Canvas) {
        val v = setupPending ?: return
        val geometry = setupRow in ROW_OVERSCAN..ROW_OFFSET_Y
        val w = 1180f
        val rowStep = 74f
        if (setupRow < setupTop) setupTop = setupRow
        if (setupRow >= setupTop + VISIBLE_SETUP_ROWS) setupTop = setupRow - VISIBLE_SETUP_ROWS + 1
        val buttonsH = if (touchMode) 84f else 0f
        val h = 290f + VISIBLE_SETUP_ROWS * rowStep + buttonsH
        val box = RectF(960 - w / 2, 540 - h / 2, 960 + w / 2, 540 + h / 2)
        setupBox = box
        setupRowRects.clear()
        fill.color = if (geometry) 0x66000000 else 0xAA000000.toInt()
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        if (geometry) drawCalibration(c)
        fill.color = 0xAA000000.toInt()
        c.drawRect(box.left + 14, box.top + 14, box.right + 14, box.bottom + 14, fill)
        Retro.gradientPanel(c, box, 0xFF1B3A8F.toInt(), 0xFF000A3A.toInt(), Retro.CELL_LIGHT, 4f)
        val header = RectF(box.left + 12, box.top + 12, box.right - 12, box.top + 82)
        Retro.bevel(c, header, Retro.HEADER, Retro.HEADER_LIGHT, Retro.CELL_SHADOW)
        c.drawText(I18n.t("setup"), header.left + 24, header.bottom - 20, pickerTitle)
        val pos = "${if (setupTop > 0) "^ " else ""}${setupRow + 1}/$SETUP_ROWS${if (setupTop + VISIBLE_SETUP_ROWS < SETUP_ROWS) " v" else ""}"
        c.drawText(pos, header.right - 24 - pickerTitle.measureText(pos), header.bottom - 20, pickerTitle)
        val rows = listOf(
            I18n.t("row_language") to (I18n.LANGUAGES.firstOrNull { it.code == v.language }?.label ?: v.language),
            I18n.t("row_picture") to I18n.t(v.aspect.labelKey),
            I18n.t("row_transitions") to I18n.t(if (v.transitions) "on" else "off"),
            I18n.t("row_audio") to I18n.t(if (v.audioLeveling) "on" else "off"),
            I18n.t("row_overscan") to "${v.overscan}%",
            I18n.t("row_offset_x") to pct(v.offsetX),
            I18n.t("row_offset_y") to pct(v.offsetY),
        ) + (ROW_DECADE_FIRST..ROW_UNDATED).map { row ->
            val on = if (row == ROW_UNDATED) v.decades.undated else DecadeFilter.ALL[row - ROW_DECADE_FIRST] in v.decades.decades
            decadeLabel(row) to (if (on) "[X]" else "[ ]")
        }
        rows.forEachIndexed { i, (label, value) ->
            if (i < setupTop || i >= setupTop + VISIBLE_SETUP_ROWS) return@forEachIndexed
            val top = header.bottom + 20 + (i - setupTop) * rowStep
            val r = RectF(box.left + 30, top, box.right - 30, top + 64)
            setupRowRects += i to r
            val sel = i == setupRow
            if (sel) Retro.bevel(c, r, Retro.YELLOW, 0xFFFFF2A8.toInt(), 0xFF9C7E00.toInt())
            else Retro.bevel(c, r, Retro.CELL, Retro.CELL_LIGHT, Retro.CELL_SHADOW)
            val tp = if (sel) cellTextSel else cellText
            tp.textSize = 42f
            c.drawText(label, r.left + 24, r.bottom - 17, tp)
            val shown = if (sel) "<  $value  >" else value
            c.drawText(shown, r.right - 24 - tp.measureText(shown), r.bottom - 17, tp)
            tp.textSize = 38f
        }
        val hint = when (setupRow) {
            ROW_LANGUAGE -> I18n.t("language_note")
            ROW_PICTURE -> I18n.t(v.aspect.hintKey)
            ROW_TRANSITIONS -> I18n.t("transitions_hint")
            ROW_AUDIO -> I18n.t("audio_hint")
            ROW_OVERSCAN -> I18n.t("overscan_hint")
            ROW_OFFSET_X, ROW_OFFSET_Y -> I18n.t("offset_hint")
            else -> I18n.t("decades_hint")
        }
        c.drawText(hint, box.left + 40, box.bottom - 106 - buttonsH, Retro.textPaint(context, 34f, Retro.CYAN))
        if (touchMode) {
            // On-screen buttons for touch screens (a remote uses OK / BACK instead).
            val bTop = box.bottom - 76 - buttonsH
            cancelButton = RectF(box.left + 30, bTop, 960f - 10f, bTop + 70)
            saveButton = RectF(960f + 10f, bTop, box.right - 30, bTop + 70)
            Retro.bevel(c, cancelButton, Retro.CELL, Retro.CELL_LIGHT, Retro.CELL_SHADOW)
            Retro.bevel(c, saveButton, Retro.YELLOW, 0xFFFFF2A8.toInt(), 0xFF9C7E00.toInt())
            cellText.textSize = 42f; cellTextSel.textSize = 42f
            c.drawText(I18n.t("cancel"), cancelButton.centerX() - cellText.measureText(I18n.t("cancel")) / 2, cancelButton.bottom - 22, cellText)
            c.drawText(I18n.t("save"), saveButton.centerX() - cellTextSel.measureText(I18n.t("save")) / 2, saveButton.bottom - 22, cellTextSel)
            cellText.textSize = 38f; cellTextSel.textSize = 38f
            c.drawText(I18n.t("setup_hint_touch"), box.left + 40, box.bottom - 54, hintText)
        } else {
            cancelButton = RectF(); saveButton = RectF()
            c.drawText(I18n.t("setup_hint"), box.left + 40, box.bottom - 64, hintText)
        }
        val about = Retro.textPaint(context, 30f, 0xFF8090B8.toInt())
        c.drawText(Retro.ellipsize("ARCHIVE CABLE $versionName   ${I18n.t("about")}", about, w - 80), box.left + 40, box.bottom - 24, about)
    }

    /** Service-menu style edge markers: everything inside the yellow frame must be visible on the CRT. */
    private fun drawCalibration(c: Canvas) {
        val edge = Paint().apply { style = Paint.Style.STROKE; color = Retro.YELLOW; strokeWidth = 8f }
        c.drawRect(4f, 4f, 1916f, 1076f, edge)
        edge.strokeWidth = 14f
        edge.color = Retro.RED
        val arm = 120f
        for ((x, y, dx, dy) in listOf(
            listOf(7f, 7f, 1f, 1f), listOf(1913f, 7f, -1f, 1f), listOf(7f, 1073f, 1f, -1f), listOf(1913f, 1073f, -1f, -1f),
        )) {
            c.drawLine(x, y, x + dx * arm, y, edge)
            c.drawLine(x, y, x, y + dy * arm, edge)
        }
        edge.strokeWidth = 3f
        edge.color = 0xAAFFFFFF.toInt()
        c.drawLine(960f, 0f, 960f, 1080f, edge)
        c.drawLine(0f, 540f, 1920f, 540f, edge)
        // Title-safe guide (90%), where all important text lives.
        edge.color = 0x667FE7FF
        c.drawRect(96f, 54f, 1824f, 1026f, edge)
    }

    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        selRow = (selRow + delta).coerceIn(0, channels.size - 1)
        if (selRow < topRow) topRow = selRow
        if (selRow >= topRow + VISIBLE_ROWS) topRow = selRow - VISIBLE_ROWS + 1
    }

    private fun ensureVisible() {
        val now = System.currentTimeMillis()
        val focus = maxOf(selectedCell()?.start ?: selTime, now)
        if (focus < windowStart || focus >= windowStart + WINDOW_MS) {
            windowStart = maxOf(Retro.floorToHalfHour(focus), Retro.floorToHalfHour(now))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        canvas.save()
        canvas.scale(width / 1920f, height / 1080f)

        // Background everywhere except the live preview hole.
        canvas.save()
        canvas.clipOutRect(PREVIEW)
        Retro.gradientPanel(canvas, RectF(0f, 0f, 1920f, 1080f), Retro.NAVY, Retro.NAVY_DARK, Retro.NAVY_DARK, 0f)
        canvas.restore()
        drawPreviewFrame(canvas)
        drawInfo(canvas, now)
        drawGrid(canvas, now)
        if (pickerOpen) drawPicker(canvas)
        Retro.scanlines(canvas, 1920f, 1080f)
        canvas.restore()
    }

    private fun drawPreviewFrame(c: Canvas) {
        val outer = RectF(PREVIEW.left - 10, PREVIEW.top - 10, PREVIEW.right + 10, PREVIEW.bottom + 10)
        fill.color = Retro.CELL_LIGHT
        c.save()
        c.clipOutRect(PREVIEW)
        Retro.bevel(c, outer, Retro.CELL, Retro.CELL_LIGHT, Retro.CELL_SHADOW, 4f)
        c.restore()
    }

    private fun drawInfo(c: Canvas, now: Long) {
        val panel = RectF(PREVIEW.right + 30, PREVIEW.top - 10, 1870f, PREVIEW.bottom + 10)
        Retro.gradientPanel(c, panel, 0xFF102C7A.toInt(), 0xFF061347.toInt(), Retro.CELL_LIGHT, 3f)
        val x = panel.left + 28
        val right = panel.right - 28

        c.drawText(Retro.longTime(now), right, panel.top + 58, clockPaint)
        c.drawText(Retro.day(now), right, panel.top + 96, dayPaint)

        val ch = channels.getOrNull(selRow) ?: return
        val clockW = maxOf(clockPaint.measureText(Retro.longTime(now)), dayPaint.measureText(Retro.day(now))) + 30
        c.drawText(Retro.ellipsize("${Retro.channelNumber(ch.number)}  ${ch.name}  ${ch.title.uppercase()}", infoChan, right - x - clockW), x, panel.top + 56, infoChan)

        val sched = repo.schedule(ch.id)
        val a = sched?.airingAt(selTime)
        if (a == null) {
            val msg = when (repo.status(ch.id)) {
                ChannelRepository.Status.FAILED -> I18n.t("no_listings")
                ChannelRepository.Status.EMPTY -> I18n.t("nothing_in_decades")
                else -> I18n.t("acquiring_listings")
            }
            infoTitle.textSize = 56f
            c.drawText(msg, x, panel.top + 150, infoTitle)
            return
        }
        val p = a.program
        c.drawText(Retro.fit(p.title, infoTitle, right - x - clockW - 10, 64f, 40f), x, panel.top + 128, infoTitle)
        val sub = listOfNotNull(p.episode, p.year).joinToString("  *  ")
        var y = panel.top + 128
        if (sub.isNotEmpty()) {
            y += 46
            c.drawText(Retro.ellipsize(sub, infoSub, right - x), x, y, infoSub)
        }
        y += 48
        val airingNow = now in a.start until a.end
        val timeText = "${Retro.longTime(a.start)} - ${Retro.longTime(a.end)}   (${Retro.duration(a.end - a.start)})"
        c.drawText(timeText, x, y, infoTime)
        if (airingNow) {
            val tw = infoTime.measureText(timeText) + 30
            val pct = "${(a.progress(now) * 100).toInt()}%"
            val pw = infoTime.measureText("100%")
            Retro.progressBar(c, RectF(x + tw, y - 26, right - pw - 16, y - 2), a.progress(now))
            c.drawText(pct, right - pw, y, infoTime)
        } else {
            val mins = (a.start - now + 59_999) / 60_000
            val starts = if (mins >= 60) I18n.t("starts_in_h", mins / 60, mins % 60) else I18n.t("starts_in_min", mins)
            val tw = infoTime.measureText(timeText) + 30
            c.drawText(starts, x + tw, y, Retro.textPaint(context, 38f, Retro.GREEN))
        }
        val desc = p.description
        if (!desc.isNullOrBlank()) {
            val top = y + 22
            val maxLines = ((panel.bottom - 20 - top) / 38).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(desc, 0, desc.length, infoDesc, (right - x).toInt())
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            c.save()
            c.translate(x, top)
            layout.draw(c)
            c.restore()
        }
    }

    private fun drawGrid(c: Canvas, now: Long) {
        hits.clear()
        val windowEnd = windowStart + WINDOW_MS
        val pxPerMs = (GRID_RIGHT - GRID_LEFT) / WINDOW_MS.toFloat()
        fun xOf(t: Long) = GRID_LEFT + (t - windowStart) * pxPerMs

        // Header: date box + half-hour time slots.
        val hdrTop = GRID_TOP
        val hdrBottom = GRID_TOP + HEADER_H
        Retro.bevel(c, RectF(CHAN_LEFT, hdrTop, CHAN_RIGHT, hdrBottom), Retro.HEADER, Retro.HEADER_LIGHT, Retro.CELL_SHADOW)
        c.drawText(Retro.day(windowStart), CHAN_LEFT + 18, hdrBottom - 16, headerText)
        for (i in 0 until 3) {
            val t = windowStart + i * 30 * 60_000L
            val r = RectF(xOf(t) + GAP / 2, hdrTop, xOf(t + 30 * 60_000L) - GAP / 2, hdrBottom)
            Retro.bevel(c, r, Retro.HEADER, Retro.HEADER_LIGHT, Retro.CELL_SHADOW)
            c.drawText(Retro.longTime(t), r.left + 18, hdrBottom - 16, headerText)
        }

        // Rows.
        val rowsTop = hdrBottom + GAP
        for (vis in 0 until VISIBLE_ROWS) {
            val idx = topRow + vis
            val ch = channels.getOrNull(idx) ?: break
            val top = rowsTop + vis * (ROW_H + GAP)
            val bottom = top + ROW_H
            val selectedRow = idx == selRow

            val chanCell = RectF(CHAN_LEFT, top, CHAN_RIGHT, bottom)
            hits += Hit(chanCell, idx, -1)
            Retro.bevel(c, chanCell, if (selectedRow) 0xFF2A4BA8.toInt() else Retro.CELL, Retro.CELL_LIGHT, Retro.CELL_SHADOW)
            fill.color = ch.color
            c.drawRect(chanCell.left + 4, chanCell.top + 4, chanCell.left + 16, chanCell.bottom - 4, fill)
            c.drawText(Retro.channelNumber(ch.number), chanCell.left + 30, bottom - 18, chanNum)
            c.drawText(Retro.ellipsize(ch.name, chanName, chanCell.right - chanCell.left - 110), chanCell.left + 100, bottom - 22, chanName)

            val sched = repo.schedule(ch.id)
            if (sched == null || sched.isEmpty) {
                val r = RectF(GRID_LEFT + GAP / 2, top, GRID_RIGHT, bottom)
                Retro.bevel(c, r, Retro.CELL_PAST, Retro.CELL, Retro.CELL_SHADOW)
                val msg = when (repo.status(ch.id)) {
                    ChannelRepository.Status.FAILED -> I18n.t("no_listings_retrying")
                    ChannelRepository.Status.EMPTY -> I18n.t("nothing_in_decades")
                    else -> I18n.t("acquiring_listings")
                }
                c.drawText(msg, r.left + 20, bottom - 26, cellSub.apply { textSize = 34f })
                cellSub.textSize = 28f
                continue
            }
            val selected = if (selectedRow) cellAt(ch, sched, selTime) else null
            for (cellData in cells(ch, sched, windowStart, windowEnd)) {
                val l = maxOf(xOf(cellData.start), GRID_LEFT) + GAP / 2
                val r = minOf(xOf(cellData.end), GRID_RIGHT) - GAP / 2
                if (r - l < 4) continue
                val cell = RectF(l, top, r, bottom)
                hits += Hit(cell, idx, cellData.start)
                val isSel = selected != null && selected.start == cellData.start
                val past = cellData.end <= now
                when {
                    isSel -> Retro.bevel(c, cell, Retro.YELLOW, 0xFFFFF2A8.toInt(), 0xFF9C7E00.toInt())
                    past -> Retro.bevel(c, cell, Retro.CELL_PAST, Retro.CELL, Retro.CELL_SHADOW)
                    else -> Retro.bevel(c, cell, Retro.CELL, Retro.CELL_LIGHT, Retro.CELL_SHADOW)
                }
                val tp = if (isSel) cellTextSel else cellText
                val sp = if (isSel) cellSubSel else cellSub
                var tx = cell.left + 14
                if (cellData.start < windowStart) {
                    c.drawText("<", tx, top + 40, tp); tx += 22
                }
                val continues = cellData.end > windowEnd
                val textRight = cell.right - (if (continues) 34 else 12)
                c.drawText(Retro.ellipsize(cellData.title, tp, textRight - tx), tx, top + 40, tp)
                if (continues) c.drawText(">", cell.right - 26, top + 40, tp)

                val airing = cellData.current
                if (airing != null) {
                    // The stacked completion bars: every row's current show reports how far it's gone.
                    Retro.progressBar(c, RectF(cell.left + 10, bottom - 20, cell.right - 10, bottom - 8), airing.progress(now),
                        if (isSel) Retro.NAVY else Retro.YELLOW)
                } else {
                    val sub = cellData.sub ?: Retro.duration(cellData.end - cellData.start)
                    c.drawText(Retro.ellipsize(sub, sp, textRight - tx), tx, bottom - 10, sp)
                }
            }
        }

        // Sweep line for "now".
        if (now in windowStart until windowEnd) {
            val x = xOf(now)
            val gridBottom = rowsTop + VISIBLE_ROWS * (ROW_H + GAP)
            c.drawLine(x, hdrBottom - 6, x, gridBottom, linePaint)
            val tab = Path().apply {
                moveTo(x - 46, hdrTop - 34); lineTo(x + 46, hdrTop - 34); lineTo(x + 46, hdrTop - 6)
                lineTo(x + 10, hdrTop - 6); lineTo(x, hdrTop + 6); lineTo(x - 10, hdrTop - 6); lineTo(x - 46, hdrTop - 6); close()
            }
            fill.color = Retro.RED
            c.drawPath(tab, fill)
            c.drawText(Retro.shortTime(now), x, hdrTop - 12, nowTabText)
        }

        // Footer hints & scroll indicator.
        val footY = 1066f
        c.drawText("ARCHIVE CABLE", CHAN_LEFT, footY, brand)
        c.drawText(I18n.t(if (touchMode) "guide_hints_touch" else "guide_hints"), CHAN_LEFT + 250, footY, hintText)
        val pageText = "${selRow + 1}/${channels.size}"
        c.drawText(pageText, GRID_RIGHT - hintText.measureText(pageText), footY, hintText)
    }

    companion object {
        private val PREVIEW = RectF(60f, 46f, 60f + 720f, 46f + 405f)
        private const val CHAN_LEFT = 50f
        private const val CHAN_RIGHT = 330f
        private const val GRID_LEFT = 334f
        private const val GRID_RIGHT = 1870f
        private const val GRID_TOP = 516f
        private const val HEADER_H = 58f
        private const val ROW_H = 70f
        private const val GAP = 5f
        private const val VISIBLE_ROWS = 6
        private const val SWIPE_MIN = 90f
        private const val SETUP_ROWS = 16
        private const val VISIBLE_SETUP_ROWS = 9
        private const val ROW_DECADE_FIRST = 7
        private const val ROW_UNDATED = 15
        private const val ROW_LANGUAGE = 0
        private const val ROW_PICTURE = 1
        private const val ROW_TRANSITIONS = 2
        private const val ROW_AUDIO = 3
        private const val ROW_OVERSCAN = 4
        private const val ROW_OFFSET_X = 5
        private const val ROW_OFFSET_Y = 6
        private const val WINDOW_MS = 90 * 60_000L
        private const val MAX_AHEAD_MS = 24 * 3600_000L
    }
}
