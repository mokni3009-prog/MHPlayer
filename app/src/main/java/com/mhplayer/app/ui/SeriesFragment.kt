package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhplayer.app.MainActivity
import com.mhplayer.app.app
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.databinding.FragmentSeriesBinding
import com.mhplayer.app.ui.adapters.ChipAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Series: title "Séries", genre chips (Toutes red, Drame, Policier,
 * Comédie, Science-fiction, Aventure), 12 posters in a 2x6 grid.
 * Posters are cards: image on top + dark label bar below (per mockup).
 */
class SeriesFragment : Fragment() {

    private var _binding: FragmentSeriesBinding? = null
    private val binding get() = _binding!!

    private val chips = listOf("Toutes", "Drame", "Policier", "Comédie", "Science-fiction", "Aventure")
    private var allSeries: List<SeriesItem> = emptyList()
    private val posterAdapter = PosterAdapter(style = PosterAdapter.Style.BAR) { poster ->
        allSeries.firstOrNull { it.seriesId == poster.id }?.let {
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.seriesDetailsArgs(it))
        }
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
                SidebarView.Item.PLAYLIST -> activity.navigateTo(MainActivity.Destination.PLAYLIST)
            }
        }
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (portrait) binding.sidebar.setHorizontal(true)

        binding.chipList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.chipList.adapter = ChipAdapter(chips) { chip -> applyFilter(chip) }

        binding.gridList.layoutManager =
            GridLayoutManager(requireContext(), if (portrait) 3 else 6)
        binding.gridList.adapter = posterAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            allSeries = requireContext().app().repository.getAllSeries()
            if (!isAdded) return@launch
            applyFilter("Toutes")
            FocusHelper.requestFocus(binding.gridList)
        }
    }

    private fun applyFilter(chip: String) {
        val filtered = if (chip == "Toutes") {
            allSeries
        } else {
            allSeries.filter { it.genre?.contains(chip, ignoreCase = true) == true }
        }
        posterAdapter.submitList(
            filtered.map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover) },
        )
        binding.emptyText.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
