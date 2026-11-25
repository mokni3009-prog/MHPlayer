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
import com.mhplayer.app.databinding.FragmentFavoritesBinding
import com.mhplayer.app.ui.adapters.FavoriteChannelAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Zeno Play Favorites (approved reference): fixed sidebar (Favorites
 * active), live channels with LIVE badge and gold hearts, then a
 * Movies & Series poster row. Tap a channel to play it, tap its heart
 * to remove it; tap a poster to open its details.
 */
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private var favChannels: List<LiveChannel> = emptyList()
    private var favVod: List<VodItem> = emptyList()
    private var favSeries: List<SeriesItem> = emptyList()

    private val channelAdapter = FavoriteChannelAdapter(
        onClick = { channel ->
            (requireActivity() as MainActivity).openPlayer(PlaybackHelper.liveArgs(channel))
        },
        onRemove = { channel ->
            viewLifecycleOwner.lifecycleScope.launch {
                requireContext().app().repository.removeFavorite("live", channel.streamId)
                if (isAdded) loadFavorites()
            }
        },
    )

    private val vodAdapter = PosterAdapter(style = PosterAdapter.Style.BELOW) { poster ->
        favVod.firstOrNull { it.streamId == poster.id }?.let {
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.vodDetailsArgs(it))
            return@PosterAdapter
        }
        favSeries.firstOrNull { it.seriesId == poster.id }?.let {
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.seriesDetailsArgs(it))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.FAVORITES)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.FAVORITES -> Unit
                SidebarView.Item.SETTINGS -> activity.navigateTo(MainActivity.Destination.SETTINGS)
                SidebarView.Item.ACCOUNT -> activity.navigateTo(MainActivity.Destination.ACCOUNT)
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        binding.favChannelsList.layoutManager = LinearLayoutManager(requireContext())
        binding.favChannelsList.adapter = channelAdapter
        binding.favVodList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.favVodList.adapter = vodAdapter
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            favChannels = app.repository.getFavoriteChannels()
            favVod = app.repository.getFavoriteVod()
            favSeries = app.repository.getFavoriteSeries()
            if (!isAdded) return@launch
            channelAdapter.submitList(favChannels)
            val posters = mutableListOf<PosterAdapter.PosterItem>()
            posters += favVod.map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon, it.rating) }
            posters += favSeries.map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover, it.rating) }
            vodAdapter.submitList(posters)
            binding.emptyText.visibility =
                if (favChannels.isEmpty() && posters.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
