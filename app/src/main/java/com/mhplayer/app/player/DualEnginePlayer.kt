package com.mhplayer.app.player

import android.os.SystemClock
import com.mhplayer.app.data.db.ChannelPlaybackDao
import com.mhplayer.app.data.db.ChannelPlaybackEntity
import com.mhplayer.app.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Per-channel playback memory (ARCHITECTURE.md — `ChannelPlaybackEntity`).
// Canonical definition lives in the data layer (data.db.AppDatabase):
// `channel_playback(channelId TEXT PK, preferredEngine TEXT,
// preferredContainer TEXT, hwDecoder TEXT, lastError TEXT?,
// lastSuccessAt LONG)` — one definition, one source of truth.
// DualEnginePlayer receives the Room DAO (a superset of get/upsert) via ctor.

/** Global engine setting (SessionManager stores the same AUTO|EXO|VLC strings). */
enum class EngineMode {
    AUTO,
    EXO,
    VLC,
}

/**
 * Dual-engine player: owns ONE [ExoLivePlayer] + ONE [VlcLivePlayer] and the
 * fallback state machine (addendum §16). The UI sees a single [LivePlayer];
 * it never knows which engine is active.
 *
 * Decision tree (AUTO mode, after ExoPlayer's own retry policy exhausts):
 * - DECODING_* → VLC immediately.
 * - PARSING_MANIFEST_* / 404-410 → other container in Exo first, then VLC.
 * - IO_NETWORK_* → app reconnect loop (3 attempts, 1 s / 2 s / 4 s), then
 *   VLC once. If VLC then fails → error UI (no ping-pong).
 * - BEHIND_LIVE_WINDOW → NO fallback (Exo recovers in place).
 * - DRM_* → NO fallback (VLC has no Widevine).
 * - Stall watchdog (1 s tick): Exo BUFFERING > 15 s or position frozen > 10 s
 *   → 1st seek-to-live, 2nd re-prepare, 3rd VLC. VLC Buffering > 15 s or
 *   frozen > 10 s → VLC self-recovers (stop+play) first, then Exo.
 * - Max 1 engine switch per channel selection, NEVER ping-pong. A manual
 *   [requestEngineSwitch] is explicit user intent and bypasses that cap.
 * - Manual VLC skips the Exo attempt entirely; manual EXO never auto-falls
 *   back (the UI offers "Try VLC player" instead).
 *
 * Container failover (.m3u8 ↔ .ts) rebuilds the URL through
 * `containerUrlProvider` (passed to [play]); without a provider the URL
 * extension is swapped (`.m3u8` ↔ `.ts`).
 *
 * Surface swap: the old engine is stopped BEFORE the new one plays (never
 * two streams/decoders, never a 2nd Xtream connection). The swap shows as
 * [PlaybackState.Buffering] plus [isSwitchingEngine] = true ("Switching
 * player…" overlay, 200–600 ms of black is expected).
 *
 * HbbTV: no special engine handling. HbbTV channels play as normal live A/V
 * in either engine — neither ExoPlayer nor libVLC implements an HbbTV
 * browser, and an Xtream `.ts`/`.m3u8` over HTTP carries no DVB tuner/AIT
 * (addendum §21). Interactive HbbTV is NOT available; the Details screen
 * must say so (see ARCHITECTURE.md). NO WebView overlay in v1.
 *
 * Per-channel capability table (§17.4) is implemented via memory: the
 * recorded winner (engine + container) is used on the next zap. The static
 * part of the table (HLS/DASH ladder, LL, DRM → Exo only; raw TS clean →
 * Exo first; corrupt/multi-program TS, DVB subs, legacy codecs, AC-3 →
 * VLC first; RTSP/RTMP/UDP → VLC only) cannot be detected from the URL
 * alone in v1 — it is learned from failures and persisted here.
 */
class DualEnginePlayer(
    private val exoPlayer: ExoLivePlayer,
    private val vlcPlayer: VlcLivePlayer,
    private val playbackDao: ChannelPlaybackDao,
    private val networkMonitor: NetworkMonitor? = null,
    /** Global setting; AUTO by default. May be changed live by the UI. */
    var engineMode: EngineMode = EngineMode.AUTO,
) : LivePlayer {

    companion object {
        /** App reconnect loop after Exo's own retry policy exhausts (§16.1). */
        const val MAX_RECONNECT_ATTEMPTS = 3
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        private const val WATCHDOG_BUFFERING_MS = 15_000L
        private const val WATCHDOG_FROZEN_MS = 10_000L
        private const val WATCHDOG_OPENING_MS = 30_000L
    }    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** True while an engine switch (surface swap) is in progress. */
    private val _isSwitchingEngine = MutableStateFlow(false)
    val isSwitchingEngine: StateFlow<Boolean> = _isSwitchingEngine.asStateFlow()

    override val isLive: Boolean
        get() = if (activeEngine == Engine.EXO) exoPlayer.isLive else vlcPlayer.isLive

    // ---- Per-selection state (reset on every play()) ----

    private var activeEngine: Engine = Engine.EXO
    private var startedEngine: Engine = Engine.EXO
    private var currentItem: PlayItem? = null
    private var currentContainer: Container = Container.M3U8
    private var currentProvider: ((Container) -> String)? = null

    /** Incremented on every play()/stop(); stale async work checks it. */
    private var currentSelectionId: Int = 0

    private var containerFailoverTried: Boolean = false
    private var reconnectAttempts: Int = 0
    private var switchCount: Int = 0
    private var watchdogStage: Int = 0

    private var volume: Float = 1f
    private var playStartedAtMs: Long = 0L
    private var bufferingSinceMs: Long? = null
    private var lastPositionMs: Long = -1L
    private var lastPositionChangeAtMs: Long = 0L
    private var successRecordedKey: Pair<Int, Engine>? = null

    /** Set on terminal error; used to re-prepare at the live edge on regain. */
    private var lastItemForRegain: PlayItem? = null
    private var lastProviderForRegain: ((Container) -> String)? = null
    private var lastTerminalErrorKind: PlaybackErrorKind? = null

    init {
        observeEngines()
        startWatchdog()
        networkMonitor?.let { monitor ->
            scope.launch {
                var wasConnected = monitor.isConnected.value
                monitor.isConnected.collect { connected ->
                    if (connected && !wasConnected) onNetworkRegained()
                    wasConnected = connected
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // LivePlayer
    // ------------------------------------------------------------------

    override fun play(item: PlayItem) {
        play(item, null)
    }

    /**
     * Starts [item], rebuilding the URL for the other container through
     * [containerUrlProvider] when a container failover happens. The provider
     * receives the wanted [Container] and returns its URL (typically
     * `UrlBuilder.liveUrl(creds, streamId, "m3u8"|"ts")`).
     */
    fun play(item: PlayItem, containerUrlProvider: ((Container) -> String)?) {
        currentSelectionId++
        val selectionId = currentSelectionId
        currentProvider = containerUrlProvider
        containerFailoverTried = false
        reconnectAttempts = 0
        switchCount = 0
        watchdogStage = 0
        bufferingSinceMs = null
        lastPositionMs = -1L
        lastPositionChangeAtMs = SystemClock.elapsedRealtime()
        successRecordedKey = null
        lastItemForRegain = null
        lastProviderForRegain = null
        lastTerminalErrorKind = null
        _isSwitchingEngine.value = false

        // Stop any previous playback first (never overlap engines).
        exoPlayer.stop()
        vlcPlayer.stop()
        _state.value = PlaybackState.Opening

        // Per-channel memory decides the starting engine/container in AUTO.
        // The DAO read is fast (PK lookup) but suspends, so it runs async;
        // playback starts as soon as it returns (a few ms).
        scope.launch {
            val memory = withContext(Dispatchers.IO) {
                runCatching {
                    item.channelId?.let { playbackDao.get(it) }
                }.getOrNull()
            }
            if (selectionId != currentSelectionId) return@launch

            val startEngine = when {
                item.engineHint != null -> item.engineHint
                engineMode == EngineMode.EXO -> Engine.EXO
                engineMode == EngineMode.VLC -> Engine.VLC
                memory?.preferredEngine == Engine.VLC.name -> Engine.VLC
                else -> Engine.EXO
            }
            val startContainer = when {
                engineMode != EngineMode.AUTO -> item.container
                memory != null -> runCatching { Container.valueOf(memory.preferredContainer) }
                    .getOrDefault(item.container)
                else -> item.container
            }
            val startItem = startItemFor(item, startContainer)
            startOn(startEngine, startItem, startContainer, selectionId)
        }
    }

    override fun stop() {
        currentSelectionId++
        currentItem = null
        currentProvider = null
        lastItemForRegain = null
        lastProviderForRegain = null
        lastTerminalErrorKind = null
        watchdogStage = 0
        _isSwitchingEngine.value = false
        exoPlayer.stop()
        vlcPlayer.stop()
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        activePlayer().pause()
    }

    override fun resume() {
        activePlayer().resume()
    }

    override fun seekToLive() {
        activePlayer().seekToLive()
    }

    override fun seekTo(positionMs: Long) {
        activePlayer().seekTo(positionMs)
    }

    override fun setQualityLock(maxHeight: Int) {
        // Exo applies it live (track caps rebuilt); VLC stores it for the
        // next play. Both engines get the same lock so a switch keeps it.
        exoPlayer.setQualityLock(maxHeight)
        vlcPlayer.setQualityLock(maxHeight)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        activePlayer().setVolume(this.volume)
    }

    override fun release() {
        scope.cancel()
        exoPlayer.release()
        vlcPlayer.release()
    }

    // ------------------------------------------------------------------
    // Manual engine control
    // ------------------------------------------------------------------

    /**
     * Explicit user request ("Try VLC player" / "Try ExoPlayer" button,
     * per-channel override). Bypasses the 1-switch-per-selection cap — it is
     * user intent, not ping-pong — but still stops the old engine first.
     * No-op when nothing is playing or the requested engine is already active.
     */
    fun requestEngineSwitch(engine: Engine) {
        val item = currentItem ?: return
        if (engine == activeEngine) return
        switchEngine(engine)
    }

    // ------------------------------------------------------------------
    // Engine observation (forward the active engine only)
    // ------------------------------------------------------------------

    private fun observeEngines() {
        scope.launch {
            exoPlayer.state.collect { state ->
                if (activeEngine == Engine.EXO) onEngineState(Engine.EXO, state)
            }
        }
        scope.launch {
            vlcPlayer.state.collect { state ->
                if (activeEngine == Engine.VLC) onEngineState(Engine.VLC, state)
            }
        }
        scope.launch {
            exoPlayer.bufferingPercent.collect {
                if (activeEngine == Engine.EXO) _bufferingPercent.value = it
            }
        }
        scope.launch {
            vlcPlayer.bufferingPercent.collect {
                if (activeEngine == Engine.VLC) _bufferingPercent.value = it
            }
        }
        scope.launch {
            exoPlayer.positionMs.collect { onPosition(Engine.EXO, it) }
        }
        scope.launch {
            vlcPlayer.positionMs.collect { onPosition(Engine.VLC, it) }
        }
        scope.launch {
            exoPlayer.durationMs.collect {
                if (activeEngine == Engine.EXO) _durationMs.value = it
            }
        }
        scope.launch {
            vlcPlayer.durationMs.collect {
                if (activeEngine == Engine.VLC) _durationMs.value = it
            }
        }
    }

    private fun onEngineState(engine: Engine, state: PlaybackState) {
        if (engine != activeEngine) return // Stale engine (already switched away).
        if (_isSwitchingEngine.value && state is PlaybackState.Idle) return
        if (_isSwitchingEngine.value && state !is PlaybackState.Idle) {
            _isSwitchingEngine.value = false
        }
        when (state) {
            is PlaybackState.Buffering -> {
                if (bufferingSinceMs == null) {
                    bufferingSinceMs = SystemClock.elapsedRealtime()
                }
                _state.value = state
            }
            is PlaybackState.Playing -> {
                bufferingSinceMs = null
                _state.value = state
                recordSuccessIfNeeded()
            }
            is PlaybackState.Error -> {
                bufferingSinceMs = null
                handleEngineError(engine, state.error)
            }
            else -> {
                _state.value = state
            }
        }
    }

    private fun onPosition(engine: Engine, position: Long) {
        if (engine != activeEngine) return
        _positionMs.value = position
        if (position != lastPositionMs) {
            lastPositionMs = position
            lastPositionChangeAtMs = SystemClock.elapsedRealtime()
        }
    }

    // ------------------------------------------------------------------
    // Fallback state machine (§16.1)
    // ------------------------------------------------------------------

    private fun canAutoSwitch(): Boolean =
        engineMode == EngineMode.AUTO &&
            currentItem?.engineHint == null &&
            switchCount == 0

    private fun handleEngineError(engine: Engine, error: PlaybackError) {
        val selectionId = currentSelectionId
        when (engine) {
            Engine.EXO -> handleExoError(error, selectionId)
            Engine.VLC -> handleVlcError(error, selectionId)
        }
    }

    private fun handleExoError(error: PlaybackError, selectionId: Int) {
        when (error.kind) {
            // NEVER fall back: Exo already recovered in place (or must not).
            PlaybackErrorKind.BehindLiveWindow,
            PlaybackErrorKind.Drm -> forwardError(error, selectionId)

            // Decoder failure: VLC immediately (different decoder stack).
            PlaybackErrorKind.Decoder -> {
                if (!tryAutoSwitchToVlc()) forwardError(error, selectionId)
            }

            // Parse / 404-410: other container in Exo first, then VLC.
            PlaybackErrorKind.Parse, PlaybackErrorKind.NotFound -> {
                if (!containerFailoverTried && failoverContainerInExo(selectionId)) return
                if (!tryAutoSwitchToVlc()) forwardError(error, selectionId)
            }

            // Network: app reconnect loop (≥3 attempts), then VLC once.
            PlaybackErrorKind.Network, PlaybackErrorKind.Unknown -> {
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    val attempt = reconnectAttempts++
                    scope.launch {
                        delay(RECONNECT_BACKOFF_MS[attempt])
                        if (selectionId != currentSelectionId) return@launch
                        if (activeEngine != Engine.EXO) return@launch
                        val item = currentItem ?: return@launch
                        exoPlayer.play(item)
                    }
                } else if (!tryAutoSwitchToVlc()) {
                    forwardError(error, selectionId)
                }
            }
        }
    }

    private fun handleVlcError(error: PlaybackError, selectionId: Int) {
        val vlcWasFallback = startedEngine == Engine.EXO
        if (vlcWasFallback) {
            // VLC was the one fallback switch: any failure → error UI.
            // (Includes "VLC fails within 10 s" — never loop back to Exo.)
            forwardError(error, selectionId)
            return
        }
        // VLC was the starting engine (manual VLC or per-channel memory):
        // reverse fallback to Exo once when it looks like a VLC weakness.
        when (error.kind) {
            PlaybackErrorKind.NotFound -> {
                if (!containerFailoverTried && failoverContainerInVlc(selectionId)) return
                if (!tryAutoSwitchToExo()) forwardError(error, selectionId)
            }
            PlaybackErrorKind.Decoder, PlaybackErrorKind.Parse,
            PlaybackErrorKind.Unknown -> {
                if (!tryAutoSwitchToExo()) forwardError(error, selectionId)
            }
            // Network / DRM / BehindLiveWindow: Exo would fail the same way.
            else -> forwardError(error, selectionId)
        }
    }

    private fun tryAutoSwitchToVlc(): Boolean {
        if (!canAutoSwitch()) return false
        switchEngine(Engine.VLC)
        return true
    }

    private fun tryAutoSwitchToExo(): Boolean {
        if (!canAutoSwitch()) return false
        switchEngine(Engine.EXO)
        return true
    }

    /**
     * Stops the old engine BEFORE playing on the new one. Counts toward the
     * 1-switch-per-selection cap (manual [requestEngineSwitch] bypasses the
     * cap check in [canAutoSwitch], but still increments the counter).
     */
    private fun switchEngine(engine: Engine) {
        val item = currentItem ?: return
        if (engine == activeEngine) return
        switchCount++
        _isSwitchingEngine.value = true
        _state.value = PlaybackState.Buffering(-1)
        if (engine == Engine.VLC) {
            exoPlayer.stop()
        } else {
            vlcPlayer.stop()
        }
        activeEngine = engine
        _currentEngine.value = engine
        bufferingSinceMs = null
        playStartedAtMs = SystemClock.elapsedRealtime()
        lastPositionChangeAtMs = playStartedAtMs
        if (engine == Engine.VLC) {
            vlcPlayer.setVolume(volume)
            vlcPlayer.play(item)
        } else {
            exoPlayer.setVolume(volume)
            exoPlayer.play(item)
        }
        // Safety net: never leave the "Switching player…" overlay stuck.
        scope.launch {
            delay(2_000)
            _isSwitchingEngine.value = false
        }
    }

    private fun forwardError(error: PlaybackError, selectionId: Int) {
        if (selectionId != currentSelectionId) return
        lastItemForRegain = currentItem
        lastProviderForRegain = currentProvider
        lastTerminalErrorKind = error.kind
        recordError(error)
        _state.value = PlaybackState.Error(error)
    }

    // ------------------------------------------------------------------
    // Container failover (.m3u8 ↔ .ts)
    // ------------------------------------------------------------------

    private fun otherContainer(container: Container): Container =
        if (container == Container.M3U8) Container.TS else Container.M3U8

    private fun startItemFor(item: PlayItem, container: Container): PlayItem {
        val url = resolveUrlFor(item, container) ?: item.url
        return item.copy(url = url, container = container)
    }

    /**
     * Resolves the URL for [container]: via the provider when present,
     * otherwise by swapping the `.m3u8`/`.ts` extension. Returns null when
     * the URL cannot be rebuilt (no provider and no known extension).
     */
    private fun resolveUrlFor(item: PlayItem, container: Container): String? {
        currentProvider?.let { provider ->
            return runCatching { provider(container) }.getOrNull()
        }
        val base = item.url.substringBefore('?')
        val query = item.url.substringAfter('?', "")
        val ext = if (container == Container.M3U8) "m3u8" else "ts"
        val swapped = Regex("\\.(m3u8|ts)$").replace(base, ".$ext")
        if (swapped == base) return null
        return if (query.isEmpty()) swapped else "$swapped?$query"
    }

    private fun failoverContainerInExo(selectionId: Int): Boolean {
        val item = currentItem ?: return false
        val other = otherContainer(currentContainer)
        val url = resolveUrlFor(item, other) ?: return false
        containerFailoverTried = true
        currentContainer = other
        currentItem = item.copy(url = url, container = other)
        exoPlayer.play(currentItem!!)
        return true
    }

    private fun failoverContainerInVlc(selectionId: Int): Boolean {
        val item = currentItem ?: return false
        val other = otherContainer(currentContainer)
        val url = resolveUrlFor(item, other) ?: return false
        containerFailoverTried = true
        currentContainer = other
        currentItem = item.copy(url = url, container = other)
        vlcPlayer.play(currentItem!!)
        return true
    }

    // ------------------------------------------------------------------
    // Start / watchdog / network regain
    // ------------------------------------------------------------------

    private fun startOn(engine: Engine, item: PlayItem, container: Container, selectionId: Int) {
        if (selectionId != currentSelectionId) return
        startedEngine = engine
        activeEngine = engine
        _currentEngine.value = engine
        currentItem = item
        currentContainer = container
        playStartedAtMs = SystemClock.elapsedRealtime()
        bufferingSinceMs = null
        lastPositionChangeAtMs = playStartedAtMs
        if (engine == Engine.VLC) {
            vlcPlayer.setVolume(volume)
            vlcPlayer.play(item)
        } else {
            exoPlayer.setVolume(volume)
            exoPlayer.play(item)
        }
    }

    private fun activePlayer(): LivePlayer =
        if (activeEngine == Engine.EXO) exoPlayer else vlcPlayer

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                checkWatchdog()
            }
        }
    }

    private fun checkWatchdog() {
        val item = currentItem ?: return
        if (_isSwitchingEngine.value) return
        val state = _state.value
        val now = SystemClock.elapsedRealtime()
        when (state) {
            is PlaybackState.Playing -> {
                // Healthy while the position advances; frozen > 10 s = stall.
                if (now - lastPositionChangeAtMs <= WATCHDOG_FROZEN_MS) {
                    watchdogStage = 0
                    bufferingSinceMs = null
                    return
                }
                onStallDetected(item, now)
            }
            is PlaybackState.Buffering -> {
                val since = bufferingSinceMs ?: now.also { bufferingSinceMs = it }
                if (now - since > WATCHDOG_BUFFERING_MS) onStallDetected(item, now)
            }
            is PlaybackState.Opening -> {
                // Stuck opening (e.g. no PAT/PMT on a raw .ts join): stall.
                if (now - playStartedAtMs > WATCHDOG_OPENING_MS) onStallDetected(item, now)
            }
            else -> Unit // Idle / Paused / Error: nothing to watch.
        }
    }

    private fun onStallDetected(item: PlayItem, now: Long) {
        val selectionId = currentSelectionId
        // Reset the stall clock so the next stage needs a fresh 15 s / 10 s.
        bufferingSinceMs = now
        lastPositionChangeAtMs = now
        when (activeEngine) {
            Engine.EXO -> if (item.isLive) {
                when (watchdogStage) {
                    // 1st: seek to the live edge.
                    0 -> {
                        watchdogStage = 1
                        exoPlayer.seekToLive()
                    }
                    // 2nd: full re-prepare of the same item.
                    1 -> {
                        watchdogStage = 2
                        exoPlayer.play(item)
                    }
                    // 3rd: switch to VLC (or error UI if switching is not allowed).
                    2 -> {
                        watchdogStage = 3
                        if (!tryAutoSwitchToVlc()) {
                            forwardError(
                                PlaybackError(
                                    PlaybackErrorKind.Unknown,
                                    "La lecture est bloquée",
                                    Engine.EXO,
                                ),
                                selectionId,
                            )
                        }
                    }
                    else -> Unit
                }
            } else {
                when (watchdogStage) {
                    // 1st: re-prepare at the last known position.
                    0 -> {
                        watchdogStage = 1
                        exoPlayer.rePrepareAt(_positionMs.value)
                    }
                    // 2nd: switch to VLC (or error UI if switching is not allowed).
                    1 -> {
                        watchdogStage = 2
                        if (!tryAutoSwitchToVlc()) {
                            forwardError(
                                PlaybackError(
                                    PlaybackErrorKind.Unknown,
                                    "La lecture est bloquée",
                                    Engine.EXO,
                                ),
                                selectionId,
                            )
                        }
                    }
                    else -> Unit
                }
            }
            Engine.VLC -> when (watchdogStage) {
                // 1st: let VLC self-recover (its own monitor does stop+play);
                // Dual only waits one more stall window.
                0 -> watchdogStage = 1
                // 2nd: switch back to Exo (or error UI).
                1 -> {
                    watchdogStage = 2
                    if (!tryAutoSwitchToExo()) {
                        forwardError(
                            PlaybackError(
                                PlaybackErrorKind.Unknown,
                                "La lecture est bloquée",
                                Engine.VLC,
                            ),
                            selectionId,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    /** On regain after a network error, re-prepare the same item at the live edge. */
    private fun onNetworkRegained() {
        val item = lastItemForRegain ?: return
        if (_state.value !is PlaybackState.Error) return
        // Only replay when the terminal error was network-related: replaying
        // a decoder-broken channel after a network flap would just fail again.
        if (lastTerminalErrorKind != PlaybackErrorKind.Network &&
            lastTerminalErrorKind != PlaybackErrorKind.Unknown
        ) return
        play(item, lastProviderForRegain)
    }

    // ------------------------------------------------------------------
    // Per-channel memory (addendum §16.5)
    // ------------------------------------------------------------------

    private fun recordSuccessIfNeeded() {
        val selectionId = currentSelectionId
        val engine = activeEngine
        // Keyed by (selection, engine): after a fallback switch the new
        // engine's success must overwrite the first engine's record, so the
        // next zap starts on the actual winner.
        val key = selectionId to engine
        if (successRecordedKey == key) return
        successRecordedKey = key
        val item = currentItem ?: return
        val channelId = item.channelId ?: return
        val container = currentContainer
        scope.launch(Dispatchers.IO) {
            runCatching {
                playbackDao.upsert(
                    ChannelPlaybackEntity(
                        channelId = channelId,
                        preferredEngine = engine.name,
                        preferredContainer = container.name,
                        hwDecoder = "HW",
                        lastError = null,
                        lastSuccessAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun recordError(error: PlaybackError) {
        val item = currentItem ?: return
        val channelId = item.channelId ?: return
        val engine = activeEngine
        val container = currentContainer
        scope.launch(Dispatchers.IO) {
            runCatching {
                val existing = playbackDao.get(channelId)
                val entity = existing?.copy(
                    lastError = "${error.kind}: ${error.message}",
                ) ?: ChannelPlaybackEntity(
                    channelId = channelId,
                    preferredEngine = engine.name,
                    preferredContainer = container.name,
                    hwDecoder = "HW",
                    lastError = "${error.kind}: ${error.message}",
                    lastSuccessAt = 0L,
                )
                playbackDao.upsert(entity)
            }
        }
    }
}
