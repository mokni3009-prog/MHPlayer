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
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.databinding.FragmentHomeBinding
import com.mhplayer.app.ui.adapters.PosterAdapter
import com.mhplayer.app.ui.adapters.RowAdapter
import kotlinx.coroutines.launch

/**
 * Home: narrow sidebar (Accueil active), hero "Film à la une" + red
 * Lecture button, rows "Films populaires" and "Séries" (6 posters each).
 * Poster click -> DetailsFragment; Lecture -> PlayerFragment.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var heroVod: VodItem? = null
    private var heroSeries: SeriesItem? = null
    private var vodList: List<VodItem> = emptyList()
    private var seriesList: List<SeriesItem> = emptyList()

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
        binding.sidebar.setFloating(true)
        binding.sidebar.setSelected(SidebarView.Item.HOME)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> Unit
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.PLAYLIST -> activity.navigateTo(MainActivity.Destination.PLAYLIST)
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        binding.rowsList.layoutManager = LinearLayoutManager(requireContext())
        binding.rowsList.isNestedScrollingEnabled = false

        binding.heroPlayButton.setOnClickListener {
            heroVod?.let { activity.openPlayer(PlaybackHelper.vodArgs(it)) }
                ?: heroSeries?.let { activity.openDetails(PlaybackHelper.seriesDetailsArgs(it)) }
        }
        binding.heroInfoButton.setOnClickListener {
            heroVod?.let { activity.openDetails(PlaybackHelper.vodDetailsArgs(it)) }
                ?: heroSeries?.let { activity.openDetails(PlaybackHelper.seriesDetailsArgs(it)) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().app()
            vodList = app.repository.getAllVod()
            seriesList = app.repository.getAllSeries()
            if (!isAdded) return@launch

            heroVod = vodList.firstOrNull()
            heroSeries = seriesList.firstOrNull()
            if (heroVod != null) {
                binding.heroTitle.text = heroVod!!.name
                binding.heroSynopsis.text = heroVod!!.plot
                    ?: "Un thriller haletant dans une ville sous la pluie. Trahisons, secrets et enquête nocturne."
                binding.heroImage.load(heroVod!!.icon) {
                    placeholder(R.drawable.poster_placeholder)
                    error(R.drawable.poster_placeholder)
                }
            } else if (heroSeries != null) {
                binding.heroTitle.text = heroSeries!!.name
                binding.heroSynopsis.text = heroSeries!!.plot
                    ?: "Un thriller haletant dans une ville sous la pluie. Trahisons, secrets et enquête nocturne."
                binding.heroImage.load(heroSeries!!.cover) {
                    placeholder(R.drawable.poster_placeholder)
                    error(R.drawable.poster_placeholder)
                }
            }

            val sections = mutableListOf<RowAdapter.RowSection>()
            if (vodList.isNotEmpty()) {
                sections += RowAdapter.RowSection(
                    "Films populaires",
                    vodList.take(6).map { PosterAdapter.PosterItem(it.streamId, it.name, it.icon) },
                )
            }
            if (seriesList.isNotEmpty()) {
                sections += RowAdapter.RowSection(
                    "Séries",
                    seriesList.take(6).map { PosterAdapter.PosterItem(it.seriesId, it.name, it.cover) },
                )
            }
            binding.rowsList.adapter = RowAdapter(sections) { poster ->
                openPoster(poster)
            }
            binding.emptyText.visibility =
                if (sections.isEmpty()) View.VISIBLE else View.GONE
            FocusHelper.requestFocus(binding.heroPlayButton)
        }
    }

    private fun openPoster(poster: PosterAdapter.PosterItem) {
        val activity = requireActivity() as MainActivity
        vodList.firstOrNull { it.streamId == poster.id }?.let {
            activity.openDetails(PlaybackHelper.vodDetailsArgs(it))
            return
        }
        seriesList.firstOrNull { it.seriesId == poster.id }?.let {
            activity.openDetails(PlaybackHelper.seriesDetailsArgs(it))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
