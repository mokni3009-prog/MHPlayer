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
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.FragmentMoviesBinding
import com.mhplayer.app.ui.adapters.ChipAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Zeno Play Movies (approved reference): fixed sidebar (Movies active),
 * banner with the top movie + rating/duration/year/genres + description +
 * "Watch Now" / "Add to List" buttons, genre chips (All Movies / Action /
 * Sci-Fi / Comedy / Drama / Thriller / Horror), "Trending Movies" and
 * "Action & Adventure" rows with ratings on the posters.
 */
class MoviesFragment : Fragment() {

    private var _binding: FragmentMoviesBinding? = null
    private val binding get() = _binding!!

    private val chips = listOf("All Movies", "Action", "Sci-Fi", "Comedy", "Drama", "Thriller", "Horror")
    private var allVod: List<VodItem> = emptyList()
    private var bannerVod: VodItem? = null

    private val row1Adapter = PosterAdapter(style = PosterAdapter.Style.BELOW) { poster ->
        openVod(poster.id)
    }
    private val row2Adapter = PosterAdapter(style = PosterAdapter.Style.BELOW) { poster ->
        openVod(poster.id)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMoviesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.MOVIES)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> Unit
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
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
            bannerVod?.let {
                requireContext().app().sessionManager.addRecentItem("vod", it.streamId)
                activity.openPlayer(PlaybackHelper.vodArgs(it))
            }
        }
        binding.addToListButton.setOnClickListener {
            bannerVod?.let { toggleFavorite(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            allVod = requireContext().app().repository.getAllVod()
            if (!isAdded) return@launch
            applyFilter(chips.first())
            FocusHelper.requestFocus(binding.row1List)
        }
    }

    private fun openVod(streamId: Int) {
        allVod.firstOrNull { it.streamId == streamId }?.let {
            requireContext().app().sessionManager.addRecentItem("vod", it.streamId)
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.vodDetailsArgs(it))
        }
    }

    private fun toggleFavorite(vod: VodItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = requireContext().app().repository.toggleFavorite("vod", vod.streamId)
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
        val filtered = if (chip == "All Movies") {
            allVod
        } else {
            allVod.filter { genreMatches(it.genre, chip) }
        }
        // Banner: the highest-rated movie of the filtered list.
        bannerVod = filtered.maxByOrNull { it.rating ?: 0.0 }
        bannerVod?.let { vod ->
            binding.bannerTitle.text = vod.name
            binding.bannerMeta.text = buildString {
                vod.rating?.let { if (it > 0.0) append("★ %.1f".format(it)) }
                vod.year?.let { if (it > 0) append(if (isNotEmpty()) " • " else "").append(it) }
                if (!vod.genre.isNullOrBlank()) {
                    append(if (isNotEmpty()) " • " else "").append(vod.genre)
                }
            }
            binding.bannerDescription.text = vod.plot.orEmpty()
            binding.bannerImage.load(vod.icon) {
                placeholder(R.drawable.poster_placeholder)
                error(R.drawable.poster_placeholder)
            }
        }
        // Row 1: Trending (highest rated first).
        val trending = filtered.sortedByDescending { it.rating ?: 0.0 }.take(10)
        row1Adapter.submitList(
            trending.map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon, it.rating) },
        )
        // Row 2: Action & Adventure.
        val action = filtered.filter {
            it.genre?.contains("action", ignoreCase = true) == true ||
                it.genre?.contains("adventure", ignoreCase = true) == true
        }.take(10)
        row2Adapter.submitList(
            action.map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon, it.rating) },
        )
        binding.row2Title.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
        binding.row2List.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
