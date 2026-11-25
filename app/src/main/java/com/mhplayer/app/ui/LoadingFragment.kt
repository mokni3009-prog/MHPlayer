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
 * Zeno Play loading screen (approved reference): Z logo + "Zeno Play" +
 * tagline, thin gold->cyan progress bar, "Loading Xtream Codes..." below.
 * Drives repository.loadAll(progress), or importM3u for an M3U login, then
 * navigates to Home. Progress callbacks come from Dispatchers.IO — View
 * updates MUST run on the Main thread (v1.4 fix for the stuck progress).
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
                    app.repository.loadAll { _, percent, message ->
                        activity.runOnUiThread { updateProgress(percent, message) }
                    }
                } else {
                    // M3U login: no Xtream auth — fetch + parse the playlist.
                    val url = app.sessionManager.getM3uUrl()
                        ?: throw IllegalStateException("No playlist")
                    val name = app.sessionManager.getPlaylistName()
                    updateProgress(5, getString(R.string.loading_categories))
                    app.repository.importM3u(url, name)
                    updateProgress(60, getString(R.string.loading_channels))
                    updateProgress(100, getString(R.string.loading_done))
                }
                activity.openHome()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.errorText.text = getString(R.string.error_generic)
                binding.errorText.visibility = View.VISIBLE
                binding.retryButton.visibility = View.VISIBLE
                FocusHelper.requestFocus(binding.retryButton)
            }
        }
    }

    private fun updateProgress(percent: Int, message: String) {
        if (!isAdded || _binding == null) return
        binding.loadingProgress.progress = percent
        binding.loadingStatus.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
