package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zeno Play Live TV (approved reference): fixed sidebar (Live TV active),
 * bouquets list and channels list inside dark panels, FLUSH top and
 * bottom, the selected row highlighted with the gold->cyan left bar.
 * Player window on the right with the channel name in the sidebar font
 * and the current program (EPG) underneath, plus a gold heart to add the
 * channel to Favorites.
 *
 * DPAD: left/right moves between columns, up/down in lists, OK selects /
 * plays a channel, OK on the player window -> fullscreen PlayerFragment.
 * Second tap on the same channel -> fullscreen (v1.5). Long-press a
 * channel -> "Play with VLC" / "Play with ExoPlayer" override (persisted
 * via ChannelPlaybackEntity). Long channel/bouquet names scroll like a
 * news ticker (marquee) in the list rows.
 */
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val bouquetAdapter = BouquetAdapter(onClick = { selectBouquet(it) })
    private val channelAdapter = ChannelAdapter(
        onClick = { onChannelClicked(it) },
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
                SidebarView.Item.FAVORITES -> activity.navigateTo(MainActivity.Destination.FAVORITES)
                SidebarView.Item.SETTINGS -> activity.navigateTo(MainActivity.Destination.SETTINGS)
                SidebarView.Item.ACCOUNT -> activity.navigateTo(MainActivity.Destination.ACCOUNT)
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
        binding.favoriteButton.setOnClickListener { toggleFavorite() }

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

    /**
     * v1.5 — second tap opens fullscreen: the first tap on a channel
     * selects it and plays it in the preview window; a second tap on the
     * SAME channel (same streamId) opens the true fullscreen player.
     * Only user taps go through here — programmatic plays ([selectBouquet]
     * auto-play, [onResume] re-play) call [playChannel] directly and never
     * open fullscreen by accident. The adapter item is a normal click
     * listener on a focusable row, so it works with touch AND with the
     * remote (DPAD OK key) on phone and box alike.
     */
    private fun onChannelClicked(channel: LiveChannel) {
        if (currentChannel?.streamId == channel.streamId) {
            openFullscreen()
        } else {
            playChannel(channel)
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
        binding.channelTitle.isSelected = true // marquee: long names scroll
        binding.liveBadge.visibility = View.VISIBLE
        app.sessionManager.addRecentItem("live", channel.streamId)
        refreshFavoriteIcon()
        loadEpg(channel)
        val item = PlaybackHelper.livePlayItem(creds, channel)
        app.dualPlayer.play(
            item.copy(engineHint = engineHint),
            PlaybackHelper.liveUrlProvider(creds, channel.streamId),
        )
    }

    /** Loads the short EPG (current + next program) under the player. */
    private fun loadEpg(channel: LiveChannel) {
        binding.epgNowTitle.text = getString(R.string.live_no_epg)
        binding.epgNowTime.text = ""
        binding.epgNextTitle.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            val programs = app.repository.getShortEpg(channel.streamId)
            if (!isAdded || currentChannel?.streamId != channel.streamId) return@launch
            val now = System.currentTimeMillis() / 1000L
            val current = programs.firstOrNull { it.startTimestamp <= now && now < it.stopTimestamp }
                ?: programs.firstOrNull { it.stopTimestamp > now }
            if (current == null) {
                binding.epgNowTitle.text = getString(R.string.live_no_epg)
                return@launch
            }
            binding.epgNowTitle.text = current.title
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.epgNowTime.text = buildString {
                if (current.startTimestamp > 0L) {
                    append(timeFormat.format(Date(current.startTimestamp * 1000L)))
                }
                if (current.stopTimestamp > 0L) {
                    append(" - ")
                    append(timeFormat.format(Date(current.stopTimestamp * 1000L)))
                }
            }
            val next = programs.firstOrNull { it.startTimestamp >= current.stopTimestamp }
            if (next != null && next.title.isNotBlank()) {
                binding.epgNextTitle.text = getString(R.string.live_next) + ": " + next.title
                binding.epgNextTitle.visibility = View.VISIBLE
            }
        }
    }

    /** Gold heart when the channel is a favorite, muted otherwise. */
    private fun refreshFavoriteIcon() {
        val channel = currentChannel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val isFav = requireContext().app().repository.isFavorite("live", channel.streamId)
            if (!isAdded || currentChannel?.streamId != channel.streamId) return@launch
            binding.favoriteButton.setColorFilter(
                ContextCompat.getColor(
                    requireContext(),
                    if (isFav) R.color.zeno_gold else R.color.zeno_text_muted,
                ),
            )
        }
    }

    private fun toggleFavorite() {
        val channel = currentChannel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val added = requireContext().app().repository.toggleFavorite("live", channel.streamId)
            if (!isAdded) return@launch
            refreshFavoriteIcon()
            Toast.makeText(
                requireContext(),
                if (added) R.string.vod_added_favorite else R.string.vod_removed_favorite,
                Toast.LENGTH_SHORT,
            ).show()
        }
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
