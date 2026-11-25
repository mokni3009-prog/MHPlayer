package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhplayer.app.MainActivity
import com.mhplayer.app.app
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.FragmentHomeBinding
import com.mhplayer.app.ui.adapters.FeaturedChannelAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Zeno Play Home (approved reference): fixed sidebar (Home active),
 * search icon + profile avatar top-right, "Featured Live Channels"
 * (horizontal cards with LIVE badge), "Continue Watching" (recently
 * played first, then newest movies) and "New Releases" (newest movies
 * by stream id). Poster tap -> Details; featured channel tap -> the
 * fullscreen player.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var liveList: List<LiveChannel> = emptyList()
    private var vodList: List<VodItem> = emptyList()
    private var seriesList: List<SeriesItem> = emptyList()

    private val featuredAdapter = FeaturedChannelAdapter { channel ->
        val app = requireContext().app()
        app.sessionManager.addRecentItem("live", channel.streamId)
        (requireActivity() as MainActivity).openPlayer(PlaybackHelper.liveArgs(channel))
    }

    private val continueAdapter = PosterAdapter(style = PosterAdapter.Style.NONE) { poster ->
        openPoster(poster)
    }

    private val newAdapter = PosterAdapter(style = PosterAdapter.Style.NONE) { poster ->
        openPoster(poster)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.HOME)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> Unit
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.FAVORITES -> activity.navigateTo(MainActivity.Destination.FAVORITES)
                SidebarView.Item.SETTINGS -> activity.navigateTo(MainActivity.Destination.SETTINGS)
                SidebarView.Item.ACCOUNT -> activity.navigateTo(MainActivity.Destination.ACCOUNT)
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        binding.searchButton.setOnClickListener {
            SearchDialog().show(parentFragmentManager, "search")
        }
        binding.profileAvatar.setOnClickListener {
            activity.navigateTo(MainActivity.Destination.ACCOUNT)
        }

        binding.featuredList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.featuredList.adapter = featuredAdapter
        binding.continueList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.continueList.adapter = continueAdapter
        binding.newList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.newList.adapter = newAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            liveList = app.repository.getAllLiveChannels()
            vodList = app.repository.getAllVod()
            seriesList = app.repository.getAllSeries()
            if (!isAdded) return@launch

            // Featured: the first live channels (up to 8).
            val featured = liveList.take(8)
            featuredAdapter.submitList(featured)
            binding.featuredEmpty.visibility =
                if (featured.isEmpty()) View.VISIBLE else View.GONE

            // Continue Watching: recently played first, then newest movies.
            val recent = app.sessionManager.getRecentItems()
            val continueItems = mutableListOf<PosterAdapter.PosterItem>()
            val seenIds = mutableSetOf<String>()
            for (entry in recent) {
                val (type, id) = entry.split(":").let {
                    if (it.size == 2) it[0] to (it[1].toIntOrNull() ?: -1) else "" to -1
                }
                if (id < 0) continue
                val item = when (type) {
                    "live" -> liveList.firstOrNull { it.streamId == id }
                        ?.let { PosterAdapter.PosterItem(it.streamId, it.name, it.icon) }
                    "vod" -> vodList.firstOrNull { it.streamId == id }
                        ?.let { PosterAdapter.PosterItem(it.streamId, it.name, it.icon, it.rating) }
                    "series" -> seriesList.firstOrNull { it.seriesId == id }
                        ?.let { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover, it.rating) }
                    else -> null
                }
                if (item != null && seenIds.add("$type:$id")) continueItems += item
            }
            val newestVod = vodList.sortedByDescending { it.streamId }
            for (vod in newestVod) {
                if (continueItems.size >= 10) break
                if (seenIds.add("vod:${vod.streamId}")) {
                    continueItems += PosterAdapter.PosterItem(vod.streamId, vod.name, vod.icon, vod.rating)
                }
            }
            continueAdapter.submitList(continueItems)
            binding.continueEmpty.visibility =
                if (continueItems.isEmpty()) View.VISIBLE else View.GONE

            // New Releases: newest movies by stream id (no added-date field
            // exists on the panel — the stream id grows with new content).
            val newItems = newestVod.take(10).map {
                PosterAdapter.PosterItem(it.streamId, it.name, it.icon, it.rating)
            }
            newAdapter.submitList(newItems)
            binding.newEmpty.visibility =
                if (newItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openPoster(poster: PosterAdapter.PosterItem) {
        val activity = requireActivity() as MainActivity
        val app = requireContext().app()
        vodList.firstOrNull { it.streamId == poster.id }?.let {
            app.sessionManager.addRecentItem("vod", it.streamId)
            activity.openDetails(PlaybackHelper.vodDetailsArgs(it))
            return
        }
        seriesList.firstOrNull { it.seriesId == poster.id }?.let {
            app.sessionManager.addRecentItem("series", it.seriesId)
            activity.openDetails(PlaybackHelper.seriesDetailsArgs(it))
            return
        }
        liveList.firstOrNull { it.streamId == poster.id }?.let {
            app.sessionManager.addRecentItem("live", it.streamId)
            activity.openPlayer(PlaybackHelper.liveArgs(it))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
