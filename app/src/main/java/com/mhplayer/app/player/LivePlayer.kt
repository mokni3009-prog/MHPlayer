package com.mhplayer.app.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Shared player contract for MH Player (ARCHITECTURE.md — "Player contracts").
 *
 * Both engines ([ExoLivePlayer] and [VlcLivePlayer]) and the [DualEnginePlayer]
 * state machine implement this interface, so the UI never knows which engine is
 * active. All engines map their native events/errors onto the same
 * [PlaybackState] / [PlaybackError] taxonomy.
 */
enum class Engine {
    EXO,
    VLC,
}

enum class Container {
    M3U8,
    TS,
}

enum class PlaybackErrorKind {
    Network,
    NotFound,
    Decoder,
    Parse,
    Drm,
    Unknown,
    BehindLiveWindow,
}

data class PlaybackError(
    val kind: PlaybackErrorKind,
    val message: String,
    val engine: Engine,
)

sealed class PlaybackState {
    object Idle : PlaybackState()
    object Opening : PlaybackState()
    data class Buffering(val percent: Int = -1) : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    data class Error(val error: PlaybackError) : PlaybackState()
}

data class PlayItem(
    val url: String,
    val title: String,
    val isLive: Boolean,
    val container: Container,
    val channelId: String? = null,
    val engineHint: Engine? = null,
    val isVod: Boolean = false,
)

interface LivePlayer {
    val state: StateFlow<PlaybackState>
    val bufferingPercent: StateFlow<Int>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isLive: Boolean
    val currentEngine: StateFlow<Engine>

    fun play(item: PlayItem)
    fun stop()
    fun pause()
    fun resume()
    fun seekToLive()
    /**
     * VOD-only seek to an absolute position (ms). No-op for live items and
     * when nothing is playing. ExoPlayer seeks in the current window; VLC
     * uses `MediaPlayer.setTime`. The UI enables the seek bar for VOD only.
     */
    fun seekTo(positionMs: Long)
    /**
     * Quality lock: maximum video height (1080 / 720 / 480), or 0 = Auto
     * (no lock — display/TV cap). Applies immediately on ExoPlayer (track
     * selector caps are rebuilt live); on VLC it is stored and applied as
     * per-media `:adaptive-maxheight` options on the next play, because
     * libVLC's adaptive cap is fixed at LibVLC creation.
     */
    fun setQualityLock(maxHeight: Int)
    fun setVolume(volume: Float) // 0..1 (VLC internally allows 200% — clamp UI to 100%)
    fun release()
}
