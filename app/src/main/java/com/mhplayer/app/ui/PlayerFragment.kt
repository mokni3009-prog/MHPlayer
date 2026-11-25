package com.mhplayer.app.ui

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.data.session.SessionManager
import com.mhplayer.app.databinding.DialogPlayerSettingsBinding
import com.mhplayer.app.databinding.FragmentPlayerBinding
import com.mhplayer.app.player.Container
import com.mhplayer.app.player.Engine
import com.mhplayer.app.player.EngineMode
import com.mhplayer.app.player.PlayItem
import com.mhplayer.app.player.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fullscreen player: video fills the screen; top bar (back arrow + title +
 * red EN DIRECT badge for live); bottom bar (play/pause, progress bar +
 * times, volume, settings gear, fullscreen). Controls auto-hide after 3s;
 * OK toggles play/pause; BACK exits. Landscape is forced while playing.
 *
 * Settings menu: engine (Auto/ExoPlayer/VLC), quality lock
 * (Auto/1080p/720p/480p), container (Auto/HLS/TS). VOD: seek bar is live —
 * dragging seeks (LivePlayer.seekTo); live: seek bar hidden for raw TS,
 * "Go Live" for HLS. The quality lock applies immediately (Exo track caps
 * rebuilt live; VLC stores it for the next play).
 * "Switching player…" overlay while DualEnginePlayer.isSwitchingEngine.
 * Error state with [Réessayer] [Autre format] [Changer de lecteur].
 */
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private var kind: String = "live"
    private var streamId: Int = 0
    private var title: String = ""
    private var ext: String = "m3u8"
    private var isLive: Boolean = true
    private var channelId: String? = null

    /** Container preference for this playback: "auto" | "hls" | "ts". */
    private var containerPref: String = "auto"
    private var currentItem: PlayItem? = null
    private var muted = false
    private var controlsVisible = true
    private var observeJob: Job? = null

    /** True while the user is dragging the VOD seek bar (progress updates paused). */
    private var isSeeking = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        kind = args.getString(PlaybackHelper.ARG_KIND, "live")
        streamId = args.getInt(PlaybackHelper.ARG_STREAM_ID, 0)
        title = args.getString(PlaybackHelper.ARG_TITLE, "")
        ext = args.getString(PlaybackHelper.ARG_EXT, "m3u8")
        isLive = args.getBoolean(PlaybackHelper.ARG_IS_LIVE, true)
        channelId = args.getString(PlaybackHelper.ARG_CHANNEL_ID)

        binding.playerTitle.text = title
        binding.liveBadge.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.goLiveButton.visibility = View.GONE

        binding.backButton.setOnClickListener { exitPlayer() }
        binding.fullscreenButton.setOnClickListener { exitPlayer() }
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.volumeButton.setOnClickListener { toggleMute() }
        binding.settingsButton.setOnClickListener { showSettings() }
        binding.goLiveButton.setOnClickListener { requireContext().app().dualPlayer.seekToLive() }
        binding.retryButton.setOnClickListener { replay() }
        binding.otherFormatButton.setOnClickListener { switchFormat() }
        binding.switchEngineButton.setOnClickListener { switchEngine() }

        // Tap anywhere on the video toggles the controls; DPAD OK toggles play/pause.
        binding.videoContainer.setOnClickListener { toggleControls() }
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                togglePlayPause()
                true
            } else {
                showControls()
                false
            }
        }

        // Live: no seek bar for raw TS; "Go Live" only for HLS.
        if (isLive) {
            val isTs = containerPref == "ts" || ext == "ts"
            binding.seekBar.visibility = View.GONE
            binding.goLiveButton.visibility = if (isTs) View.GONE else View.VISIBLE
        } else {
            // VOD: the seek bar seeks (LivePlayer.seekTo). Seek bar max is
            // 1000 (per-mille of the duration; see the position collector).
            binding.seekBar.visibility = View.VISIBLE
            binding.seekBar.isEnabled = true
            binding.seekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        // Live preview of the target time while dragging.
                        if (fromUser) {
                            val dur = requireContext().app().dualPlayer.durationMs.value
                            if (dur > 0L) {
                                binding.currentTime.text =
                                    PlaybackHelper.formatTime(progress * dur / 1000L)
                            }
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        isSeeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        isSeeking = false
                        val dur = requireContext().app().dualPlayer.durationMs.value
                        if (dur > 0L) {
                            val target = (seekBar?.progress ?: 0) * dur / 1000L
                            requireContext().app().dualPlayer.seekTo(target)
                        }
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi()
        val app = requireContext().app()
        app.exoPlayer.setPlayerView(binding.playerView)
        app.vlcPlayer.setVideoLayout(binding.vlcLayout)
        observePlayer()
        playCurrent()
        scheduleHideControls()
    }

    override fun onPause() {
        super.onPause()
        hideHandler.removeCallbacks(hideRunnable)
        observeJob?.cancel()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val app = requireContext().app()
        app.dualPlayer.stop()
        app.exoPlayer.setPlayerView(null)
        app.vlcPlayer.setVideoLayout(null)
        _binding = null
    }

    // -- Playback ---------------------------------------------------------------

    private fun buildPlayItem(): PlayItem? {
        val app = requireContext().app()
        val creds: Credentials = app.sessionManager.getCredentials() ?: return null
        return when (kind) {
            "live" -> {
                val output = when (containerPref) {
                    "ts" -> "ts"
                    else -> "m3u8"
                }
                val channel = LiveChannel(
                    streamId = streamId, name = title, icon = null,
                    categoryId = "", epgChannelId = null,
                    isHbbtv = title.contains("HbbTV", ignoreCase = true),
                )
                PlaybackHelper.livePlayItem(creds, channel, output)
            }
            "episode" -> {
                val series = SeriesItem(
                    seriesId = 0, name = title, cover = null, categoryId = "",
                    rating = null, year = null, genre = null, plot = null,
                )
                val episode = Episode(
                    id = streamId, episodeNum = 0, title = title,
                    containerExtension = ext, season = 1, info = null,
                )
                PlaybackHelper.episodePlayItem(creds, series, episode)
            }
            else -> {
                val vod = VodItem(
                    streamId = streamId, name = title, icon = null, categoryId = "",
                    rating = null, year = null, genre = null, plot = null,
                    durationSecs = null, containerExtension = ext,
                )
                PlaybackHelper.vodPlayItem(creds, vod)
            }
        }
    }

    private fun playCurrent() {
        val app = requireContext().app()
        val item = buildPlayItem() ?: return
        currentItem = item
        val provider: ((Container) -> String)? =
            if (kind == "live") {
                val creds = app.sessionManager.getCredentials()
                if (creds != null) PlaybackHelper.liveUrlProvider(creds, streamId) else null
            } else {
                null
            }
        app.dualPlayer.play(item, provider)
    }

    private fun replay() {
        binding.errorView.visibility = View.GONE
        playCurrent()
    }

    /** "Autre format": toggles HLS <-> TS and replays (live only). */
    private fun switchFormat() {
        if (kind != "live") {
            replay()
            return
        }
        containerPref = if (containerPref == "ts") "hls" else "ts"
        binding.errorView.visibility = View.GONE
        val isTs = containerPref == "ts"
        binding.seekBar.visibility = View.GONE
        binding.goLiveButton.visibility = if (isTs) View.GONE else View.VISIBLE
        playCurrent()
    }

    /** "Changer de lecteur": switches to the other engine. */
    private fun switchEngine() {
        val player = requireContext().app().dualPlayer
        val other = if (player.currentEngine.value == Engine.EXO) Engine.VLC else Engine.EXO
        binding.errorView.visibility = View.GONE
        player.requestEngineSwitch(other)
    }

    private fun togglePlayPause() {
        val player = requireContext().app().dualPlayer
        when (player.state.value) {
            is PlaybackState.Playing -> player.pause()
            is PlaybackState.Paused -> player.resume()
            is PlaybackState.Error -> replay()
            else -> Unit
        }
        showControls()
    }

    private fun toggleMute() {
        val player = requireContext().app().dualPlayer
        muted = !muted
        player.setVolume(if (muted) 0f else 1f)
        binding.volumeButton.alpha = if (muted) 0.5f else 1f
        showControls()
    }

    private fun exitPlayer() {
        (requireActivity() as MainActivity).let { activity ->
            activity.player.stop()
            activity.popTop()
        }
    }

    // -- Controls auto-hide -------------------------------------------------------

    private fun showControls() {
        controlsVisible = true
        binding.topBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        if (binding.errorView.visibility == View.VISIBLE) return
        controlsVisible = false
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000L)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun showSystemUi() {
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    // -- Settings dialog ----------------------------------------------------------

    private fun showSettings() {
        val app = requireContext().app()
        val session = app.sessionManager
        val dialogBinding = DialogPlayerSettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        when (session.engineSetting) {
            SessionManager.ENGINE_EXO -> dialogBinding.engineExo.isChecked = true
            SessionManager.ENGINE_VLC -> dialogBinding.engineVlc.isChecked = true
            else -> dialogBinding.engineAuto.isChecked = true
        }
        when (session.qualityLock) {
            SessionManager.QUALITY_1080 -> dialogBinding.quality1080.isChecked = true
            SessionManager.QUALITY_720 -> dialogBinding.quality720.isChecked = true
            SessionManager.QUALITY_480 -> dialogBinding.quality480.isChecked = true
            else -> dialogBinding.qualityAuto.isChecked = true
        }
        when (containerPref) {
            "hls" -> dialogBinding.containerHls.isChecked = true
            "ts" -> dialogBinding.containerTs.isChecked = true
            else -> dialogBinding.containerAuto.isChecked = true
        }

        dialogBinding.settingsOkButton.setOnClickListener {
            val engineSetting = when (dialogBinding.engineGroup.checkedRadioButtonId) {
                R.id.engineExo -> SessionManager.ENGINE_EXO
                R.id.engineVlc -> SessionManager.ENGINE_VLC
                else -> SessionManager.ENGINE_AUTO
            }
            val qualityLock = when (dialogBinding.qualityGroup.checkedRadioButtonId) {
                R.id.quality1080 -> SessionManager.QUALITY_1080
                R.id.quality720 -> SessionManager.QUALITY_720
                R.id.quality480 -> SessionManager.QUALITY_480
                else -> SessionManager.QUALITY_AUTO
            }
            val newContainer = when (dialogBinding.containerGroup.checkedRadioButtonId) {
                R.id.containerHls -> "hls"
                R.id.containerTs -> "ts"
                else -> "auto"
            }
            session.engineSetting = engineSetting
            session.qualityLock = qualityLock
            val player = app.dualPlayer
            player.engineMode = when (engineSetting) {
                SessionManager.ENGINE_EXO -> EngineMode.EXO
                SessionManager.ENGINE_VLC -> EngineMode.VLC
                else -> EngineMode.AUTO
            }
            // Quality lock applies immediately: Exo rebuilds its track caps
            // live; VLC stores it for the next play.
            player.setQualityLock(
                when (qualityLock) {
                    SessionManager.QUALITY_1080 -> 1080
                    SessionManager.QUALITY_720 -> 720
                    SessionManager.QUALITY_480 -> 480
                    else -> 0
                },
            )
            // Explicit engine choice switches immediately when playing.
            if (engineSetting == SessionManager.ENGINE_EXO) {
                player.requestEngineSwitch(Engine.EXO)
            } else if (engineSetting == SessionManager.ENGINE_VLC) {
                player.requestEngineSwitch(Engine.VLC)
            }
            if (newContainer != containerPref && kind == "live") {
                containerPref = newContainer
                val isTs = containerPref == "ts"
                binding.seekBar.visibility = View.GONE
                binding.goLiveButton.visibility = if (isTs) View.GONE else View.VISIBLE
                playCurrent()
            } else {
                containerPref = newContainer
            }
            dialog.dismiss()
            showControls()
        }
        dialog.show()
    }

    // -- Player observation ---------------------------------------------------------

    private fun observePlayer() {
        val app = requireContext().app()
        val player = app.dualPlayer
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                player.currentEngine.collectLatest { engine ->
                    if (!isAdded) return@collectLatest
                    binding.playerView.visibility =
                        if (engine == Engine.EXO) View.VISIBLE else View.GONE
                    binding.vlcLayout.visibility =
                        if (engine == Engine.VLC) View.VISIBLE else View.GONE
                }
            }
            launch {
                player.state.collectLatest { state ->
                    if (!isAdded) return@collectLatest
                    binding.playPauseButton.setImageResource(
                        if (state is PlaybackState.Playing) R.drawable.ic_pause else R.drawable.ic_play,
                    )
                    when (state) {
                        is PlaybackState.Error -> {
                            binding.errorMessage.text = state.error.message.ifBlank {
                                "Erreur de lecture"
                            }
                            binding.errorView.visibility = View.VISIBLE
                        }
                        else -> binding.errorView.visibility = View.GONE
                    }
                }
            }
            launch {
                player.isSwitchingEngine.collectLatest { switching ->
                    if (!isAdded) return@collectLatest
                    binding.switchingOverlay.visibility =
                        if (switching) View.VISIBLE else View.GONE
                }
            }
            launch {
                player.positionMs.collectLatest { pos ->
                    if (!isAdded) return@collectLatest
                    binding.currentTime.text = PlaybackHelper.formatTime(pos)
                    val dur = player.durationMs.value
                    // While the user drags the seek bar, don't fight them.
                    if (!isLive && !isSeeking && dur > 0L) {
                        binding.seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }
                }
            }
            launch {
                player.durationMs.collectLatest { dur ->
                    if (!isAdded) return@collectLatest
                    binding.totalTime.text =
                        if (dur > 0L) PlaybackHelper.formatTime(dur) else "—"
                }
            }
        }
    }
}
