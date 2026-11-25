package com.mhplayer.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * [LoadControl] wrapper around [DefaultLoadControl] that implements the adaptive
 * buffer of report §22.5 / addendum §22.5: after each stall (rebuffer) the
 * minimum buffer grows by [STALL_STEP_MS] (capped at [MAX_MIN_BUFFER_MS]);
 * after [DECAY_AFTER_MS] with no rebuffer it decays one step.
 *
 * Implementation notes (read before changing):
 * - The player keeps a reference to this wrapper, never to the inner
 *   [DefaultLoadControl]. When thresholds change, the inner instance is rebuilt
 *   and swapped atomically ([@Volatile] [inner]). This is acceptable because
 *   the player only ever calls [LoadControl] methods on the wrapper, and the
 *   wrapper forwards every call to the *current* inner instance.
 * - One [DefaultAllocator] is shared across all inner rebuilds (passed via
 *   `DefaultLoadControl.Builder.setAllocator`), so buffer allocations keep a
 *   single pool even when the inner control is swapped mid-stream.
 * - When an inner instance is rebuilt mid-stream, the last
 *   [onTracksSelected] call is replayed on the new inner instance so it
 *   recomputes its target buffer size for the current period. Profile switches
 *   ([setLiveProfile]) are additionally only performed by [ExoLivePlayer] in
 *   `play()`, i.e. before `prepare()`, so the new inner also receives the
 *   player's own `onTracksSelected` for the new period.
 * - Decay is evaluated lazily inside [shouldContinueLoading] /
 *   [shouldStartPlayback] (called frequently by the player), so no extra
 *   coroutine/timer is needed.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl(live: Boolean) : LoadControl {

    companion object {
        /** Buffer growth step applied after each stall (report §22.5). */
        const val STALL_STEP_MS = 5_000

        /** Absolute cap for the (grown) minimum buffer. */
        const val MAX_MIN_BUFFER_MS = 30_000

        /** Clean time after which the grown buffer decays one step. */
        const val DECAY_AFTER_MS = 10 * 60 * 1000L

        // Live profile (report §2.2): 15_000 / 60_000 / 2_500 / 5_000, no back-buffer.
        private const val LIVE_MIN_BUFFER_MS = 15_000
        private const val LIVE_MAX_BUFFER_MS = 60_000
        private const val LIVE_BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        private const val LIVE_BACK_BUFFER_MS = 0

        // VOD profile (ARCHITECTURE.md): 30_000 / 120_000 / 2_500 / 5_000 + 60 s back-buffer.
        private const val VOD_MIN_BUFFER_MS = 30_000
        private const val VOD_MAX_BUFFER_MS = 120_000
        private const val VOD_BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val VOD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        private const val VOD_BACK_BUFFER_MS = 60_000
    }

    private val allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val lock = Any()

    @Volatile
    private var liveProfile: Boolean = live

    /** Number of +5 s stall steps currently applied (0 = base profile). */
    private var stallLevel: Int = 0

    /** [SystemClock.elapsedRealtime] of the last stall, 0 if none yet. */
    private var lastStallAtMs: Long = 0L

    @Volatile
    private var inner: DefaultLoadControl = buildInner()

    private var lastTracksSelected: Triple<LoadControl.Parameters, TrackGroupArray, Array<ExoTrackSelection?>>? = null

    /** Current minimum buffer in ms (base profile + stall steps, capped). */
    val minBufferMs: Int
        get() {
            val base = if (liveProfile) LIVE_MIN_BUFFER_MS else VOD_MIN_BUFFER_MS
            return (base + stallLevel * STALL_STEP_MS).coerceAtMost(MAX_MIN_BUFFER_MS)
        }

    /** Whether the live (true) or VOD (false) buffer profile is active. */
    fun isLiveProfile(): Boolean = liveProfile

    /**
     * Switches between the live and VOD buffer profiles. Must be called before
     * `prepare()` of the new item (which [ExoLivePlayer.play] guarantees).
     * Rebuilds the inner control only when the profile actually changes.
     */
    fun setLiveProfile(live: Boolean) {
        if (live != liveProfile) {
            liveProfile = live
            rebuildInner()
        }
    }

    /**
     * Records a stall (rebuffer). Grows the minimum buffer by one step, up to
     * the cap, and restarts the clean-time clock used for decay.
     */
    fun onStall() {
        lastStallAtMs = SystemClock.elapsedRealtime()
        val base = if (liveProfile) LIVE_MIN_BUFFER_MS else VOD_MIN_BUFFER_MS
        val maxLevel = (MAX_MIN_BUFFER_MS - base) / STALL_STEP_MS
        if (stallLevel < maxLevel) {
            stallLevel++
            rebuildInner()
        }
    }

    private fun buildInner(): DefaultLoadControl {
        val minBuffer = minBufferMs
        return if (liveProfile) {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    minBuffer,
                    LIVE_MAX_BUFFER_MS,
                    LIVE_BUFFER_FOR_PLAYBACK_MS,
                    LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(LIVE_BACK_BUFFER_MS, false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    minBuffer,
                    VOD_MAX_BUFFER_MS,
                    VOD_BUFFER_FOR_PLAYBACK_MS,
                    VOD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(VOD_BACK_BUFFER_MS, false)
                .build()
        }
    }

    private fun rebuildInner() {
        synchronized(lock) {
            val next = buildInner()
            inner = next
            // Restore the new inner's per-period state (see class doc).
            lastTracksSelected?.let { (parameters, trackGroups, trackSelections) ->
                next.onTracksSelected(parameters, trackGroups, trackSelections)
            }
        }
    }

    /** Decays one stall step after [DECAY_AFTER_MS] with no rebuffer. */
    private fun maybeDecay() {
        if (stallLevel > 0 && lastStallAtMs > 0L &&
            SystemClock.elapsedRealtime() - lastStallAtMs > DECAY_AFTER_MS
        ) {
            stallLevel--
            // Restart the clock so each step decays at most once per 10 min.
            lastStallAtMs = SystemClock.elapsedRealtime()
            rebuildInner()
        }
    }

    // ---- LoadControl delegation (all calls forward to the current inner) ----

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        inner.getBackBufferDurationUs(playerId)

    override fun onPrepared(playerId: PlayerId) {
        inner.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        lastTracksSelected = Triple(parameters, trackGroups, trackSelections)
        inner.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        inner.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        inner.onReleased(playerId)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        maybeDecay()
        return inner.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        maybeDecay()
        return inner.shouldStartPlayback(parameters)
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        inner.retainBackBufferFromKeyframe(playerId)
}
