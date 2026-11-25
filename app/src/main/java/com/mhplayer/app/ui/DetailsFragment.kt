package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.Season
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.FragmentDetailsBinding
import com.mhplayer.app.ui.adapters.EpisodeAdapter
import kotlinx.coroutines.launch

/**
 * Details: sidebar (Series active for series), large poster, title, meta
 * line ("2024 • Drama • 2 seasons"), description, gold "Play" + grey
 * "More info", "Season 1" row with episode cards (Episode 1 selected
 * gold). Movies: same layout without episodes. HbbTV-flagged channels show
 * "Interactive HbbTV is not available on this device". A gold heart adds
 * the item to Favorites.
 */
class DetailsFragment : Fragment() {

    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!

    private var kind: String = "vod"
    private var itemId: Int = 0
    private var title: String = ""
    private var cover: String? = null
    private var year: Int = 0
    private var genre: String = ""
    private var plot: String = ""
    private var seasonCount: Int = 0
    private var isHbbtv: Boolean = false

    private var seasons: List<Season> = emptyList()
    private var firstEpisode: Episode? = null
    private var plotExpanded = false
    private val episodeAdapter = EpisodeAdapter(onClick = { episode -> playEpisode(episode) })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        val args = requireArguments()
        kind = args.getString(PlaybackHelper.ARG_DETAILS_KIND, "vod")
        itemId = args.getInt(PlaybackHelper.ARG_ID, 0)
        title = args.getString(PlaybackHelper.ARG_TITLE, "")
        cover = args.getString(PlaybackHelper.ARG_COVER)
        year = args.getInt(PlaybackHelper.ARG_YEAR, 0)
        genre = args.getString(PlaybackHelper.ARG_GENRE, "")
        plot = args.getString(PlaybackHelper.ARG_PLOT, "")
        seasonCount = args.getInt(PlaybackHelper.ARG_SEASONS, 0)
        isHbbtv = args.getBoolean(PlaybackHelper.ARG_IS_HBBTV, false) ||
            title.contains("HbbTV", ignoreCase = true)

        binding.sidebar.setSelected(
            when (kind) {
                "series" -> SidebarView.Item.SERIES
                "live" -> SidebarView.Item.CHANNELS
                else -> SidebarView.Item.MOVIES
            },
        )
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
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

        binding.detailsTitle.text = title
        binding.detailsPlot.text = plot.ifBlank { "No description available." }
        binding.detailsPlot.maxLines = 2
        binding.detailsPoster.load(cover) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
        binding.hbbtvNote.visibility = if (isHbbtv) View.VISIBLE else View.GONE

        binding.episodeList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.episodeList.adapter = episodeAdapter

        binding.playButton.setOnClickListener { onPlay() }
        binding.infoButton.setOnClickListener {
            plotExpanded = !plotExpanded
            binding.detailsPlot.maxLines = if (plotExpanded) Int.MAX_VALUE else 2
        }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        refreshFavoriteIcon()

        when (kind) {
            "series" -> {
                binding.detailsMeta.text = buildString {
                    if (year > 0) append(year)
                    if (genre.isNotBlank()) append(if (isNotEmpty()) " • " else "").append(genre)
                    if (seasonCount > 0) append(if (isNotEmpty()) " • " else "").append("$seasonCount seasons")
                }.ifBlank { "—" }
                loadSeasons()
            }
            "live" -> {
                binding.detailsMeta.text = "LIVE"
                binding.seasonTitle.visibility = View.GONE
                binding.episodeList.visibility = View.GONE
            }
            else -> {
                binding.detailsMeta.text = buildString {
                    if (year > 0) append(year)
                    if (genre.isNotBlank()) append(if (isNotEmpty()) " • " else "").append(genre)
                }.ifBlank { "—" }
                binding.seasonTitle.visibility = View.GONE
                binding.episodeList.visibility = View.GONE
            }
        }
        FocusHelper.requestFocus(binding.playButton)
    }

    private fun loadSeasons() {
        viewLifecycleOwner.lifecycleScope.launch {
            seasons = requireContext().app().repository.getSeriesInfo(itemId)
            if (!isAdded) return@launch
            val firstSeason = seasons.firstOrNull()
            if (firstSeason == null || firstSeason.episodes.isEmpty()) {
                binding.seasonTitle.visibility = View.GONE
                binding.episodeList.visibility = View.GONE
                return@launch
            }
            seasonCount = seasons.size
            binding.detailsMeta.text = buildString {
                if (year > 0) append(year)
                if (genre.isNotBlank()) append(if (isNotEmpty()) " • " else "").append(genre)
                append(if (isNotEmpty()) " • " else "").append("$seasonCount seasons")
            }
            binding.seasonTitle.text = "Season ${firstSeason.seasonNumber}"
            val adapter = EpisodeAdapter(firstSeason.episodes, coverUrl = cover) { episode ->
                playEpisode(episode)
            }
            binding.episodeList.adapter = adapter
            firstEpisode = firstSeason.episodes.firstOrNull()
            firstEpisode?.let { adapter.selectedEpisodeId = it.id }
        }
    }

    /** Gold heart when this item is a favorite, muted otherwise. */
    private fun refreshFavoriteIcon() {
        val type = when (kind) {
            "series" -> "series"
            "live" -> "live"
            else -> "vod"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val isFav = requireContext().app().repository.isFavorite(type, itemId)
            if (!isAdded) return@launch
            binding.favoriteButton.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isFav) R.color.zeno_gold else R.color.zeno_text_muted,
                ),
            )
        }
    }

    private fun toggleFavorite() {
        val type = when (kind) {
            "series" -> "series"
            "live" -> "live"
            else -> "vod"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val added = requireContext().app().repository.toggleFavorite(type, itemId)
            if (!isAdded) return@launch
            refreshFavoriteIcon()
            android.widget.Toast.makeText(
                requireContext(),
                if (added) R.string.vod_added_favorite else R.string.vod_removed_favorite,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun onPlay() {
        val activity = requireActivity() as MainActivity
        val app = requireContext().app()
        val creds = app.sessionManager.getCredentials()
        when (kind) {
            "live" -> {
                if (creds == null) return
                val channel = LiveChannel(
                    streamId = itemId, name = title, icon = cover,
                    categoryId = "", epgChannelId = null, isHbbtv = isHbbtv,
                )
                activity.openPlayer(PlaybackHelper.liveArgs(channel))
            }
            "series" -> {
                firstEpisode?.let { playEpisode(it) }
                    ?: run { binding.emptyText.visibility = View.VISIBLE }
            }
            else -> {
                if (creds == null) return
                val vod = VodItem(
                    streamId = itemId, name = title, icon = cover, categoryId = "",
                    rating = null, year = year.takeIf { it > 0 }, genre = genre.ifBlank { null },
                    plot = plot.ifBlank { null }, durationSecs = null,
                    containerExtension = requireArguments().getString(PlaybackHelper.ARG_EXT, "mp4"),
                )
                activity.openPlayer(PlaybackHelper.vodArgs(vod))
            }
        }
    }

    private fun playEpisode(episode: Episode) {
        val activity = requireActivity() as MainActivity
        val series = SeriesItem(
            seriesId = itemId, name = title, cover = cover, categoryId = "",
            rating = null, year = year.takeIf { it > 0 }, genre = genre.ifBlank { null },
            plot = plot.ifBlank { null },
        )
        activity.openPlayer(PlaybackHelper.episodeArgs(series, episode))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
