package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.db.ChannelPlaybackEntity
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.databinding.FragmentChannelsBinding
import com.mhplayer.app.player.Container
import com.mhplayer.app.player.Engine
import com.mhplayer.app.player.PlaybackState
import com.mhplayer.app.ui.adapters.BouquetAdapter
import com.mhplayer.app.ui.adapters.ChannelAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Channels: sidebar (Chaînes active), Bouquets column (selected = full red
 * bg), Chaînes column (selected channel = FULL red background), embedded
 * player window (channel name + red EN DIRECT badge).
 *
 * DPAD: left/right moves between columns, up/down in lists, OK selects /
 * plays a channel, OK on the player window -> fullscreen PlayerFragment.
 * Long-press a channel -> "Play with VLC" / "Play with ExoPlayer" override
 * (persisted via ChannelPlaybackEntity).
 */
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val bouquetAdapter = BouquetAdapter(onClick = { selectBouquet(it) })
    private val channelAdapter = ChannelAdapter(
        onClick = { playChannel(it) },
        onLongClick = { channel -> showEngineOverride(channel) },
    )

    private var categories: List<Category> = emptyList()
    private var currentChannels: List<LiveChannel> = emptyList()
    private var currentChannel: LiveChannel? = null
    private var muted = false
    private var observeJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.CHANNELS)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> Unit
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.PLAYLIST -> activity.navigateTo(MainActivity.Destination.PLAYLIST)
            }
        }
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (portrait) binding.sidebar.setHorizontal(true)

        binding.bouquetList.layoutManager = LinearLayoutManager(
            requireContext(),
            if (portrait) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false,
        )
        binding.bouquetList.adapter = bouquetAdapter
        binding.channelList.layoutManager = LinearLayoutManager(requireContext())
        binding.channelList.adapter = channelAdapter

        binding.videoContainer.setOnClickListener { openFullscreen() }
        binding.miniFullscreenButton.setOnClickListener { openFullscreen() }
        binding.miniPlayButton.setOnClickListener { togglePlayPause() }
        binding.miniVolumeButton.setOnClickListener { toggleMute() }

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            categories = app.repository.getLiveCategories()
            if (!isAdded) return@launch
            bouquetAdapter.submitList(categories)
            binding.emptyText.visibility =
                if (categories.isEmpty()) View.VISIBLE else View.GONE
            categories.firstOrNull()?.let { selectBouquet(it) }
            FocusHelper.requestFocus(binding.bouquetList)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = requireContext().app()
        app.exoPlayer.setPlayerView(binding.playerView)
        app.vlcPlayer.setVideoLayout(binding.vlcLayout)
        observePlayer()
        // Resume the current channel on the (possibly recreated) surfaces.
        currentChannel?.let { playChannel(it, keepSelection = true) }
    }

    override fun onPause() {
        super.onPause()
        observeJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val app = requireContext().app()
        app.dualPlayer.stop()
        app.exoPlayer.setPlayerView(null)
        app.vlcPlayer.setVideoLayout(null)
        _binding = null
    }

    private fun selectBouquet(category: Category) {
        bouquetAdapter.selectedCategoryId = category.id
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            currentChannels = app.repository.getLiveStreams(category.id)
            if (!isAdded) return@launch
            channelAdapter.submitList(currentChannels)
            binding.emptyText.visibility =
                if (currentChannels.isEmpty()) View.VISIBLE else View.GONE
            currentChannels.firstOrNull()?.let { playChannel(it) }
        }
    }

    private fun playChannel(channel: LiveChannel, keepSelection: Boolean = false) {
        playChannelWithHint(channel, engineHint = null)
    }

    private fun playChannelWithHint(channel: LiveChannel, engineHint: Engine?) {
        val app = requireContext().app()
        val creds = app.sessionManager.getCredentials() ?: return
        currentChannel = channel
        channelAdapter.selectedStreamId = channel.streamId
        binding.channelTitle.text = channel.name
        binding.channelSubtitle.text = channel.name
        binding.liveBadge.visibility = View.VISIBLE
        val item = PlaybackHelper.livePlayItem(creds, channel)
        app.dualPlayer.play(
            item.copy(engineHint = engineHint),
            PlaybackHelper.liveUrlProvider(creds, channel.streamId),
        )
    }

    private fun openFullscreen() {
        val channel = currentChannel ?: return
        (requireActivity() as MainActivity).openPlayer(PlaybackHelper.liveArgs(channel))
    }

    private fun togglePlayPause() {
        val player = requireContext().app().dualPlayer
        when (player.state.value) {
            is PlaybackState.Playing -> player.pause()
            is PlaybackState.Paused -> player.resume()
            else -> currentChannel?.let { playChannel(it) }
        }
    }

    private fun toggleMute() {
        val player = requireContext().app().dualPlayer
        muted = !muted
        player.setVolume(if (muted) 0f else 1f)
        binding.miniVolumeButton.alpha = if (muted) 0.5f else 1f
    }

    /** Long-press menu: per-channel engine override, persisted in Room. */
    private fun showEngineOverride(channel: LiveChannel): Boolean {
        val popup = PopupMenu(requireContext(), binding.channelList)
        popup.menu.add("Play with VLC")
        popup.menu.add("Play with ExoPlayer")
        popup.setOnMenuItemClickListener { menuItem ->
            val engine = if (menuItem.title == "Play with VLC") Engine.VLC else Engine.EXO
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                requireContext().app().database.channelPlaybackDao().upsert(
                    ChannelPlaybackEntity(
                        channelId = channel.streamId.toString(),
                        preferredEngine = engine.name,
                        preferredContainer = Container.M3U8.name,
                        hwDecoder = "HW",
                        lastError = null,
                        lastSuccessAt = 0L,
                    ),
                )
                withContext(Dispatchers.Main) {
                    if (isAdded) playChannelWithHint(channel, engineHint = engine)
                }
            }
            true
        }
        popup.show()
        return true
    }

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
                    binding.videoLoading.visibility =
                        if (state is PlaybackState.Opening || state is PlaybackState.Buffering) View.VISIBLE else View.GONE
                    binding.miniPlayButton.setImageResource(
                        if (state is PlaybackState.Playing) R.drawable.ic_pause else R.drawable.ic_play,
                    )
                }
            }
            launch {
                player.positionMs.collectLatest { pos ->
                    if (!isAdded) return@collectLatest
                    binding.miniCurrentTime.text = PlaybackHelper.formatTime(pos)
                }
            }
            launch {
                player.durationMs.collectLatest { dur ->
                    if (!isAdded) return@collectLatest
                    binding.miniTotalTime.text =
                        if (dur > 0L) PlaybackHelper.formatTime(dur) else "—"
                }
            }
        }
    }
}
