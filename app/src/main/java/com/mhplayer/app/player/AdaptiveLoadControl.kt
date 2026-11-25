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
 * - When an inner instance is rebuilt mid-stream, the new instance is NOT
 *   touched inside [rebuildInner] (v1.5 — see below). Instead the wrapper
 *   lazily prepares it on the player's playback thread the first time the
 *   player calls the wrapper afterwards ([ensurePreparedLocked]), replaying
 *   the last [onTracksSelected] call then, so it recomputes its target
 *   buffer size for the current period. Profile switches ([setLiveProfile])
 *   are additionally only performed by [ExoLivePlayer] in `play()`, i.e.
 *   before `prepare()`, so the new inner also receives the player's own
 *   `onTracksSelected` for the new period.
 * - v1.5 CRASH FIX (D1 id 9, NPE in DefaultLoadControl.onTracksSelected):
 *   a fresh DefaultLoadControl only accepts onTracksSelected /
 *   shouldContinueLoading for a playerId AFTER onPrepared(playerId) was
 *   called on THAT instance — its internal loadingStates map is filled by
 *   onPrepared, and onTracksSelected does
 *   checkNotNull(loadingStates.get(playerId)) (Media3 1.11.1,
 *   DefaultLoadControl.java:730). The old rebuildInner() replayed the
 *   stored selections on the fresh inner without onPrepared -> NPE on the
 *   first stall. It must also NOT call onPrepared from the listener thread
 *   (main): onPrepared pins the inner to the calling thread, and the
 *   player's next prepare() (playback thread) would then fail
 *   DefaultLoadControl's thread check. Hence the lazy prepare above,
 *   always on the playback thread, atomic with the forwarded call under
 *   [lock] (a stall on the main thread must not interleave with a
 *   shouldContinueLoading on the playback thread).
 * - Defense in depth: every call into the inner LoadControl is wrapped in
 *   try/catch — a load-control error degrades to default buffering, it
 *   never crashes the app.
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

    /**
     * PlayerIds the player has prepared on this wrapper (added in
     * [onPrepared], removed in [onStopped]/[onReleased]). A fresh inner
     * instance is only auto-prepared for a playerId in this set — never for
     * a player the player has not prepared (or has already released).
     * Guarded by [lock].
     */
    private val seenPlayerIds = mutableSetOf<PlayerId>()

    /**
     * PlayerIds already onPrepared on the CURRENT inner instance. Cleared on
     * every rebuild; repopulated lazily by [ensurePreparedLocked] on the
     * playback thread. Guarded by [lock].
     */
    private val preparedOnCurrentInner = mutableSetOf<PlayerId>()

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
            try {
                rebuildInner()
            } catch (_: Throwable) {
                // A profile switch must never crash the app; the old inner
                // keeps serving with the previous profile.
            }
        }
    }

    /**
     * Records a stall (rebuffer). Grows the minimum buffer by one step, up to
     * the cap, and restarts the clean-time clock used for decay.
     */
    fun onStall() {
        try {
            lastStallAtMs = SystemClock.elapsedRealtime()
            val base = if (liveProfile) LIVE_MIN_BUFFER_MS else VOD_MIN_BUFFER_MS
            val maxLevel = (MAX_MIN_BUFFER_MS - base) / STALL_STEP_MS
            if (stallLevel < maxLevel) {
                stallLevel++
                rebuildInner()
            }
        } catch (_: Throwable) {
            // A stall must never crash the app; the buffer just stays put.
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

    /**
     * Swaps in a freshly built inner control with the current thresholds.
     *
     * v1.5: this ONLY swaps the instance and resets the prepared tracking.
     * It deliberately does NOT call onPrepared/onTracksSelected on the new
     * inner: a fresh DefaultLoadControl has an empty loadingStates map, so
     * onTracksSelected would throw NPE (checkNotNull at
     * DefaultLoadControl.java:730), and calling onPrepared here would pin
     * the inner to the WRONG thread (onStall runs on the main thread, while
     * the player prepares on its playback thread). The new inner is prepared
     * lazily by [ensurePreparedLocked] on the playback thread, atomically
     * with the next forwarded player call.
     */
    private fun rebuildInner() {
        try {
            synchronized(lock) {
                inner = buildInner()
                preparedOnCurrentInner.clear()
            }
        } catch (_: Throwable) {
            // A failed rebuild must never crash the app; the old inner is
            // gone, but the next forwarded call re-prepares the new one.
        }
    }

    /**
     * Makes sure the CURRENT inner instance is prepared for [playerId] before
     * any LoadControl method is forwarded to it. Must be called with [lock]
     * held, on the player's playback thread (all LoadControl forwarding
     * happens there). Prepares the fresh inner on first use after a rebuild
     * and replays the last known non-null track selections so it recomputes
     * its target buffer for the current period. Never passes null to the
     * inner: the replay is skipped when no selections were ever recorded.
     * Throws only if the inner itself throws (callers catch it).
     */
    private fun ensurePreparedLocked(playerId: PlayerId, replay: Boolean = true) {
        if (playerId !in seenPlayerIds || playerId in preparedOnCurrentInner) return
        inner.onPrepared(playerId)
        preparedOnCurrentInner.add(playerId)
        if (replay) {
            try {
                lastTracksSelected?.let { (parameters, trackGroups, trackSelections) ->
                    inner.onTracksSelected(parameters, trackGroups, trackSelections)
                }
            } catch (_: Throwable) {
                // Best-effort replay: the player's own next onTracksSelected
                // repopulates the target buffer.
            }
        }
    }

    /** Decays one stall step after [DECAY_AFTER_MS] with no rebuffer. */
    private fun maybeDecay() {
        try {
            if (stallLevel > 0 && lastStallAtMs > 0L &&
                SystemClock.elapsedRealtime() - lastStallAtMs > DECAY_AFTER_MS
            ) {
                stallLevel--
                // Restart the clock so each step decays at most once per 10 min.
                lastStallAtMs = SystemClock.elapsedRealtime()
                rebuildInner()
            }
        } catch (_: Throwable) {
            // Decay is best-effort; it must never crash the app.
        }
    }

    // ---- LoadControl delegation (all calls forward to the current inner) ----

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        inner.getBackBufferDurationUs(playerId)

    override fun onPrepared(playerId: PlayerId) {
        try {
            synchronized(lock) {
                seenPlayerIds.add(playerId)
                inner.onPrepared(playerId)
                preparedOnCurrentInner.add(playerId)
            }
        } catch (_: Throwable) {
            // Preparation failure must never crash the app; the next
            // forwarded call retries via ensurePreparedLocked.
        }
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        try {
            synchronized(lock) {
                // The playerId is known-prepared from here on, even if the
                // player's own onPrepared was somehow missed.
                seenPlayerIds.add(parameters.playerId)
                ensurePreparedLocked(parameters.playerId, replay = false)
                lastTracksSelected = Triple(parameters, trackGroups, trackSelections)
                inner.onTracksSelected(parameters, trackGroups, trackSelections)
            }
        } catch (_: Throwable) {
            // A track-selection failure must never crash the app.
        }
    }

    override fun onStopped(playerId: PlayerId) {
        try {
            synchronized(lock) {
                seenPlayerIds.remove(playerId)
                preparedOnCurrentInner.remove(playerId)
                inner.onStopped(playerId)
            }
        } catch (_: Throwable) {
            // Ignored: stopping must never crash the app.
        }
    }

    override fun onReleased(playerId: PlayerId) {
        try {
            synchronized(lock) {
                seenPlayerIds.remove(playerId)
                preparedOnCurrentInner.remove(playerId)
                inner.onReleased(playerId)
            }
        } catch (_: Throwable) {
            // Ignored: releasing must never crash the app.
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        try {
            maybeDecay()
        } catch (_: Throwable) {
            // Ignored: decay is best-effort.
        }
        return try {
            synchronized(lock) {
                ensurePreparedLocked(parameters.playerId)
                inner.shouldContinueLoading(parameters)
            }
        } catch (_: Throwable) {
            // Load-control failure degrades to "keep loading" so playback
            // can proceed; it never crashes the app.
            true
        }
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        try {
            maybeDecay()
        } catch (_: Throwable) {
            // Ignored: decay is best-effort.
        }
        return try {
            synchronized(lock) {
                ensurePreparedLocked(parameters.playerId)
                inner.shouldStartPlayback(parameters)
            }
        } catch (_: Throwable) {
            // Load-control failure degrades to "wait for buffer"; the next
            // call retries the lazy prepare. It never crashes the app.
            false
        }
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
