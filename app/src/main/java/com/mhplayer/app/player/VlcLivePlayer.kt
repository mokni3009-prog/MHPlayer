package com.mhplayer.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC 3.7.8 fallback player (ARCHITECTURE.md — VlcLivePlayer; addendum
 * §§15, 17–18).
 *
 * - LAZY init: [LibVLC] (~88 MB native) is created only on the first [play],
 *   never in the constructor and never at app start. One [LibVLC] per process,
 *   one [MediaPlayer] per playback.
 * - Init options (addendum §15.2) + `--adaptive-logic=predictive` and a
 *   1920×1080 adaptive cap (§17.3).
 * - Per-media options: `:network-caching` / `:live-caching` (default 3000 ms;
 *   see [networkCachingMs]) and the shared UA.
 * - `Media(libVlc, Uri.parse(url))` — NEVER a raw String (a String URL is a
 *   documented black-screen cause: libVLC may treat it as a local path).
 * - `setHWDecoderEnabled(true, force)` — `force` defaults to false; H616 is
 *   often UNKNOWN to VLC's allowlist, so if HW fails on the real box, test
 *   [forceHwDecoder] = true and persist the result per channel.
 * - Events map onto the shared [PlaybackState]/[PlaybackError] taxonomy.
 *   libVLC gives no typed error, so `EncounteredError` is classified with a
 *   parallel HTTP HEAD probe on the shared OkHttp client: HEAD fails =
 *   Network, HEAD 404/410 = NotFound, HEAD ok = Decoder/Parse (indistinguishable
 *   from VLC alone, reported as Decoder).
 * - Live detection: `duration == 0 && !isSeekable && state in (Playing, Paused)`
 *   — Buffering is NOT counted as live (addendum §15.3).
 * - App-side retry: backoff 1 s, 2 s, 4 s, 8 s (cap 15 s + jitter), max 5
 *   attempts, then the error is surfaced (the [DualEnginePlayer] then tries
 *   the other container / ExoPlayer / error UI).
 * - Stall recovery: Buffering > 15 s or time frozen > 10 s → `stop()` +
 *   `play()` (re-opening IS the seek-to-live in VLC).
 * - Release order: stop → detachViews → release player → release media;
 *   `libVlc.release()` only in [release] (app exit). The VLC player is also
 *   released in [stop] so zapping back to an ExoPlayer channel never keeps
 *   two native cores alive.
 * - NEVER call libVLC from inside an event callback: every event is posted
 *   to the main handler first (libVLC is not reentrant — calling back in
 *   from a callback thread deadlocks).
 * - Volume is clamped to 0..1 (VLC internally allows 200%).
 * - No DRM in VLC, no CMCD/Content Steering, no MediaSession bridge in v1.
 *
 * Clock jitter: `--clock-jitter=0` is kept from the contract's init list, but
 * the addendum warns to leave clock-jitter at its default for jittery Xtream
 * TS PCR. If A/V sync issues appear on the real box, remove that option.
 */
class VlcLivePlayer(
    context: Context,
    private val okHttpClient: OkHttpClient,
    /**
     * Deinterlace for interlaced channels (addendum §20.5). Only honoured if
     * set before the first [play] — it is a [LibVLC] init option, and LibVLC
     * is created once, lazily.
     */
    private val deinterlace: Boolean = false,
) : LivePlayer {

    companion object {
        const val USER_AGENT = "MHPlayer/1.0 (Android)"

        /** Default network/live caching for live TV (addendum §18.1). */
        const val DEFAULT_NETWORK_CACHING_MS = 3_000

        /** App-side retry budget (addendum §18.4). */
        const val MAX_RETRY_ATTEMPTS = 5
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private const val RETRY_JITTER_MS = 500L

        private const val STALL_BUFFERING_MS = 15_000L
        private const val STALL_FROZEN_MS = 10_000L
        private const val PROBE_TIMEOUT_SECONDS = 3L

        private val INIT_OPTIONS = listOf(
            "--network-caching=3000",
            "--live-caching=3000",
            "--file-caching=1000",
            "--clock-jitter=0",
            "--drop-late-frames",
            "--skip-frames",
            "--vout=android-display",
            "--mediacodec-dr",
            "--http-user-agent=MHPlayer/1.0 (Android)",
            "--no-video-title-show",
            "--audio-time-stretch",
            "--adaptive-logic=predictive",
            "--adaptive-maxwidth=1920",
            "--adaptive-maxheight=1080",
        )
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _bufferingPercent = MutableStateFlow(0)
    override val bufferingPercent: StateFlow<Int> = _bufferingPercent.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentEngine = MutableStateFlow(Engine.VLC)
    override val currentEngine: StateFlow<Engine> = _currentEngine.asStateFlow()

    /**
     * Live detection (addendum §15.3): duration == 0, not seekable, and
     * actually Playing/Paused. Buffering does not count as live.
     */
    override val isLive: Boolean
        get() {
            val player = vlcPlayer ?: return false
            val s = _state.value
            return (s is PlaybackState.Playing || s is PlaybackState.Paused) &&
                player.length == 0L && !player.isSeekable
        }

    /**
     * Per-media network/live caching in ms. 3000 = stability default,
     * 5000 = flaky link, 1000 = fast zap (addendum §18.1). Takes effect on
     * the next [play]/re-open. Raised by [DualEnginePlayer] after stalls
     * (addendum §22.5: VLC cannot change caching mid-stream).
     */
    var networkCachingMs: Int = DEFAULT_NETWORK_CACHING_MS
        set(value) {
            field = value.coerceIn(500, 10_000)
        }

    /**
     * Force hardware decoding (`setHWDecoderEnabled(true, true)`). Default
     * false (ARCHITECTURE.md). Only test `true` on the real X96 Mate: if the
     * vendor decoder black/green-screens, it must stay false.
     */
    var forceHwDecoder: Boolean = false

    /**
     * Quality lock height (0 = Auto). libVLC's adaptive cap is fixed at
     * LibVLC creation (`--adaptive-maxheight`), so this value is stored and
     * applied as per-media `:adaptive-maxheight`/`:adaptive-maxwidth`
     * options on the next [play] (see [startPlayback]).
     */
    private var qualityLockHeight: Int = 0

    // Lazy LibVLC: created only on first play (never in the constructor).
    private val libVlcLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val options = INIT_OPTIONS.toMutableList()
        if (deinterlace) {
            options += "--deinterlace=1"
            options += "--deinterlace-mode=blend"
        }
        LibVLC(appContext, ArrayList(options))
    }
    private val libVlc: LibVLC get() = libVlcLazy.value

    private var vlcPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var currentItem: PlayItem? = null
    private var videoLayout: VLCVideoLayout? = null
    private var volume: Float = 1f

    private var retryAttempts: Int = 0
    private var retryJob: Job? = null

    /** Last TimeChanged value; used for the frozen-time stall detector. */
    private var lastTimeMs: Long = -1L
    private var lastTimeAdvanceAtMs: Long = 0L

    /** Buffering start (elapsedRealtime), null when not buffering. */
    private var bufferingSinceMs: Long? = null

    /** One stop()+play() recovery per stall episode (reset on progress). */
    private var stallRecoveredThisEpisode: Boolean = false

    init {
        startStallMonitor()
    }

    // ------------------------------------------------------------------
    // LivePlayer
    // ------------------------------------------------------------------

    override fun play(item: PlayItem) {
        retryJob?.cancel()
        retryJob = null
        retryAttempts = 0
        stallRecoveredThisEpisode = false
        bufferingSinceMs = null
        lastTimeMs = -1L
        lastTimeAdvanceAtMs = SystemClock.elapsedRealtime()
        currentItem = item
        _state.value = PlaybackState.Opening
        startPlayback(item)
    }

    override fun stop() {
        // Release the VLC player when zapping away: never keep two native
        // cores (Exo + VLC) alive at once.
        releasePlayer()
        currentItem = null
        _bufferingPercent.value = 0
        _positionMs.value = 0
        _durationMs.value = 0
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        vlcPlayer?.pause()
    }

    override fun resume() {
        if (currentItem == null) return
        vlcPlayer?.play()
    }

    override fun seekToLive() {
        // VLC has no live-window API: re-opening the URL IS the seek-to-live.
        if (currentItem?.isLive == true) {
            reopen()
        }
    }

    override fun seekTo(positionMs: Long) {
        // VOD only: live has no seekable timeline (seekToLive is the live op).
        if (currentItem == null || currentItem?.isLive == true) return
        // MediaPlayer.setTime seeks; no-op when the player is not ready yet
        // (the UI only offers seeking while VOD is playing).
        vlcPlayer?.setTime(positionMs.coerceAtLeast(0L))
    }

    override fun setQualityLock(maxHeight: Int) {
        // Stored; applied as per-media adaptive options on the next play.
        qualityLockHeight = maxHeight.coerceIn(0, 2160)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        // VLC volume is 0..200 (100 = 100%); never expose >100%.
        vlcPlayer?.volume = (this.volume * 100).toInt()
    }

    override fun release() {
        releasePlayer()
        currentItem = null
        if (libVlcLazy.isInitialized()) {
            runCatching { libVlc.release() }
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Surface binding (the UI layer owns the VLCVideoLayout)
    // ------------------------------------------------------------------

    /**
     * Binds the UI-owned [VLCVideoLayout] (its own SurfaceView, separate from
     * ExoPlayer's PlayerView). Pass null to detach. If a player is already
     * active, the layout is swapped live.
     */
    fun setVideoLayout(layout: VLCVideoLayout?) {
        if (videoLayout === layout) return
        val player = vlcPlayer
        if (player != null && videoLayout != null) {
            runCatching { player.detachViews() }
        }
        videoLayout = layout
        if (player != null && layout != null) {
            runCatching { player.attachViews(layout, null, false, false) }
        }
    }

    /**
     * Warms the native core in the background (e.g. called by the app after
     * the first successful ExoPlayer playback) so a later fallback does not
     * pay the LibVLC load cost on the main thread mid-zap.
     */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching { libVlc }
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private fun startPlayback(item: PlayItem) {
        releasePlayer()
        // Touching `libVlc` here is the lazy creation point.
        val lib = libVlc
        val player = MediaPlayer(lib)
        vlcPlayer = player
        player.setEventListener(eventListener)
        videoLayout?.let { layout ->
            runCatching { player.attachViews(layout, null, false, false) }
        }
        // Uri.parse — NEVER a raw String.
        val media = Media(lib, Uri.parse(item.url))
        currentMedia = media
        media.setHWDecoderEnabled(true, forceHwDecoder)
        media.addOption(":network-caching=$networkCachingMs")
        media.addOption(":live-caching=$networkCachingMs")
        media.addOption(":http-user-agent=$USER_AGENT")
        // Quality lock (settings dialog): per-media adaptive cap for this
        // play. Overrides the LibVLC init cap (1920×1080) for this item.
        if (qualityLockHeight > 0) {
            media.addOption(":adaptive-maxheight=$qualityLockHeight")
            media.addOption(":adaptive-maxwidth=${qualityLockHeight * 16 / 9}")
        }
        player.media = media
        player.volume = (volume * 100).toInt()
        player.play()
    }

    /** stop() + play() on the same item (re-open = seek-to-live in VLC). */
    private fun reopen() {
        val item = currentItem ?: return
        startPlayback(item)
    }

    /**
     * Release order: stop → detachViews → release player → release media.
     * LibVLC itself is released only in [release].
     */
    private fun releasePlayer() {
        retryJob?.cancel()
        retryJob = null
        val player = vlcPlayer
        vlcPlayer = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            runCatching { player.setEventListener(null) }
            runCatching { player.release() }
        }
        currentMedia?.let { media ->
            runCatching { media.release() }
        }
        currentMedia = null
    }

    // ------------------------------------------------------------------
    // Events — always marshalled off the callback thread (not reentrant)
    // ------------------------------------------------------------------

    private val eventListener = MediaPlayer.EventListener { event ->
        // NEVER call libVLC from inside the event callback: post to Main.
        mainHandler.post { handleVlcEvent(event) }
    }

    private fun handleVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _state.value = PlaybackState.Opening
            }
            MediaPlayer.Event.Buffering -> {
                val percent = event.buffering.toInt().coerceIn(0, 100)
                _bufferingPercent.value = percent
                if (bufferingSinceMs == null) {
                    bufferingSinceMs = SystemClock.elapsedRealtime()
                }
                _state.value = PlaybackState.Buffering(percent)
            }
            MediaPlayer.Event.Playing -> {
                bufferingSinceMs = null
                updateDuration()
                _state.value = PlaybackState.Playing
            }
            MediaPlayer.Event.Paused -> {
                _state.value = PlaybackState.Paused
            }
            MediaPlayer.Event.Stopped,
            MediaPlayer.Event.EndReached -> {
                bufferingSinceMs = null
                _bufferingPercent.value = 0
                _state.value = PlaybackState.Idle
            }
            MediaPlayer.Event.EncounteredError -> {
                onEncounteredError()
            }
            MediaPlayer.Event.TimeChanged -> {
                val time = event.timeChanged
                _positionMs.value = time.coerceAtLeast(0L)
                updateDuration()
                if (time != lastTimeMs) {
                    lastTimeMs = time
                    lastTimeAdvanceAtMs = SystemClock.elapsedRealtime()
                    stallRecoveredThisEpisode = false
                }
            }
            MediaPlayer.Event.PositionChanged,
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                // Track list changed / position ticks: no state change in v1.
            }
        }
    }

    private fun updateDuration() {
        val length = vlcPlayer?.length ?: 0L
        _durationMs.value = if (length > 0L) length else 0L
    }

    // ------------------------------------------------------------------
    // Error classification + app-side retry (addendum §18.4)
    // ------------------------------------------------------------------

    private fun onEncounteredError() {
        val item = currentItem ?: return
        scope.launch {
            val probe = probeUrl(item.url)
            when (probe) {
                ProbeResult.NOT_FOUND -> {
                    // Fail fast: retrying a 404 never helps.
                    emitError(PlaybackErrorKind.NotFound, "Chaîne indisponible")
                }
                ProbeResult.NETWORK, ProbeResult.REACHABLE -> {
                    if (retryAttempts < MAX_RETRY_ATTEMPTS) {
                        val attempt = retryAttempts++
                        // Backoff 1 s, 2 s, 4 s, 8 s (cap 15 s) + jitter.
                        val delayMs = min(
                            (1L shl attempt) * 1_000L,
                            MAX_RETRY_DELAY_MS,
                        ) + Random.nextLong(0L, RETRY_JITTER_MS)
                        _state.value = PlaybackState.Buffering(-1)
                        retryJob = scope.launch {
                            delay(delayMs)
                            if (currentItem != null && vlcPlayer != null) {
                                reopen()
                            }
                        }
                    } else {
                        val kind = if (probe == ProbeResult.NETWORK) {
                            PlaybackErrorKind.Network
                        } else {
                            // HEAD ok ⇒ decode/demux side (Parse vs Decoder
                            // is indistinguishable from VLC alone).
                            PlaybackErrorKind.Decoder
                        }
                        val message = if (kind == PlaybackErrorKind.Network) {
                            "Connexion perdue"
                        } else {
                            "Erreur de décodage"
                        }
                        emitError(kind, message)
                    }
                }
            }
        }
    }

    private fun emitError(kind: PlaybackErrorKind, message: String) {
        retryJob?.cancel()
        retryJob = null
        _state.value = PlaybackState.Error(PlaybackError(kind, message, Engine.VLC))
    }

    /**
     * Correlated HTTP HEAD probe (shared OkHttp client, short timeouts, same
     * connection pool). Any HTTP response — even an error status — means the
     * server is reachable; only a transport failure means Network.
     */
    private suspend fun probeUrl(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val probeClient = okHttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()
            probeClient.newCall(request).execute().use { response ->
                when (response.code) {
                    404, 410 -> ProbeResult.NOT_FOUND
                    else -> ProbeResult.REACHABLE
                }
            }
        } catch (_: Exception) {
            ProbeResult.NETWORK
        }
    }

    private enum class ProbeResult { NETWORK, NOT_FOUND, REACHABLE }

    // ------------------------------------------------------------------
    // Stall monitor: Buffering > 15 s or time frozen > 10 s → stop()+play()
    // ------------------------------------------------------------------

    private fun startStallMonitor() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                checkStall()
            }
        }
    }

    private fun checkStall() {
        val player = vlcPlayer ?: return
        if (currentItem == null || stallRecoveredThisEpisode) return
        val now = SystemClock.elapsedRealtime()
        val state = _state.value
        val bufferingTooLong = state is PlaybackState.Buffering &&
            bufferingSinceMs != null && now - bufferingSinceMs!! > STALL_BUFFERING_MS
        val timeFrozen = state is PlaybackState.Playing &&
            lastTimeMs >= 0L && now - lastTimeAdvanceAtMs > STALL_FROZEN_MS
        if (bufferingTooLong || timeFrozen) {
            stallRecoveredThisEpisode = true
            bufferingSinceMs = null
            // Re-opening re-joins at the live edge (VLC has no seek-to-live).
            mainHandler.post { reopen() }
        }
    }
}
