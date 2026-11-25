package com.mhplayer.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.repo.XtreamRepository
import com.mhplayer.app.databinding.FragmentLoadingBinding
import kotlinx.coroutines.launch

/**
 * Loading screen: logo top-center, progress ring + red linear bar + %,
 * status text, steps row (Chaînes ✓ / Films in progress / Séries grey),
 * "Ma playlist" at the bottom. Drives repository.loadAll(progress), or
 * importM3u for an M3U login, then navigates to Home.
 */
class LoadingFragment : Fragment() {

    private var _binding: FragmentLoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLoadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.retryButton.setOnClickListener { startLoading() }
        startLoading()
    }

    private fun startLoading() {
        val app = requireContext().app()
        val activity = requireActivity() as MainActivity
        binding.errorText.visibility = View.GONE
        binding.retryButton.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (app.sessionManager.getCredentials() != null) {
                    app.repository.loadAll { step, percent, message ->
                        updateProgress(step, percent, message)
                    }
                } else {
                    // M3U login: no Xtream auth — fetch + parse the playlist.
                    val url = app.sessionManager.getM3uUrl()
                        ?: throw IllegalStateException("Aucune playlist")
                    val name = app.sessionManager.getPlaylistName()
                    updateProgress(XtreamRepository.STEP_CATEGORIES, 5, "Chargement de la playlist…")
                    app.repository.importM3u(url, name)
                    updateProgress(XtreamRepository.STEP_LIVE, 60, "Chargement des chaînes…")
                    updateProgress(XtreamRepository.STEP_DONE, 100, "Terminé")
                }
                activity.openHome()
            } catch (e: Exception) {
                binding.errorText.text =
                    "Échec du chargement — vérifiez votre connexion puis réessayez"
                binding.errorText.visibility = View.VISIBLE
                binding.retryButton.visibility = View.VISIBLE
                FocusHelper.requestFocus(binding.retryButton)
            }
        }
    }

    private fun updateProgress(step: String, percent: Int, message: String) {
        if (!isAdded) return
        binding.progressBar.progress = percent
        binding.progressRing.progress = percent
        binding.percentText.text = "$percent%"
        binding.statusText.text = message

        val liveState = when (step) {
            XtreamRepository.STEP_CATEGORIES -> 1
            XtreamRepository.STEP_LIVE -> 1
            XtreamRepository.STEP_VOD, XtreamRepository.STEP_SERIES, XtreamRepository.STEP_DONE -> 2
            else -> 0
        }
        val vodState = when (step) {
            XtreamRepository.STEP_VOD -> 1
            XtreamRepository.STEP_SERIES, XtreamRepository.STEP_DONE -> 2
            else -> 0
        }
        val seriesState = when (step) {
            XtreamRepository.STEP_SERIES -> 1
            XtreamRepository.STEP_DONE -> 2
            else -> 0
        }
        applyStep(binding.stepLiveIcon, binding.stepLiveLabel, liveState)
        applyStep(binding.stepVodIcon, binding.stepVodLabel, vodState)
        applyStep(binding.stepSeriesIcon, binding.stepSeriesLabel, seriesState)
    }

    private fun applyStep(icon: android.widget.TextView, label: android.widget.TextView, state: Int) {
        val context = requireContext()
        when (state) {
            2 -> { // done: green check
                icon.text = "✓"
                icon.setTextColor(context.getColor(R.color.color_success_green))
                label.setTextColor(context.getColor(R.color.color_success_green))
            }
            1 -> { // in progress: red dot
                icon.text = "●"
                icon.setTextColor(context.getColor(R.color.color_primary_red))
                label.setTextColor(context.getColor(R.color.color_primary_red))
            }
            else -> { // pending: grey hollow circle
                icon.text = "○"
                icon.setTextColor(context.getColor(R.color.color_text_disabled))
                label.setTextColor(context.getColor(R.color.color_text_disabled))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
