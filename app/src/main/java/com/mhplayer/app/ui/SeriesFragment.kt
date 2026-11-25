package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.databinding.FragmentSeriesBinding
import com.mhplayer.app.ui.adapters.ChipAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Zeno Play Series (approved reference): fixed sidebar (Series active),
 * banner with the top series + rating/year/genres + description +
 * "Watch Now" / "Add to List" buttons, genre chips (All Series / Drama /
 * Sci-Fi / Action / Thriller / Comedy / Fantasy / Mystery),
 * "Trending Series" and "Drama & Fantasy" rows with ratings on the
 * posters. Posters are cards: image on top + dark label bar below.
 */
class SeriesFragment : Fragment() {

    private var _binding: FragmentSeriesBinding? = null
    private val binding get() = _binding!!

    private val chips = listOf("All Series", "Drama", "Sci-Fi", "Action", "Thriller", "Comedy", "Fantasy", "Mystery")
    private var allSeries: List<SeriesItem> = emptyList()
    private var bannerSeries: SeriesItem? = null

    private val row1Adapter = PosterAdapter(style = PosterAdapter.Style.BAR) { poster ->
        openSeries(poster.id)
    }
    private val row2Adapter = PosterAdapter(style = PosterAdapter.Style.BAR) { poster ->
        openSeries(poster.id)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSeriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.SERIES)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> Unit
                SidebarView.Item.FAVORITES -> activity.navigateTo(MainActivity.Destination.FAVORITES)
                SidebarView.Item.SETTINGS -> activity.navigateTo(MainActivity.Destination.SETTINGS)
                SidebarView.Item.ACCOUNT -> activity.navigateTo(MainActivity.Destination.ACCOUNT)
            }
        }
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (portrait) binding.sidebar.setHorizontal(true)

        binding.chipList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.chipList.adapter = ChipAdapter(chips) { chip -> applyFilter(chip) }

        binding.row1List.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.row1List.adapter = row1Adapter
        binding.row2List.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.row2List.adapter = row2Adapter

        binding.watchNowButton.setOnClickListener {
            // Series: Watch Now opens the details — the first episode
            // plays from there (episodes load on demand).
            bannerSeries?.let {
                requireContext().app().sessionManager.addRecentItem("series", it.seriesId)
                activity.openDetails(PlaybackHelper.seriesDetailsArgs(it))
            }
        }
        binding.addToListButton.setOnClickListener {
            bannerSeries?.let { toggleFavorite(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            allSeries = requireContext().app().repository.getAllSeries()
            if (!isAdded) return@launch
            applyFilter(chips.first())
            FocusHelper.requestFocus(binding.row1List)
        }
    }

    private fun openSeries(seriesId: Int) {
        allSeries.firstOrNull { it.seriesId == seriesId }?.let {
            requireContext().app().sessionManager.addRecentItem("series", it.seriesId)
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.seriesDetailsArgs(it))
        }
    }

    private fun toggleFavorite(series: SeriesItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = requireContext().app().repository.toggleFavorite("series", series.seriesId)
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                if (added) R.string.vod_added_favorite else R.string.vod_removed_favorite,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** "Sci-Fi" also matches the panel genre "Science Fiction". */
    private fun genreMatches(genre: String?, chip: String): Boolean {
        if (genre.isNullOrBlank()) return false
        val g = genre.lowercase()
        return when (chip) {
            "Sci-Fi" -> g.contains("sci-fi") || g.contains("science fiction") || g.contains("sci fi")
            else -> g.contains(chip.lowercase())
        }
    }

    private fun applyFilter(chip: String) {
        val filtered = if (chip == "All Series") {
            allSeries
        } else {
            allSeries.filter { genreMatches(it.genre, chip) }
        }
        // Banner: the highest-rated series of the filtered list.
        bannerSeries = filtered.maxByOrNull { it.rating ?: 0.0 }
        bannerSeries?.let { series ->
            binding.bannerTitle.text = series.name
            binding.bannerMeta.text = buildString {
                series.rating?.let { if (it > 0.0) append("★ %.1f".format(it)) }
                series.year?.let { if (it > 0) append(if (isNotEmpty()) " • " else "").append(it) }
                if (!series.genre.isNullOrBlank()) {
                    append(if (isNotEmpty()) " • " else "").append(series.genre)
                }
            }
            binding.bannerDescription.text = series.plot.orEmpty()
            binding.bannerImage.load(series.cover) {
                placeholder(R.drawable.poster_placeholder)
                error(R.drawable.poster_placeholder)
            }
        }
        // Row 1: Trending (highest rated first).
        val trending = filtered.sortedByDescending { it.rating ?: 0.0 }.take(10)
        row1Adapter.submitList(
            trending.map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover, it.rating) },
        )
        // Row 2: Drama & Fantasy.
        val drama = filtered.filter {
            it.genre?.contains("drama", ignoreCase = true) == true ||
                it.genre?.contains("fantasy", ignoreCase = true) == true
        }.take(10)
        row2Adapter.submitList(
            drama.map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover, it.rating) },
        )
        binding.row2Title.visibility = if (drama.isEmpty()) View.GONE else View.VISIBLE
        binding.row2List.visibility = if (drama.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
