package app.olauncher.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.audiofx.Visualizer
import android.media.MediaMetadata
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Prefs
import app.olauncher.helper.*
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Real output samples only. No microphone recording, storage, background service or synthetic beat. */
class AudioVisualizerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var homeSurface = false
    private var notificationBound = false
    private var targetToken: android.media.session.MediaSession.Token? = null
    private var previewAllowed = true
    private var artworkSink: ((Bitmap?) -> Unit)? = null
    private var artwork: Bitmap? = null
    private var artworkKey: List<Any?>? = null
    private var metadataRevision = 0
    private var artworkJob: Job? = null
    private var artworkRequest: MediaArtwork.Request? = null
    private var artworkGeneration = 0
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun bindNotification(
        token: android.media.session.MediaSession.Token?,
        allowPreview: Boolean = true,
        onArtwork: ((Bitmap?) -> Unit)? = null,
    ) {
        if (!notificationBound || token != targetToken || allowPreview != previewAllowed) stop()
        notificationBound = true
        targetToken = token
        previewAllowed = allowPreview
        artworkSink = onArtwork
        onArtwork?.invoke(if (allowPreview) artwork else null)
        title.visibility = GONE // App and track already belong to the surrounding notification.
        refresh()
    }
    var onConfigure: (() -> Unit)? = null
    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val title = TextView(context, null, 0, R.style.TextSmall).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
    }
    private val wave = WaveView(context)
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var owner: LifecycleOwner? = null
    private var listening = false
    private var controller: MediaController? = null
    private val watched = LinkedHashMap<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var subscribed = false
    private var captureFailed = false
    private var concurrentPlayback = false
    private val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { refresh() }
        override fun onPause(owner: LifecycleOwner) { stop() }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { if (listening) choose(it.orEmpty()) }
    private fun mediaCallback() = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { rescanSessions() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { metadataRevision++; update() }
        override fun onSessionDestroyed() { rescanSessions() }
    }
    private val component get() = ComponentName(context, NotificationService::class.java)

    init {
        orientation = VERTICAL; minimumHeight = 48.dpToPx(); setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
        addView(title, LayoutParams(-1, -2)); addView(wave, LayoutParams(-1, 40.dpToPx()))
        isClickable = true; isFocusable = true
        setOnClickListener { onConfigure?.invoke() }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        wave.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        wave.onSeek = { position -> runCatching { controller?.transportControls?.seekTo(position) } }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        owner = findViewTreeLifecycleOwner(); owner?.lifecycle?.addObserver(observer); refresh()
    }
    override fun onDetachedFromWindow() {
        stop(); owner?.lifecycle?.removeObserver(observer); owner = null; super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) { if (visibility == VISIBLE) refresh() else stop() }
    }
    /** preset() already folds in the saver, Remove animations and e-ink. */
    private fun motionStill() = LauncherMotion.preset(context, prefs) == LauncherMotion.OFF ||
        (homeSurface && prefs.homeScrollStyle == 0)
    fun refresh() {
        val shown = prefs.visualizerEnabled && (!notificationBound || targetToken != null) && (if (homeSurface) prefs.visualizerHome else prefs.visualizerPanel)
        val wantsArtwork = notificationBound && targetToken != null && previewAllowed && artworkSink != null
        // Home has no media card when playback is idle. Keep the seek rail hidden
        // during navigation, even if an old session has not been rescanned yet.
        visibility = if (shown && !homeSurface) VISIBLE else GONE
        val color = if (artwork != null) Color.WHITE else if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text else context.getColorFromAttr(R.attr.primaryColor)
        title.setTextColor(color); applyFocusOutline(color)
        wave.configure(color, prefs.visualizerStyle, prefs.visualizerColor, prefs.colorThemeId)
        // Only when it changes: setLayoutParams requests a layout of the whole Home tree, even with
        // the visualizer hidden, and refresh() runs two or three times on every Home resume.
        val waveHeight = if (prefs.visualizerStyle == 0) wave.ribbonHeight(prefs.visualizerHeight)
            else (28 + prefs.visualizerHeight * 16).dpToPx()
        if (wave.layoutParams.height != waveHeight) wave.layoutParams = wave.layoutParams.apply { height = waveHeight }
        // Home draws no wave while motion is still (saver, Motion Off, Remove animations, e-ink or
        // the Static layout). Staying subscribed anyway meant every playback or metadata change
        // from any media app on the phone still called into the launcher, only to repaint a label
        // on a GONE view. The notification panel keeps its card; that one the user opened.
        if (homeSurface && motionStill()) { stop(); return }
        if ((!shown && !wantsArtwork) || !isAttachedToWindow || windowVisibility != VISIBLE || owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) { stop(); return }
        if (!context.notificationAccessGranted()) { stop(); label(context.getString(R.string.visualizer_notification_access)); return }
        if (!listening) {
            listening = runCatching { manager.addOnActiveSessionsChangedListener(sessions, component, handler); true }.getOrDefault(false)
            if (!listening) { label(context.getString(R.string.visualizer_unavailable)); return }
            choose(runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList()))
        } else update()
    }
    private fun rescanSessions() {
        if (listening) choose(runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList()))
    }
    private fun choose(controllers: List<MediaController>) {
        if (!listening) return
        concurrentPlayback = controllers.count { it.playbackState?.state == PlaybackState.STATE_PLAYING } > 1
        val relevant = if (notificationBound) controllers.filter { it.sessionToken == targetToken } else controllers
        val tokens = relevant.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { watched.remove(it)?.let { (controller, callback) -> controller.unregisterCallback(callback) } }
        relevant.forEach { candidate ->
            if (candidate.sessionToken !in watched) {
                val callback = mediaCallback()
                watched[candidate.sessionToken] = candidate to callback
                candidate.registerCallback(callback, handler)
            }
        }
        val next = if (notificationBound) controllers.firstOrNull { it.sessionToken == targetToken }
            else controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            releaseCapture(); clearArtwork(); controller = next; captureFailed = false
        }
        update()
    }
    private fun label(value: String) {
        title.text = value
        contentDescription = if (notificationBound && value == context.getString(R.string.customize_visualizer))
            context.getString(R.string.visualizer_playing_settings)
        else value + ". " + context.getString(R.string.visualizer_settings)
    }
    private fun update() {
        if (!listening) return
        // Once per update: getMetadata() is a binder call that brings the artwork bitmap with it,
        // and this used to make it three times.
        val metadata = controller?.metadata
        refreshArtwork(metadata)
        val playback = controller?.playbackState
        wave.playback(playback, metadata)
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val still = motionStill()
        val song = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val ambiguous = notificationBound && concurrentPlayback
        val status = when {
            !permitted -> context.getString(R.string.visualizer_audio_access)
            still -> context.getString(R.string.visualizer_paused_power)
            captureFailed -> context.getString(R.string.visualizer_unavailable)
            ambiguous -> context.getString(R.string.visualizer_multiple_playback)
            !playing -> context.getString(R.string.visualizer_no_playback)
            song.isNotBlank() -> if (homeSurface) {
                val app = controller?.packageName?.let { pkg -> runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg) }.orEmpty()
                "$app · $song"
            } else song
            else -> context.getString(R.string.visualizer_listening)
        }
        label(if (notificationBound) context.getString(R.string.customize_visualizer) else status)
        val visualizerShown = prefs.visualizerEnabled && (if (homeSurface) prefs.visualizerHome else prefs.visualizerPanel)
        if (homeSurface) {
            visibility = if (visualizerShown && playing && !still) VISIBLE else GONE
            wave.visibility = if (visualizerShown && playing && permitted && !captureFailed && !still) VISIBLE else GONE
            title.visibility = if (visualizerShown && playing && (!permitted || captureFailed)) VISIBLE else GONE
        }
        if (notificationBound) {
            visibility = if (visualizerShown && playing && !still) VISIBLE else GONE
            wave.visibility = if (visualizerShown && playing && permitted && !captureFailed && !still && !ambiguous) VISIBLE else GONE
            // Permission and unavailable states remain actionable, without leaking hidden track titles.
            title.visibility = if (playing && visualizerShown && (!permitted || captureFailed || ambiguous)) VISIBLE else GONE
            if (title.isVisible) label(status)
        }
        if (!visualizerShown || !playing || !permitted || still || captureFailed || ambiguous) { releaseCapture(); return }
        if (subscribed) return
        subscribed = true
        OutputWaveCapture.subscribe(this, { signed, peaks -> wave.samples(signed, peaks) }, ::showCaptureFailure)
    }
    private fun showCaptureFailure() {
        captureFailed = true
        releaseCapture()
        if (notificationBound) { visibility = VISIBLE; title.visibility = VISIBLE; wave.visibility = GONE }
        label(context.getString(R.string.visualizer_unavailable))
    }
    private fun releaseCapture() {
        if (subscribed) OutputWaveCapture.unsubscribe(this)
        subscribed = false
        wave.clear()
    }
    private fun setArtwork(next: Bitmap?) {
        artwork = next
        val color = if (next != null) Color.WHITE else if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text else context.getColorFromAttr(R.attr.primaryColor)
        title.setTextColor(color)
        wave.configure(color, prefs.visualizerStyle, prefs.visualizerColor, prefs.colorThemeId)
        artworkSink?.invoke(next)
    }
    private fun clearArtwork() {
        artworkGeneration++
        artworkRequest?.let { handler.removeCallbacks(it.timeout); it.cancel() }
        artworkRequest = null
        artworkJob?.cancel()
        artworkJob = null
        artworkKey = null
        setArtwork(null)
    }
    private fun refreshArtwork(metadata: MediaMetadata?) {
        if (!notificationBound || !previewAllowed || artworkSink == null) {
            if (artwork != null || artworkJob != null) clearArtwork()
            return
        }
        val key = metadata?.let {
            listOf(metadataRevision,
                it.getString(MediaMetadata.METADATA_KEY_TITLE),
                it.getString(MediaMetadata.METADATA_KEY_ARTIST),
                it.getString(MediaMetadata.METADATA_KEY_ALBUM),
                it.getString(MediaMetadata.METADATA_KEY_ART_URI),
                it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                it.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
        }
        if (key == artworkKey) return
        clearArtwork()
        if (metadata == null) return
        artworkKey = key
        val token = targetToken
        val generation = artworkGeneration
        val request = MediaArtwork.Request()
        artworkRequest = request
        handler.postDelayed(request.timeout, 1_500L)
        artworkJob = artworkScope.launch {
            try {
                val thumbnail = MediaArtwork.thumbnail(context.applicationContext, metadata, request)
                if (isActive && generation == artworkGeneration && token == targetToken &&
                    controller?.sessionToken == token && isAttachedToWindow) setArtwork(thumbnail)
            } finally {
                handler.removeCallbacks(request.timeout)
                request.cancel()
                if (artworkRequest === request) artworkRequest = null
            }
        }
    }
    private fun stop() {
        releaseCapture()
        wave.playback(null, null)
        clearArtwork()
        watched.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        watched.clear()
        controller = null
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        listening = false
        captureFailed = false
        concurrentPlayback = false
    }
}

/** How often the output wave is sampled; the ribbon crossfades across exactly this interval. */
private const val WAVE_POLL_MS = 33L

/**
 * Whether the ribbon draws on this vsync: every second one, at whatever rate the panel runs. The
 * gap is one and a half periods, so jitter has half a frame of room either way. A fixed gap
 * cannot do this for both 60 and 90 Hz - one 60 Hz frame plus jitter (up to 19.7 ms) overlaps
 * two 90 Hz frames minus jitter (from 19.2 ms); RibbonFrameCapTest shows both failures.
 * The frame that settles to zero always draws.
 */
internal fun ribbonFrameDue(frameNanos: Long, lastDrawNanos: Long, vsyncNanos: Long, settled: Boolean): Boolean =
    settled || frameNanos - lastDrawNanos >= vsyncNanos * 3 / 2

/** One output-mix effect for all visible media rows. Capture delivery and view state stay on main. */
private object OutputWaveCapture {
    private data class Sink(val samples: (FloatArray, FloatArray) -> Unit, val error: () -> Unit)
    private val sinks = LinkedHashMap<Any, Sink>()
    private val envelope = FloatArray(32)
    private val signed = FloatArray(128)
    private val handler = Handler(Looper.getMainLooper())
    private val frameLock = Any()
    private val effectLock = Any()
    private var pending = ByteArray(0)
    private var pendingGeneration = 0
    private var framePosted = false
    private var effect: Visualizer? = null
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    @Volatile private var generation = 0
    private val drain = Runnable {
        var valid = false
        synchronized(frameLock) {
            framePosted = false
            if (pending.isNotEmpty() && pendingGeneration == generation) {
                audioEnvelope(pending, envelope)
                audioWaveform(pending, signed)
                valid = true
            }
        }
        if (valid) sinks.values.forEach { it.samples(signed, envelope) }
    }

    fun subscribe(owner: Any, samples: (FloatArray, FloatArray) -> Unit, error: () -> Unit) {
        sinks[owner] = Sink(samples, error)
        if (effect != null) return
        val token = ++generation
        try {
            val capture = Visualizer(0)
            effect = capture
            check(capture.setEnabled(false) == Visualizer.SUCCESS)
            val range = Visualizer.getCaptureSizeRange()
            check(capture.setCaptureSize(range[1].coerceAtMost(1024).coerceAtLeast(range[0])) == Visualizer.SUCCESS)
            check(capture.setEnabled(true) == Visualizer.SUCCESS)
            val bytes = ByteArray(capture.captureSize)
            val workerThread = HandlerThread("Moo output wave").apply { start() }
            val worker = Handler(workerThread.looper)
            pollThread = workerThread
            pollHandler = worker
            val poll = object : Runnable {
                private var failures = 0
                override fun run() {
                    if (token != generation) return
                    val result = synchronized(effectLock) {
                        if (token != generation) return
                        try { capture.getWaveForm(bytes) } catch (_: RuntimeException) { Int.MIN_VALUE }
                    }
                    if (token != generation) return
                    if (result == Visualizer.SUCCESS) {
                        failures = 0
                        synchronized(frameLock) {
                            if (token != generation) return
                            if (pending.size != bytes.size) pending = ByteArray(bytes.size)
                            bytes.copyInto(pending)
                            pendingGeneration = token
                            if (!framePosted) {
                                framePosted = true
                                handler.post(drain)
                            }
                        }
                    } else if (++failures >= 5) {
                        handler.post { fail(token) }
                        return
                    }
                    // Polled rather than the effect's own callback, which the A17 runs at 20 Hz.
                    // 30 Hz, not 60: the ribbon draws at most every second frame now, and each
                    // poll is an IPC into audioserver plus a post to the main thread.
                    if (token == generation) worker.postDelayed(this, WAVE_POLL_MS)
                }
            }
            worker.post(poll)
        } catch (_: RuntimeException) {
            fail(token)
        }
    }
    private fun fail(token: Int) {
        if (token != generation) return
        val errors = sinks.values.toList()
        sinks.clear()
        release()
        errors.forEach { it.error() }
    }
    fun unsubscribe(owner: Any) {
        sinks.remove(owner)
        if (sinks.isEmpty()) release()
    }
    private fun release() {
        generation++
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollHandler = null
        pollThread = null
        handler.removeCallbacks(drain)
        synchronized(frameLock) { pending = ByteArray(0); framePosted = false }
        val capture = effect
        effect = null
        if (capture != null) synchronized(effectLock) {
            runCatching { capture.enabled = false }
            runCatching { capture.release() }
        }
    }
}

private class WaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    /** Read once: the getter allocates, and onDraw asked twice per frame. Size and face are fixed. */
    private val timeMetrics = timePaint.fontMetrics
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val waveValues = FloatArray(128)
    private val waveSegments = FloatArray((128 - 1) * 4)
    private val ribbonSegments = FloatArray(256 * 4)
    private val barValues = FloatArray(32)
    private val history = FloatArray(64)
    private val previousHistory = FloatArray(64)
    private val visibleRect = Rect()
    private val density = resources.displayMetrics.density
    private var style = 0
    private var color = Color.WHITE
    private var colorful = false
    private var targetLevel = 0f
    private var drawnLevel = 0f
    private var phase = 0f
    private var lastFrameNanos = 0L
    private var lastSampleNanos = 0L
    private var frameQueued = false
    private var activePlayback = false
    private var durationMs = 0L
    private var positionMs = 0L
    private var positionUpdatedAtMs = 0L
    private var playbackSpeed = 0f
    private var seekable = false
    private var previewFraction = -1f
    private var renderedSecond = -1L
    private var elapsedText = ""
    private var durationText = ""
    var onSeek: ((Long) -> Unit)? = null

    private val frame = Choreographer.FrameCallback { frameNanos ->
        frameQueued = false
        if (style != 0 || !isShown || !getGlobalVisibleRect(visibleRect)) {
            lastFrameNanos = 0L
            return@FrameCallback
        }
        // Once per animation run, not per vsync: before Android 12 the display info is not
        // cached, so getRefreshRate() was a binder call on every frame.
        if (lastFrameNanos == 0L)
            vsyncNanos = (1_000_000_000f / (display?.refreshRate?.takeIf { it > 1f } ?: 60f)).toLong()
        val elapsed = if (lastFrameNanos == 0L) 1f / 60f
            else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, .05f)
        lastFrameNanos = frameNanos
        if (frameNanos - lastSampleNanos > 500_000_000L) targetLevel = 0f
        val follow = (elapsed * if (targetLevel > drawnLevel) 20f else 11f).coerceAtMost(1f)
        drawnLevel += (targetLevel - drawnLevel) * follow
        if (targetLevel == 0f && drawnLevel < .006f) drawnLevel = 0f
        phase = (phase + elapsed * 8f * density) % (60f * density)
        // Motion is integrated every vsync, but drawn every second one: the whole Home window
        // redrew at the panel rate while music played (278 frames in 3 s on the A17). The gap
        // has slack because vsync timestamps jitter, and a hard 22.2 ms cut would sometimes
        // wait a third frame and judder. The frame that settles to zero always draws, so the
        // ribbon never freezes raised.
        val settled = targetLevel == 0f && drawnLevel == 0f
        if (ribbonFrameDue(frameNanos, lastDrawNanos, vsyncNanos, settled)) {
            lastDrawNanos = frameNanos
            postInvalidateOnAnimation()
        }
        if (!settled) queueFrame() else lastFrameNanos = 0L
    }
    private var lastDrawNanos = 0L
    private var vsyncNanos = 16_666_667L

    fun configure(tint: Int, preset: Int, colorMode: Int, theme: Int) {
        val previousStyle = style
        color = tint; style = preset; colorful = colorMode == 2 || (colorMode == 0 && theme >= 5)
        syncSeekability()
        if (style != 0) stopFrames() else if (previousStyle != 0 && targetLevel > 0f) queueFrame()
        shader(); invalidate()
    }
    private fun canSeek() = style == 0 && seekable
    private fun syncSeekability() {
        val enabled = canSeek()
        if (!enabled && isFocused) clearFocus()
        isClickable = enabled
        isFocusable = enabled
        importantForAccessibility = if (enabled) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = if (enabled) context.getString(R.string.visualizer_seek_position) else null
    }
    private fun shader() {
        paint.color = color
        paint.shader = if (colorful && width > 0) LinearGradient(0f, 0f, width.toFloat(), 0f, color,
            Color.HSVToColor(FloatArray(3).also { Color.colorToHSV(color, it); it[1] = .65f; it[2] = .85f }), Shader.TileMode.CLAMP) else null
        railPaint.color = Color.argb((Color.alpha(color) * .30f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        thumbPaint.color = color
        timePaint.color = Color.argb((Color.alpha(color) * .72f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        focusPaint.color = color
        focusPaint.strokeWidth = 1.5f * density
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shader() }
    override fun onDetachedFromWindow() { stopFrames(); super.onDetachedFromWindow() }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    fun playback(state: PlaybackState?, metadata: MediaMetadata?) {
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: -1L
        val valid = duration > 0L && position >= 0L
        val newDuration = if (valid) duration else 0L
        val newPosition = if (valid) position.coerceAtMost(duration) else 0L
        val newUpdatedAt = if (valid) state?.lastPositionUpdateTime ?: 0L else 0L
        val newSeekable = valid && state != null && state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        val newActive = state?.state == PlaybackState.STATE_PLAYING
        val newSpeed = if (valid && newActive && state.playbackSpeed.isFinite()) state.playbackSpeed else 0f
        if (activePlayback == newActive && durationMs == newDuration && positionMs == newPosition &&
            positionUpdatedAtMs == newUpdatedAt && playbackSpeed == newSpeed && seekable == newSeekable) return
        activePlayback = newActive
        durationMs = newDuration
        positionMs = newPosition
        positionUpdatedAtMs = newUpdatedAt
        playbackSpeed = newSpeed
        seekable = newSeekable
        previewFraction = -1f
        durationText = if (valid) formatTime(duration) else ""
        renderedSecond = -1L
        syncSeekability()
        invalidate()
    }
    private fun positionAtNow(): Long {
        if (durationMs <= 0L) return 0L
        val age = if (positionUpdatedAtMs > 0L) (SystemClock.elapsedRealtime() - positionUpdatedAtMs).coerceAtLeast(0L) else 0L
        return (positionMs + (age * playbackSpeed).toLong()).coerceIn(0L, durationMs)
    }
    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val secs = seconds % 60L
        return if (minutes < 60L) "%02d:%02d".format(minutes, secs)
            else "%d:%02d:%02d".format(minutes / 60L, minutes % 60L, secs)
    }
    fun ribbonHeight(size: Int): Int {
        val captionHeight = timeMetrics.descent - timeMetrics.ascent
        val extra = (captionHeight - 14f * density).coerceAtLeast(0f)
        return ((52 + size * 16) * density + extra).toInt()
    }
    private fun trackLeft() = 6.5f * density
    private fun trackRight() = (width - 6.5f * density).coerceAtLeast(trackLeft())
    private fun seekFraction(x: Float) = ((x - trackLeft()) / (trackRight() - trackLeft()).coerceAtLeast(1f)).coerceIn(0f, 1f)
    private fun seekTo(fraction: Float) {
        if (!canSeek()) return
        val next = (durationMs * fraction.toDouble()).toLong().coerceIn(0L, durationMs)
        positionMs = next
        positionUpdatedAtMs = SystemClock.elapsedRealtime()
        renderedSecond = -1L
        onSeek?.invoke(next)
        invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canSeek()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewFraction = seekFraction(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                previewFraction = seekFraction(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val next = seekFraction(event.x)
                previewFraction = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                seekTo(next)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                previewFraction = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (canSeek() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val step = (durationMs / 20L).coerceAtLeast(1_000L)
            val next = (positionAtNow() + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0L, durationMs)
            seekTo(next.toFloat() / durationMs)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!canSeek()) return
        info.className = SeekBar::class.java.name
        info.rangeInfo = if (Build.VERSION.SDK_INT >= 30)
            AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                0f, durationMs / 1000f, positionAtNow() / 1000f)
        else legacyRangeInfo()
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    @Suppress("DEPRECATION") // RangeInfo constructor starts at API 30; Moo supports API 24.
    private fun legacyRangeInfo() = AccessibilityNodeInfo.RangeInfo.obtain(
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, durationMs / 1000f, positionAtNow() / 1000f)
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (canSeek()) {
            val next = when (action) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
                    arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1f)
                        ?.takeIf { it >= 0f }?.let { (it * 1000f).toLong() }
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                    (positionAtNow() + (durationMs / 20L).coerceAtLeast(1_000L)).coerceAtMost(durationMs)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                    (positionAtNow() - (durationMs / 20L).coerceAtLeast(1_000L)).coerceAtLeast(0L)
                else -> null
            }
            if (next != null) {
                seekTo(next.toFloat() / durationMs)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
    private fun queueFrame() {
        if (frameQueued || style != 0 || !isShown || !getGlobalVisibleRect(visibleRect)) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frame)
    }
    private fun stopFrames() {
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(frame)
        frameQueued = false
        lastFrameNanos = 0L
    }
    fun samples(signed: FloatArray, peaks: FloatArray) {
        var changed = false
        for (i in waveValues.indices) {
            val next = (waveValues[i] * .2f + signed[i] * .8f).coerceIn(-1f, 1f)
            if (abs(next - waveValues[i]) > .003f && style == 2) changed = true
            waveValues[i] = next
        }
        var energy = 0f
        for (i in barValues.indices) {
            val next = (barValues[i] * .2f + peaks[i] * .8f).coerceIn(0f, 1f)
            if (abs(next - barValues[i]) > .003f && style == 1) changed = true
            barValues[i] = next
            energy += peaks[i]
        }
        val level = kotlin.math.sqrt((energy / peaks.size).coerceIn(0f, 1f))
        targetLevel = if (level < .03f) 0f else level
        history.copyInto(previousHistory)
        history.copyInto(history, 0, 1, history.size)
        history[history.lastIndex] = targetLevel
        lastSampleNanos = System.nanoTime()
        if (style == 0) {
            if (targetLevel > 0f || drawnLevel > 0f) queueFrame()
            else if (durationMs > 0L && positionAtNow() / 1000L != renderedSecond) postInvalidateOnAnimation()
        } else if (changed) postInvalidateOnAnimation()
    }
    fun clear() {
        stopFrames()
        val hadSignal = targetLevel != 0f || drawnLevel != 0f ||
            waveValues.any { it != 0f } || barValues.any { it != 0f }
        targetLevel = 0f; drawnLevel = 0f; phase = 0f; lastSampleNanos = 0L
        waveValues.fill(0f); barValues.fill(0f); history.fill(0f); previousHistory.fill(0f)
        if (hadSignal) invalidate()
    }
    private fun historyAt(t: Float, blend: Float): Float {
        val index = (t * (history.size - 1)).coerceIn(0f, (history.size - 1).toFloat())
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(history.lastIndex)
        val mix = index - lower
        val before = previousHistory[lower] + (previousHistory[upper] - previousHistory[lower]) * mix
        val after = history[lower] + (history[upper] - history[lower]) * mix
        return before + (after - before) * blend
    }
    private fun drawRibbon(canvas: Canvas, left: Float, right: Float, baseline: Float) {
        val span = right - left
        if (span < density || drawnLevel <= 0f) return
        val count = (span / (1.5f * density)).toInt().coerceIn(2, 256)
        val step = span / count
        // Crossfades across one poll interval: a shorter window would finish each fade halfway
        // to the next sample and the ribbon would step.
        val sampleBlend = ((System.nanoTime() - lastSampleNanos) / (WAVE_POLL_MS * 1_000_000f)).coerceIn(0f, 1f)
        val maximumRise = 18f * density
        val wavelength = 60f * density
        for (i in 0 until count) {
            val x = left + (i + .5f) * step
            val t = (i + .5f) / count
            val edge = kotlin.math.sin(Math.PI * t).toFloat().let { it * it }
            val carrier = .72f + .28f * kotlin.math.sin((x + phase) * (Math.PI * 2.0 / wavelength)).toFloat()
            val recorded = historyAt(t, sampleBlend)
            val rise = maximumRise * drawnLevel * (.55f + .45f * recorded) * edge * carrier
            val offset = i * 4
            ribbonSegments[offset] = x
            ribbonSegments[offset + 1] = baseline - 2.5f * density - rise
            ribbonSegments[offset + 2] = x
            ribbonSegments[offset + 3] = baseline + 2.5f * density
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = step + 1f
        canvas.drawLines(ribbonSegments, 0, count * 4, paint)
    }
    override fun onDraw(canvas: Canvas) {
        if (!activePlayback) return
        val middle = height / 2f
        if (style == 0) {
            val left = trackLeft()
            val right = trackRight()
            val timeBaseline = height - timeMetrics.descent - 3f * density
            val baseline = timeBaseline + timeMetrics.ascent - 7.5f * density
            val fraction = if (durationMs > 0L) previewFraction.takeIf { it >= 0f }
                ?: (positionAtNow().toDouble() / durationMs).toFloat() else 1f
            val activeRight = left + (right - left) * fraction.coerceIn(0f, 1f)
            railPaint.style = Paint.Style.STROKE
            railPaint.strokeWidth = 5f * density
            canvas.drawLine(left, baseline, right, baseline, railPaint)
            if (activeRight > left && (durationMs > 0L || drawnLevel > 0f)) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 5f * density
                canvas.drawLine(left, baseline, activeRight, baseline, paint)
                drawRibbon(canvas, left, activeRight, baseline)
            }
            if (seekable) canvas.drawCircle(activeRight, baseline, 6.5f * density, thumbPaint)
            if (durationMs > 0L) {
                val second = if (previewFraction >= 0f) (durationMs * previewFraction).toLong() / 1000L
                    else positionAtNow() / 1000L
                if (second != renderedSecond) {
                    renderedSecond = second
                    elapsedText = formatTime(second * 1000L)
                }
                canvas.drawText(elapsedText, left, timeBaseline, timePaint)
                canvas.drawText(durationText, right - timePaint.measureText(durationText), timeBaseline, timePaint)
            }
            if (isFocused) canvas.drawRoundRect(1f * density, 1f * density,
                width - 1f * density, height - 1f * density, 8f * density, 8f * density, focusPaint)
            return
        }
        paint.strokeWidth = (if (style == 1) 3f else 2f) * density
        paint.style = Paint.Style.STROKE
        if (style == 1) {
            paint.strokeCap = Paint.Cap.ROUND
            val amplitude = (middle - 3f * density).coerceAtLeast(0f)
            for (i in barValues.indices) {
                val x = (i + .5f) * width / barValues.size
                val y = max(1f, barValues[i] * amplitude)
                canvas.drawLine(x, middle - y, x, middle + y, paint)
            }
            return
        }
        paint.strokeCap = Paint.Cap.BUTT
        val step = width.toFloat() / (waveValues.size - 1)
        val amplitude = (middle - 3f * density).coerceAtLeast(0f)
        var previousY = middle - waveValues[0] * amplitude
        for (i in 1 until waveValues.size) {
            val x = i * step
            val y = middle - waveValues[i] * amplitude
            val offset = (i - 1) * 4
            waveSegments[offset] = x - step
            waveSegments[offset + 1] = previousY
            waveSegments[offset + 2] = x
            waveSegments[offset + 3] = y
            previousY = y
        }
        canvas.drawLines(waveSegments, paint)
    }
}
