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
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.FragmentMoviesBinding
import com.mhplayer.app.ui.adapters.ChipAdapter
import com.mhplayer.app.ui.adapters.PosterAdapter
import kotlinx.coroutines.launch

/**
 * Movies: title "Films", genre chips (Tous red, Action, Comédie, Drame,
 * Science-fiction, Thriller), 12 posters in a 2x6 grid.
 * Sidebar shows "Films" exactly ONCE.
 */
class MoviesFragment : Fragment() {

    private var _binding: FragmentMoviesBinding? = null
    private val binding get() = _binding!!

    private val chips = listOf("Tous", "Action", "Comédie", "Drame", "Science-fiction", "Thriller")
    private var allVod: List<VodItem> = emptyList()
    private val posterAdapter = PosterAdapter(style = PosterAdapter.Style.BELOW) { poster ->
        allVod.firstOrNull { it.streamId == poster.id }?.let {
            (requireActivity() as MainActivity).openDetails(PlaybackHelper.vodDetailsArgs(it))
        }
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
            allVod = requireContext().app().repository.getAllVod()
            if (!isAdded) return@launch
            applyFilter("Tous")
            FocusHelper.requestFocus(binding.gridList)
        }
    }

    private fun applyFilter(chip: String) {
        val filtered = if (chip == "Tous") {
            allVod
        } else {
            allVod.filter { it.genre?.contains(chip, ignoreCase = true) == true }
        }
        posterAdapter.submitList(
            filtered.map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon) },
        )
        binding.emptyText.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
