package com.mhplayer.app.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.CacheDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.common.ParserException
import androidx.media3.datasource.cache.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.cmcd.CmcdConfiguration
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient

/**
 * Media3 1.11.1 primary live player (ARCHITECTURE.md — ExoLivePlayer;
 * research report §§1–14).
 *
 * Configuration summary (all values from the contract):
 * - ONE shared [OkHttpClient] (app singleton, passed in). The player derives a
 *   client from it with connect 8 s / read 15 s — derived via `newBuilder()`,
 *   so the connection pool and dispatcher stay shared with the Xtream API and
 *   image loading, while the player gets its own timeouts. (The
 *   `OkHttpDataSource.Factory` in Media3 1.11.1 exposes no timeout setters;
 *   timeouts must live on the OkHttp client.)
 * - UA `MHPlayer/1.0 (Android)` on the data source.
 * - Shared [DefaultBandwidthMeter] singleton (`getSingletonInstance`), reset on
 *   network-type change (its default).
 * - [AdaptiveTrackSelection]: bandwidthFraction 0.75, up after 5 s buffered,
 *   down after 25 s (rise fast to highest quality, per user rule).
 * - Track caps: TV/H616 profile → 1920×1080, HEVC+AVC preferred (AV1
 *   excluded — no AV1 HW decoder on H616); phones → display size.
 *   Tunnelling only on the TV profile AND only if
 *   [PackageManager.FEATURE_TUNNELED_PLAYBACK] is present.
 * - [AdaptiveLoadControl]: live 15_000/60_000/2_500/5_000, VOD
 *   30_000/120_000/2_500/5_000 + 60 s back-buffer, `prioritizeTimeOverSize`.
 * - Renderers: decoder fallback on, extension renderer mode ON.
 * - Media source factory: live target offset 6_000 ms (min 2_000 / max
 *   30_000), [CustomRetryPolicy], CMCD factory. HLS chunkless preparation is
 *   ON by default in Media3 1.11.1 (no code needed to enable it).
 * - Live speed control: fallback 0.97/1.03, +2_000 ms target offset per
 *   rebuffer. Playback speed is NEVER pinned to 1.0f on live.
 * - Cache: [SimpleCache] 256 MB, `FLAG_IGNORE_CACHE_ON_ERROR`, used for VOD
 *   only — live segments pass through the plain OkHttp factory.
 * - `LiveConfiguration` (target 6_000 / min 2_000 / max 30_000, speed
 *   0.97–1.03) on EVERY live item, including raw `.ts`
 *   ([androidx.media3.exoplayer.source.ProgressiveMediaSource] progressive-live
 *   via the default factory + live configuration).
 * - `EventLogger` in debug builds only.
 *
 * Lifecycle: exactly ONE [ExoPlayer] instance per [DualEnginePlayer], created
 * in the constructor and released in [release]. The player is reused across
 * zaps (`setMediaSource` + `prepare`); never recreated per channel.
 *
 * DRM: no [androidx.media3.exoplayer.drm.DrmSessionManager] is attached in v1
 * because [PlayItem] carries no `drmConfiguration` and Xtream live/VOD is
 * overwhelmingly clear (report §7.1). If a future [PlayItem] gains DRM info,
 * attach a `DefaultDrmSessionManager` only for that item, and cap DRM
 * renditions to SD on Widevine L3 (H616).
 *
 * The UI layer owns the [PlayerView] (SurfaceView, `surface_type`
 * `surface_view`); this class only binds it via [setPlayerView].
 */
@OptIn(UnstableApi::class)
class ExoLivePlayer(
    context: Context,
    okHttpClient: OkHttpClient,
    /** True on the X96 Mate / TV-box profile, false on phones. */
    private val isTvProfile: Boolean = false,
) : LivePlayer {

    companion object {
        const val USER_AGENT = "MHPlayer/1.0 (Android)"

        // Player HTTP timeouts (report §5.3). Applied on the derived OkHttp client.
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val READ_TIMEOUT_MS = 15_000L

        // LiveConfiguration (report §3.1).
        private const val LIVE_TARGET_OFFSET_MS = 6_000L
        private const val LIVE_MIN_OFFSET_MS = 2_000L
        private const val LIVE_MAX_OFFSET_MS = 30_000L
        private const val LIVE_MIN_SPEED = 0.97f
        private const val LIVE_MAX_SPEED = 1.03f

        // Live-offset monitor (report §3.6): re-seek to the edge if the drift
        // stays beyond the max offset for longer than this.
        private const val MAX_OFFSET_DRIFT_MS = 10_000L

        // Resume: if paused live drifted this far behind, jump to the edge.
        private const val RESUME_DRIFT_MS = 10_000L

        private const val CACHE_MAX_BYTES = 256L * 1024L * 1024L
        private const val CACHE_DIR_NAME = "media"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _bufferingPercent = MutableStateFlow(0)
    override val bufferingPercent: StateFlow<Int> = _bufferingPercent.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentEngine = MutableStateFlow(Engine.EXO)
    override val currentEngine: StateFlow<Engine> = _currentEngine.asStateFlow()

    /** Live when the current item is a live item (see also [ExoPlayer.isCurrentWindowLive]). */
    override val isLive: Boolean
        get() = currentItem?.isLive == true

    private val bandwidthMeter: DefaultBandwidthMeter =
        DefaultBandwidthMeter.getSingletonInstance(appContext)

    private val trackSelector: DefaultTrackSelector
    private val loadControl = AdaptiveLoadControl(live = true)
    private val retryPolicy = CustomRetryPolicy()

    private val httpDataSourceFactory: DataSource.Factory
    private val liveMediaSourceFactory: DefaultMediaSourceFactory

    /** VOD media-source factory (CacheDataSource); built once the cache is ready. */
    @Volatile
    private var vodMediaSourceFactory: MediaSource.Factory? = null

    private val player: ExoPlayer

    private var playerView: PlayerView? = null
    private var currentItem: PlayItem? = null
    private var currentMediaItem: MediaItem? = null
    private var volume: Float = 1f

    /** Quality lock height (0 = Auto). Applied in [applyTrackCaps]. */
    private var qualityLockHeight: Int = 0

    /** True once STATE_READY has been reached for the current item. */
    private var wasReady: Boolean = false

    /** One-shot in-place recovery for AUDIO/VIDEO_TRACK_INIT_FAILED. */
    private var trackInitRecovered: Boolean = false

    /** Live-offset drift start (elapsedRealtime), null when within bounds. */
    private var driftSinceMs: Long? = null

    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                ExoPlayer.STATE_BUFFERING -> {
                    // Rebuffer (not initial buffering): grow the adaptive buffer.
                    if (wasReady) loadControl.onStall()
                    _state.value = PlaybackState.Buffering(player.bufferedPercentage)
                }
                ExoPlayer.STATE_READY -> {
                    wasReady = true
                    _state.value =
                        if (player.playWhenReady) PlaybackState.Playing else PlaybackState.Paused
                }
                ExoPlayer.STATE_ENDED -> {
                    _state.value = PlaybackState.Idle
                }
                ExoPlayer.STATE_IDLE -> {
                    // Deliberately ignored: onPlayerError sets Error here, and
                    // stop() sets Idle explicitly. Overwriting would hide errors.
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player.playbackState == ExoPlayer.STATE_READY) {
                _state.value =
                    if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    init {
        // --- ABR / track selection (report §1) ---
        val adaptiveFactory = AdaptiveTrackSelection.Factory(bandwidthMeter)
            .setBandwidthFraction(0.75f)
            .setMinDurationForQualityIncreaseMs(5_000)
            .setMaxDurationForQualityDecreaseMs(25_000)
        trackSelector = DefaultTrackSelector(appContext, adaptiveFactory)
        applyTrackCaps()

        // --- Network stack (report §5): shared client, player timeouts. ---
        val playerClient = okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        httpDataSourceFactory = OkHttpDataSource.Factory(playerClient)
            .setUserAgent(USER_AGENT)

        val cmcdFactory = CmcdConfiguration.Factory()
        liveMediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
            .setLiveMinOffsetMs(LIVE_MIN_OFFSET_MS)
            .setLiveMaxOffsetMs(LIVE_MAX_OFFSET_MS)
            .setLiveMinPlaybackSpeed(LIVE_MIN_SPEED)
            .setLiveMaxPlaybackSpeed(LIVE_MAX_SPEED)
            .setLoadErrorHandlingPolicy(retryPolicy)
            .setCmcdConfigurationFactory(cmcdFactory)

        // --- Player (report §§2–3, 6, 10) ---
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        player = ExoPlayer.Builder(appContext)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(liveMediaSourceFactory)
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setFallbackMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .setTargetLiveOffsetIncrementOnRebufferMs(2_000)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        // EventLogger in debug builds only (FLAG_DEBUGGABLE, not BuildConfig —
        // avoids depending on BuildConfig generation being enabled).
        val debuggable =
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            player.addAnalyticsListener(EventLogger())
        }

        // --- VOD disk cache (report §8.7): SimpleCache does file I/O in its
        // constructor, so build it off the main thread. Until it is ready, VOD
        // plays uncached through the live factory (live is unaffected). ---
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cache = SimpleCache(
                    File(appContext.cacheDir, CACHE_DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(appContext),
                )
                val cacheFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                vodMediaSourceFactory = DefaultMediaSourceFactory(appContext)
                    .setDataSourceFactory(cacheFactory)
                    .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setLiveMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setLiveMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    .setLiveMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setLiveMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .setLoadErrorHandlingPolicy(retryPolicy)
                    .setCmcdConfigurationFactory(cmcdFactory)
            }
        }

        startMonitor()
    }

    // ------------------------------------------------------------------
    // LivePlayer
    // ------------------------------------------------------------------

    override fun play(item: PlayItem) {
        currentItem = item
        currentMediaItem = buildMediaItem(item)
        wasReady = false
        trackInitRecovered = false
        driftSinceMs = null

        // Live vs VOD buffer profile (swapped before prepare — see
        // AdaptiveLoadControl docs).
        loadControl.setLiveProfile(!item.isVod)

        val factory =
            if (item.isVod) vodMediaSourceFactory ?: liveMediaSourceFactory
            else liveMediaSourceFactory
        val source = factory.createMediaSource(currentMediaItem!!)
        player.setMediaSource(source)
        player.volume = volume
        player.prepare()
        player.playWhenReady = true
        _state.value = PlaybackState.Opening
    }

    override fun stop() {
        currentItem = null
        currentMediaItem = null
        wasReady = false
        driftSinceMs = null
        player.stop()
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun resume() {
        if (currentItem == null) return
        // Live: never resume a stale position. If we drifted behind the edge
        // while paused, jump to live first (report §9.1).
        if (isLive && player.isCurrentWindowLive) {
            val offset = player.currentLiveOffset
            if (offset != C.TIME_UNSET && offset > RESUME_DRIFT_MS) {
                player.seekToDefaultPosition()
            }
        }
        player.playWhenReady = true
        player.play()
    }

    override fun seekToLive() {
        if (currentItem?.isLive != true) return
        if (player.playbackState == ExoPlayer.STATE_IDLE) {
            player.prepare()
        }
        // The only safe live seek (report §3.7).
        player.seekToDefaultPosition()
    }

    override fun seekTo(positionMs: Long) {
        // VOD only: live has no seekable timeline (seekToLive is the live op).
        if (currentItem == null || currentItem?.isLive == true) return
        // ExoPlayer queues the seek if not ready yet; it is honoured once
        // the window is prepared. Clamping is done by the player itself.
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setQualityLock(maxHeight: Int) {
        qualityLockHeight = maxHeight.coerceIn(0, 2160)
        applyTrackCaps()
    }

    /**
     * Re-prepares the current item, resuming VOD at [positionMs] (a seek
     * issued right after `prepare()` is honoured once ready). For live items
     * the position is ignored — playback restarts at the live edge. Used by
     * the [DualEnginePlayer] stall watchdog for VOD so a stall does not
     * restart the movie from the beginning.
     */
    fun rePrepareAt(positionMs: Long) {
        val item = currentItem ?: return
        val mediaItem = currentMediaItem ?: return
        wasReady = false
        driftSinceMs = null
        val factory =
            if (item.isVod) vodMediaSourceFactory ?: liveMediaSourceFactory
            else liveMediaSourceFactory
        player.setMediaSource(factory.createMediaSource(mediaItem))
        player.prepare()
        if (!item.isLive && positionMs > 0L) {
            player.seekTo(positionMs)
        }
        player.playWhenReady = true
        _state.value = PlaybackState.Opening
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        player.volume = this.volume
    }

    override fun release() {
        scope.cancel()
        playerView?.player = null
        playerView = null
        player.removeListener(listener)
        player.release()
    }

    // ------------------------------------------------------------------
    // Surface binding (the UI layer owns the PlayerView)
    // ------------------------------------------------------------------

    /**
     * Binds the UI-owned [PlayerView] (SurfaceView) to this player. Pass null
     * to detach. The view's `surface_type` must be `surface_view` (set in the
     * layout by the UI layer).
     */
    fun setPlayerView(view: PlayerView?) {
        if (playerView === view) return
        playerView?.player = null
        playerView = view
        view?.player = player
    }

    // ------------------------------------------------------------------
    // Configuration helpers
    // ------------------------------------------------------------------

    private fun applyTrackCaps() {
        // Quality lock (settings dialog) wins over the display/TV cap:
        // a 720p lock caps to 1280×720 even on the TV profile.
        val (maxWidth, maxHeight) = if (qualityLockHeight > 0) {
            (qualityLockHeight * 16 / 9) to qualityLockHeight
        } else if (isTvProfile) {
            1920 to 1080
        } else {
            displaySize()
        }
        val tunnelingEnabled = isTvProfile &&
            appContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_TUNNELED_PLAYBACK,
            )
        val builder = trackSelector.buildUponParameters()
            .setMaxVideoSize(maxWidth, maxHeight)
            .setTunnelingEnabled(tunnelingEnabled)
        if (isTvProfile) {
            // H616: no AV1 HW decoder. Prefer HEVC, then AVC; AV1 is excluded
            // from the preferred list (report §§1.8, 6.4).
            builder.setPreferredVideoMimeTypes(
                arrayOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264),
            )
        }
        trackSelector.parameters = builder.build()
    }

    private fun displaySize(): Pair<Int, Int> {
        val windowManager =
            appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun buildMediaItem(item: PlayItem): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(item.url))
        mimeTypeForUrl(item.url)?.let { builder.setMimeType(it) }
        if (item.isLive) {
            // LiveConfiguration on EVERY live item, including raw .ts
            // (progressive-live). Never rely on defaults (report §3.1).
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    .setMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun mimeTypeForUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            else -> null // Let the factory sniff (e.g. VOD .mp4/.mkv).
        }
    }

    // ------------------------------------------------------------------
    // Live-offset monitor + position flows (report §§3.6, 14)
    // ------------------------------------------------------------------

    private fun startMonitor(): Job = scope.launch {
        while (isActive) {
            delay(1_000)
            _positionMs.value = player.currentPosition.coerceAtLeast(0L)
            _durationMs.value = player.duration
                .takeIf { it != C.TIME_UNSET }
                ?.coerceAtLeast(0L) ?: 0L
            _bufferingPercent.value = player.bufferedPercentage
            checkLiveOffset()
        }
    }

    private fun checkLiveOffset() {
        if (currentItem?.isLive != true || !player.isCurrentWindowLive) {
            driftSinceMs = null
            return
        }
        val offset = player.currentLiveOffset
        val now = android.os.SystemClock.elapsedRealtime()
        if (offset != C.TIME_UNSET && offset > LIVE_MAX_OFFSET_MS) {
            val since = driftSinceMs ?: now.also { driftSinceMs = it }
            if (now - since > MAX_OFFSET_DRIFT_MS) {
                driftSinceMs = null
                player.seekToDefaultPosition()
            }
        } else {
            driftSinceMs = null
        }
    }

    // ------------------------------------------------------------------
    // Error dispatch (report §9 error matrix)
    // ------------------------------------------------------------------

    private fun handlePlayerError(error: PlaybackException) {
        wasReady = false
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                // NEVER engine-fallback: recover in place at the live edge.
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
                _state.value = PlaybackState.Buffering(player.bufferedPercentage)
            }
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> {
                emitError(PlaybackErrorKind.Decoder, "Erreur de décodage")
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                emitError(PlaybackErrorKind.Parse, "Flux illisible")
            }
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                when (responseCode(error)) {
                    404, 410 -> emitError(PlaybackErrorKind.NotFound, "Chaîne indisponible")
                    else -> emitError(PlaybackErrorKind.Network, "Erreur réseau")
                }
            }
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                emitError(PlaybackErrorKind.NotFound, "Chaîne indisponible")
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> {
                emitError(PlaybackErrorKind.Network, "Connexion perdue")
            }
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_TRACK_INIT_FAILED -> {
                // Recreate the sink once by re-preparing; then surface the error.
                val mediaItem = currentMediaItem
                if (!trackInitRecovered && mediaItem != null) {
                    trackInitRecovered = true
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                    _state.value = PlaybackState.Buffering(player.bufferedPercentage)
                } else {
                    emitError(PlaybackErrorKind.Decoder, "Erreur de décodage")
                }
            }
            else -> {
                if (error.errorCodeName.startsWith("ERROR_CODE_DRM")) {
                    // DRM: licence retry → SD fallback → DRM error UI in the
                    // app layer. NO VLC fallback (VLC has no Widevine).
                    emitError(PlaybackErrorKind.Drm, "Contenu protégé non pris en charge")
                } else {
                    emitError(PlaybackErrorKind.Unknown, "Erreur de lecture")
                }
            }
        }
    }

    private fun emitError(kind: PlaybackErrorKind, message: String) {
        _state.value = PlaybackState.Error(PlaybackError(kind, message, Engine.EXO))
    }

    /** Walks the cause chain for an HTTP status code, if any. */
    private fun responseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }
}

/**
 * Retry policy for segment/manifest loads (report §4.2).
 *
 * - Minimum 8 retries for every loadable type (stock is 3, 6 for
 *   progressive-live — too small for flaky IPTV).
 * - Delay = `errorCount × 1000 ms`, capped at 5_000 ms, only for transient
 *   errors (timeouts, connection failures, DNS failures, 5xx/429).
 * - Fail fast ([C.TIME_UNSET]) on 4xx and parser errors — retrying those
 *   never helps.
 * - Track/location fallback exclusion keeps the stock defaults (60 s / 5 min).
 */
@OptIn(UnstableApi::class)
class CustomRetryPolicy : DefaultLoadErrorHandlingPolicy() {

    companion object {
        const val MIN_RETRY_COUNT = 8
        const val MAX_RETRY_DELAY_MS = 5_000L
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MIN_RETRY_COUNT

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        if (!isTransient(loadErrorInfo.exception)) return C.TIME_UNSET
        return (loadErrorInfo.errorCount * 1_000L).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun isTransient(exception: IOException): Boolean {
        var cause: Throwable? = exception
        while (cause != null) {
            when (cause) {
                // Fail fast: these can never succeed on retry.
                is ParserException,
                is FileNotFoundException,
                is HttpDataSource.CleartextNotPermittedException -> return false
                is HttpDataSource.InvalidResponseCodeException -> {
                    // 4xx (except 429) fails fast; 429 and 5xx are transient.
                    return cause.responseCode == 429 || cause.responseCode in 500..599
                }
            }
            cause = cause.cause
        }
        return when (exception) {
            is SocketTimeoutException,
            is ConnectException,
            is UnknownHostException -> true
            // Any other IOException (reset, broken pipe, …) is treated as
            // transient; the cap + min retry count bound the damage.
            else -> true
        }
    }
}
