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
import com.mhplayer.app.databinding.FragmentPlaylistBinding
import com.mhplayer.app.ui.adapters.ChannelTileAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import com.mhplayer.app.ui.adapters.ThumbAdapter
import kotlinx.coroutines.launch

/**
 * My Playlist: info card (playlist name, "Serveur : … • Premium", counts
 * X chaînes / Y films / Z séries, "Dernière mise à jour : aujourd'hui"),
 * 3 rows: Chaînes tiles, Films posters, Séries posters.
 * All counts and names come from the repository (Room) — never faked.
 */
class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private var vodList: List<VodItem> = emptyList()
    private var seriesList: List<SeriesItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.PLAYLIST)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.PLAYLIST -> Unit
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        binding.channelTiles.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.filmThumbs.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.seriesThumbs.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            val channels: List<LiveChannel> = app.repository.getAllLiveChannels()
            vodList = app.repository.getAllVod()
            seriesList = app.repository.getAllSeries()
            if (!isAdded) return@launch

            val playlistName = app.sessionManager.getPlaylistName().ifBlank { "Ma playlist" }
            binding.playlistName.text = playlistName
            val server = app.sessionManager.getCredentials()?.serverUrl
                ?: app.sessionManager.getM3uUrl()
                ?: "—"
            binding.serverInfo.text = "Serveur : $server • Premium"
            binding.statChannelsValue.text = channels.size.toString()
            binding.statMoviesValue.text = vodList.size.toString()
            binding.statSeriesValue.text = seriesList.size.toString()

            binding.channelTiles.adapter = ChannelTileAdapter(channels.take(6)) {
                // Tile -> open the Channels screen to play it there.
                activity.navigateTo(MainActivity.Destination.CHANNELS)
            }
            binding.filmThumbs.adapter = ThumbAdapter(
                vodList.take(6).map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon) },
            ) { poster ->
                vodList.firstOrNull { it.streamId == poster.id }?.let {
                    activity.openDetails(PlaybackHelper.vodDetailsArgs(it))
                }
            }
            binding.seriesThumbs.adapter = ThumbAdapter(
                seriesList.take(6).map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover) },
            ) { poster ->
                seriesList.firstOrNull { it.seriesId == poster.id }?.let {
                    activity.openDetails(PlaybackHelper.seriesDetailsArgs(it))
                }
            }
            val empty = channels.isEmpty() && vodList.isEmpty() && seriesList.isEmpty()
            binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
