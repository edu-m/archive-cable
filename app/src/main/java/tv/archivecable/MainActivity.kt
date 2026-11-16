package tv.archivecable

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {

    private lateinit var repo: ChannelRepository
    private lateinit var player: ExoPlayer
    private lateinit var root: FrameLayout
    /** Holds everything on screen; resized/shifted as a whole for CRT overscan and X/Y position. */
    private lateinit var frame: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var staticView: StaticView
    private lateinit var bumper: BumperView
    private lateinit var settings: Settings
    /** Auto volume: evens out near-silent and ear-piercing recordings. */
    private val leveler = LevelerAudioProcessor()
    /** Display aspect ratio of the current video (0 = unknown). */
    private var videoRatio = 0f
    private lateinit var osd: OsdView
    private lateinit var guide: GuideView

    private val handler = Handler(Looper.getMainLooper())
    private val channels get() = repo.channels

    private var current = 0
    private var previous = -1
    private var playable: Playable? = null
    /** Wall-clock time the current media is due to finish (shifts when the viewer seeks or pauses). */
    private var scheduledEnd = 0L
    private var tuneStartedAt = 0L
    private var waitingForFrame = false
    private val badUrls = HashSet<String>()
    private var digits = ""
    private var lastBackPress = 0L

    private var tunePending = false
    private val tuneNow = Runnable { tunePending = false; playLive(System.currentTimeMillis()) }
    private val commitDigits = Runnable { commitDigitEntry() }
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        settings = Settings(applicationContext)
        val legacy = getPreferences(MODE_PRIVATE) // pre-1.1 storage
        if (settings.language == null) legacy.getString(PREF_LANGUAGE, null)?.let { settings.language = it }
        I18n.set(settings.language)
        repo = ChannelRepository(applicationContext, I18n.code, settings.decadeFilter)

        val savedId = settings.lastChannel(I18n.code) ?: legacy.getString("${PREF_CHANNEL}_${I18n.code}", null)
        current = channels.indexOfFirst { it.id == savedId }.coerceAtLeast(0)

        buildViews()
        buildPlayer()

        repo.onChanged = { id -> onLineupChanged(id) }
        repo.start(channels.getOrNull(current)?.id)

        // Android 13+ delivers BACK through this callback (and Android 16 no longer sends the key at all).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    /** TVs have no system bars, but phones/tablets/emulators do: keep the picture full-screen. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Touch screens (phones/tablets), mirroring the remote: tap = guide (OK), swipe up/down = channel,
     * swipe left/right = -15/+15 s, long-press = channel banner.
     */
    private val liveGestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                openGuide()
                return true
            }
            override fun onLongPress(e: MotionEvent) = showOsd()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                val min = 0.06f * root.height
                if (maxOf(abs(dx), abs(dy)) < min) return false
                if (abs(dx) > abs(dy)) seek(if (dx > 0) SEEK_MS else -SEEK_MS)
                else tune(nextChannel(if (dy < 0) 1 else -1))
                return true
            }
        })
    }

    private fun handleBack() {
        if (guide.isOpen) guide.handleKey(KeyEvent.KEYCODE_BACK) else onBack()
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        root.addView(frame, match())
        screen = FrameLayout(this)
        playerView = (layoutInflater.inflate(R.layout.player_view, screen, false) as PlayerView).apply {
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(false)
        }
        staticView = StaticView(this)
        screen.addView(playerView, match())
        screen.addView(staticView, match())
        bumper = BumperView(this)
        screen.addView(bumper, match())
        frame.addView(screen, match())
        guide = GuideView(this, repo).apply {
            versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            onTune = { idx -> closeGuide(); if (idx != current) tune(idx) else showOsd() }
            onClose = { closeGuide() }
            setupProvider = {
                GuideView.SetupValues(I18n.code, settings.aspect, settings.transitions, settings.audioLeveling,
                    settings.overscan, settings.offsetX, settings.offsetY, settings.decadeFilter)
            }
            onSetupPreview = { v ->
                applyAspect(v.aspect)
                leveler.enabled = v.audioLeveling // audible right away while browsing
                applyGeometry(v.overscan, v.offsetX, v.offsetY)
            }
            onSetupSave = { v -> saveSetup(v) }
        }
        frame.addView(guide, match())
        osd = OsdView(this)
        frame.addView(osd, match())
        setContentView(root)
        root.setOnTouchListener { _, ev -> !guide.isOpen && liveGestures.onTouchEvent(ev) }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { applyGeometry() }
        }
    }

    private fun buildPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ArchiveApi.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .build()
        leveler.enabled = settings.audioLeveling
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(leveler))
                    .setEnableFloatOutput(false) // the leveler works on 16-bit PCM
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                waitingForFrame = false
                bumper.markVideoReady()
                staticView.hide()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onMediaEnded()
            }

            override fun onPlayerError(error: PlaybackException) = onMediaError(error)

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    applyAspect()
                }
            }
        })
        // No captions/subtitle tracks ever (they ruin the vibe), and prefer dubbed audio in the UI language.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .setPreferredAudioLanguage(I18n.code)
            .build()
        playerView.player = player
        playerView.subtitleView?.visibility = View.GONE
    }

    /**
     * AUTO stretches 4:3-shaped video (ratio 1.15–1.6) to fill a 16:9 screen, like the "wide" mode
     * of an old TV, and leaves widescreen or odd shapes alone.
     */
    private fun applyAspect(mode: AspectMode = settings.aspect) {
        playerView.resizeMode = when (mode) {
            AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectMode.AUTO ->
                if (videoRatio in 1.15f..1.6f) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        updateOverscan(mode)
    }

    /**
     * AUTO also overscans ~4% like a CRT did, hiding the black edges and head-switching noise baked
     * into most VHS captures. Not applied inside the guide's preview window.
     */
    private fun updateOverscan(mode: AspectMode = settings.aspect) {
        val w = frame.layoutParams.width.takeIf { it > 0 } ?: root.width
        val h = frame.layoutParams.height.takeIf { it > 0 } ?: root.height
        val lp = if (mode == AspectMode.AUTO && !guide.isOpen && w > 0 && h > 0) {
            FrameLayout.LayoutParams((w * OVERSCAN).toInt(), (h * OVERSCAN).toInt(), Gravity.CENTER)
        } else match()
        playerView.layoutParams = lp
        staticView.layoutParams = match()
    }

    /**
     * CRT setup: scales the whole picture (video, guide, banners) to [overscan]% and shifts it by
     * [offsetX]/[offsetY] tenths of a percent, so nothing is lost under a tube's bezel.
     */
    private fun applyGeometry(overscan: Int = settings.overscan, offsetX: Int = settings.offsetX, offsetY: Int = settings.offsetY) {
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0) return
        // The UI is drawn for 16:9: on wider (phones) or taller (tablets) screens, keep a 16:9 box.
        val baseW = if (rw * 9 > rh * 16) rh * 16 / 9 else rw
        val baseH = if (rw * 9 > rh * 16) rh else rw * 9 / 16
        val w = (baseW * overscan / 100f).toInt()
        val h = (baseH * overscan / 100f).toInt()
        frame.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = (rw - w) / 2 + (baseW * offsetX / 1000f).toInt()
            topMargin = (rh - h) / 2 + (baseH * offsetY / 1000f).toInt()
        }
        if (guide.isOpen) layoutPreview(w, h)
        updateOverscan()
    }

    private fun saveSetup(v: GuideView.SetupValues) {
        settings.aspect = v.aspect
        settings.transitions = v.transitions
        settings.audioLeveling = v.audioLeveling
        leveler.enabled = v.audioLeveling
        settings.overscan = v.overscan
        settings.offsetX = v.offsetX
        settings.offsetY = v.offsetY
        applyAspect()
        applyGeometry()
        if (!v.transitions) stopBumper()
        val decadesChanged = v.decades != settings.decadeFilter
        settings.decadeFilter = v.decades
        if (v.language != I18n.code || decadesChanged) {
            // Language swaps UI *and* lineup; decades swap the lineup. Restart on the new channels.
            settings.language = v.language
            closeGuide()
            recreate()
        }
    }

    // ---- Channel idents ------------------------------------------------------------------------

    private val fadeIn = object : Runnable {
        var startedAt = 0L
        override fun run() {
            val f = ((SystemClock.elapsedRealtime() - startedAt) / 700f).coerceIn(0f, 1f)
            player.volume = f
            if (f < 1f) handler.postDelayed(this, 30)
        }
    }

    private fun playBumper(ch: ChannelDef, kind: BumperView.Kind, title: String, waitForVideo: Boolean = true) {
        val dur = BumperView.durationMs(ch.bumper, kind, shortForm = ch.slotMinutes == 0)
        handler.removeCallbacks(fadeIn)
        player.volume = 0f // the show starts underneath; bring it up when the ident ends
        Jingles.play(ch.bumper, kind)
        bumper.play(ch.bumper, kind, ch, title, null, dur, waitForVideo) {
            fadeIn.startedAt = SystemClock.elapsedRealtime()
            handler.post(fadeIn)
        }
    }

    private fun stopBumper() {
        bumper.cancel()
        Jingles.stop()
        handler.removeCallbacks(fadeIn)
        player.volume = 1f
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun onStart() {
        super.onStart()
        tune(current, showBanner = true)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        stopBumper()
        handler.removeCallbacksAndMessages(null)
        tunePending = false
        player.stop()
        playable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.shutdown()
        player.release()
    }

    // ---- Tuning -------------------------------------------------------------------------------

    private fun tune(index: Int, showBanner: Boolean = true) {
        if (channels.isEmpty()) return
        if (index != current) previous = current
        current = index.coerceIn(0, channels.size - 1)
        settings.setLastChannel(I18n.code, channels[current].id)
        playable = null
        stopBumper()
        player.stop()
        player.clearMediaItems()
        staticView.show(StaticView.Mode.SNOW)
        if (showBanner && !guide.isOpen) showOsd()
        // Short delay: rapid channel surfing only loads the channel you stop on.
        handler.removeCallbacks(tuneNow)
        tunePending = true
        handler.postDelayed(tuneNow, TUNE_DELAY_MS)
    }

    /** [seamless]: continuing on the same channel (program → next), so cut to black rather than static. */
    private fun playLive(at: Long, seamless: Boolean = false) {
        val ch = channels.getOrNull(current) ?: return
        val sched = repo.schedule(ch.id)
        if (sched == null || sched.isEmpty) {
            repo.prioritize(ch.id)
            playable = null
            player.stop()
            val chLabel = "${I18n.t("ch")} ${Retro.channelNumber(ch.number)}  ${ch.title.uppercase(I18n.current.locale)}"
            when (repo.status(ch.id)) {
                ChannelRepository.Status.FAILED -> staticView.show(StaticView.Mode.SNOW, I18n.t("no_signal"), chLabel)
                ChannelRepository.Status.EMPTY -> staticView.show(StaticView.Mode.BARS, I18n.t("no_signal"), I18n.t("nothing_in_decades"))
                else -> staticView.show(StaticView.Mode.SNOW, I18n.t("acquiring_signal"), chLabel)
            }
            return
        }
        val now = System.currentTimeMillis()
        val p = sched.playableAt(at, repo.filler, badUrls)
        val previousPlayable = playable
        playable = p
        android.util.Log.i("ArchiveCable", "playLive seamless=$seamless at=${Retro.longTime(at)} -> " +
            when (p) {
                is Playable.Media -> "${if (p.isFiller) "filler" else "program"} '${p.program.title}' pos=${p.positionMs / 1000}s/" +
                    "${((p.clipEndMs ?: p.program.durationMs) - p.clipStartMs) / 1000}s"
                is Playable.StandBy -> "standby"
            })
        if (seamless && settings.transitions && p is Playable.Media) {
            val showStarting = !p.isFiller && p.positionMs < 15_000
            val breakStarting = p.isFiller && previousPlayable is Playable.Media && !previousPlayable.isFiller
            when {
                showStarting -> playBumper(ch, BumperView.Kind.NEXT, p.program.title)
                breakStarting -> {
                    val nextTitle = sched.airingAt(p.airing.end)?.program?.title ?: ch.title
                    playBumper(ch, BumperView.Kind.BREAK, I18n.t("after_break", nextTitle))
                }
            }
        }
        when (p) {
            is Playable.Media -> {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(p.clipStartMs)
                    .setEndPositionMs(p.clipEndMs ?: C.TIME_END_OF_SOURCE)
                    .build()
                val item = MediaItem.Builder()
                    .setUri(p.program.url)
                    .setMediaId(p.program.url)
                    .setClippingConfiguration(clipping)
                    .build()
                val clipLen = (p.clipEndMs ?: p.program.durationMs) - p.clipStartMs
                scheduledEnd = now + (clipLen - p.positionMs)
                tuneStartedAt = SystemClock.elapsedRealtime()
                waitingForFrame = true
                if (seamless) staticView.hide() else staticView.show(StaticView.Mode.SNOW)
                player.setMediaItem(item, p.positionMs.coerceAtLeast(0))
                player.prepare()
                player.playWhenReady = true
            }
            is Playable.StandBy -> {
                player.stop()
                scheduledEnd = p.untilMs
                waitingForFrame = false
                staticView.show(StaticView.Mode.BARS, I18n.t("stand_by"), I18n.t("resumes_at", Retro.longTime(p.untilMs)))
            }
        }
        osd.update(infoFor(current))
    }

    private fun onMediaEnded() {
        val now = System.currentTimeMillis()
        // Stay time-shifted after pausing/rewinding (like a cable DVR), unless we've drifted far behind.
        val next = if (now - scheduledEnd in 0..MAX_TIMESHIFT_MS) scheduledEnd + 50 else maxOf(now, scheduledEnd + 50)
        playLive(next, seamless = true)
    }

    private fun onMediaError(error: PlaybackException) {
        val p = playable as? Playable.Media
        android.util.Log.w("ArchiveCable", "playback error ${error.errorCodeName} for ${p?.program?.url}", error)
        p?.let { badUrls += it.program.url }
        playable = null
        staticView.show(StaticView.Mode.SNOW, I18n.t("signal_lost"), I18n.t("technical_difficulties"))
        handler.removeCallbacks(tuneNow)
        tunePending = true
        handler.postDelayed(tuneNow, 2500)
    }

    private var tickCount = 0

    private fun onTick() {
        val now = System.currentTimeMillis()
        if (isDebuggable() && ++tickCount % 3 == 0 && player.isPlaying) {
            val db = 20 * kotlin.math.log10(leveler.currentGain.toDouble().coerceAtLeast(1e-6))
            android.util.Log.i("ArchiveCable", "auto volume ${"%+.1f".format(db)} dB on '${(playable as? Playable.Media)?.program?.title}'")
        }
        val p = playable
        // Still the same channel: continue seamlessly so the next show gets its ident.
        if (p is Playable.StandBy && now >= p.untilMs) playLive(maxOf(now, p.untilMs + 50), seamless = true)
        if (waitingForFrame && SystemClock.elapsedRealtime() - tuneStartedAt > FIRST_FRAME_TIMEOUT_MS) {
            (p as? Playable.Media)?.let { badUrls += it.program.url }
            waitingForFrame = false
            playLive(now)
        }
    }

    private fun onLineupChanged(id: String) {
        val ch = channels.getOrNull(current)
        if (ch != null && (id == ch.id || id == "_filler")) {
            val waiting = playable == null && !tunePending
            val standBy = playable is Playable.StandBy && id == "_filler"
            if (waiting || standBy) playLive(System.currentTimeMillis())
            osd.update(infoFor(current))
        }
        guide.refresh()
    }

    // ---- OSD ----------------------------------------------------------------------------------

    private fun infoFor(index: Int): OsdView.Info {
        val ch = channels[index]
        val sched = repo.schedule(ch.id)
        val now = System.currentTimeMillis()
        val media = if (index == current) playable as? Playable.Media else null
        val airing = media?.airing ?: sched?.airingAt(now)
        val next = airing?.let { sched?.airingAt(it.end) }
        val status = when (repo.status(ch.id)) {
            ChannelRepository.Status.FAILED -> I18n.t("no_listings_retrying")
            ChannelRepository.Status.EMPTY -> I18n.t("nothing_in_decades")
            else -> null
        }
        return OsdView.Info(ch, airing, next, status, media?.isFiller == true)
    }

    private fun showOsd() {
        if (channels.isNotEmpty()) osd.showChannel(infoFor(current), OSD_MS)
    }

    private fun seek(deltaMs: Long) {
        val p = playable as? Playable.Media ?: return
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val target = (player.currentPosition + deltaMs).coerceIn(0, (dur - 1000).coerceAtLeast(0))
        player.seekTo(target)
        scheduledEnd = System.currentTimeMillis() + (dur - target)
        val label = if (deltaMs > 0) "+${deltaMs / 1000}s >>" else "<< -${-deltaMs / 1000}s"
        osd.showSeek(label, p.clipStartMs + target, p.clipStartMs + dur, infoFor(current))
    }

    // ---- Guide --------------------------------------------------------------------------------

    private fun openGuide() {
        osd.dismiss()
        guide.open(current)
        updateOverscan()
        root.post { layoutPreview(frame.layoutParams.width.takeIf { it > 0 } ?: frame.width, frame.layoutParams.height.takeIf { it > 0 } ?: frame.height) }
    }

    /** Puts the live video into the guide's preview window. */
    private fun layoutPreview(frameW: Int, frameH: Int) {
        val r = guide.previewRect(frameW, frameH)
        screen.layoutParams = FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP or Gravity.START).apply {
            leftMargin = r.left
            topMargin = r.top
        }
    }

    private fun closeGuide() {
        guide.close()
        screen.layoutParams = match()
        updateOverscan()
    }

    // ---- Remote -------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!isHandled(code)) return super.dispatchKeyEvent(event)
        if (code == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.dispatchKeyEvent(event) // routed to the OnBackInvokedCallback
        }
        if (event.action == KeyEvent.ACTION_DOWN) onRemoteKey(code, event)
        return true
    }

    private fun isHandled(code: Int) = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER -> false
        else -> true
    }

    private fun onRemoteKey(code: Int, event: KeyEvent) {
        if (guide.isOpen) {
            if (!guide.handleKey(code) && digitOf(code) != null) {
                closeGuide(); enterDigit(digitOf(code)!!)
            }
            return
        }
        val n = channels.size
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> tune(nextChannel(1))
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> tune(nextChannel(-1))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(SEEK_MS)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-SEEK_MS)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_INPUT ->
                if (event.repeatCount == 0) { if (digits.isNotEmpty()) commitDigitEntry() else openGuide() }
            KeyEvent.KEYCODE_INFO -> showOsd()
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous in 0 until n) tune(previous)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (playable is Playable.Media) {
                    val play = when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> true
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                        else -> !player.playWhenReady
                    }
                    player.playWhenReady = play
                    if (play) scheduledEnd = System.currentTimeMillis() + (player.duration - player.currentPosition)
                    osd.showSeek(if (play) I18n.t("play") else I18n.t("paused"), player.currentPosition, player.duration.coerceAtLeast(1), infoFor(current))
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> onBack()
            // Debug builds only: preview this channel's idents (B = show starting, N = commercial break).
            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N -> if (isDebuggable()) channels.getOrNull(current)?.let { ch ->
                if (code == KeyEvent.KEYCODE_B) playBumper(ch, BumperView.Kind.NEXT, (playable as? Playable.Media)?.program?.title ?: ch.title, waitForVideo = false)
                else playBumper(ch, BumperView.Kind.BREAK, I18n.t("after_break", ch.title), waitForVideo = false)
            }
            else -> digitOf(code)?.let { enterDigit(it) }
        }
    }

    /** Next channel in [dir], skipping ones with nothing from the selected decades (like unsubscribed channels). */
    private fun nextChannel(dir: Int): Int {
        val n = channels.size
        var i = current
        repeat(n) {
            i = Math.floorMod(i + dir, n)
            if (repo.status(channels[i].id) != ChannelRepository.Status.EMPTY) return i
        }
        return Math.floorMod(current + dir, n)
    }

    private fun onBack() {
        if (digits.isNotEmpty()) {
            digits = ""; handler.removeCallbacks(commitDigits); osd.dismiss(); return
        }
        if (osd.visibility == View.VISIBLE) {
            osd.dismiss(); return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress < 2500) finish()
        else {
            lastBackPress = now
            Toast.makeText(this, I18n.t("press_back_again"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDebuggable() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    private fun enterDigit(d: Int) {
        digits += d
        osd.showDigits(digits)
        handler.removeCallbacks(commitDigits)
        if (digits.length >= 2) commitDigitEntry() else handler.postDelayed(commitDigits, 1600)
    }

    private fun commitDigitEntry() {
        handler.removeCallbacks(commitDigits)
        val number = digits.toIntOrNull()
        digits = ""
        val idx = channels.indexOfFirst { it.number == number }
        if (idx >= 0) tune(idx) else osd.dismiss()
    }

    companion object {
        private const val PREF_CHANNEL = "channel"
        private const val PREF_LANGUAGE = "language"
        private const val SEEK_MS = 15_000L
        private const val OSD_MS = 3_000L
        private const val TUNE_DELAY_MS = 350L
        private const val FIRST_FRAME_TIMEOUT_MS = 35_000L
        private const val MAX_TIMESHIFT_MS = 15 * 60_000L
        private const val OVERSCAN = 1.04f
    }
}
