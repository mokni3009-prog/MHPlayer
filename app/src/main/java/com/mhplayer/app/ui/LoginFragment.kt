package com.mhplayer.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.MainActivity
import com.mhplayer.app.app
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

/**
 * Login screen (login-approved.png): red play-triangle + "MH Player",
 * tabs Xtream Codes (active) / Playlist M3U, 4 Xtream fields + red
 * "Se connecter". On success -> LoadingFragment.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private var isM3uTab = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireContext().app()

        // Prefill saved values (password is stored encrypted in the session).
        app.sessionManager.getCredentials()?.let { creds ->
            binding.inputServer.setText(creds.serverUrl)
            binding.inputUsername.setText(creds.username)
            binding.inputPassword.setText(creds.password)
            binding.inputPlaylistName.setText(creds.playlistName)
        }
        app.sessionManager.getM3uUrl()?.let { url ->
            binding.inputM3uUrl.setText(url)
            binding.inputM3uPlaylistName.setText(app.sessionManager.getPlaylistName())
        }

        binding.tabXtream.setOnClickListener { selectTab(false) }
        binding.tabM3u.setOnClickListener { selectTab(true) }

        binding.loginButton.setOnClickListener { doLogin() }
        selectTab(false)
        FocusHelper.requestFocus(binding.inputServer)
    }

    private fun selectTab(m3u: Boolean) {
        isM3uTab = m3u
        binding.formXtream.visibility = if (m3u) View.GONE else View.VISIBLE
        binding.formM3u.visibility = if (m3u) View.VISIBLE else View.GONE
        // v1.4 redesign: segmented pill tabs — the active tab gets the red
        // gradient pill, the inactive one stays transparent.
        binding.tabXtream.setBackgroundResource(
            if (m3u) com.mhplayer.app.R.drawable.bg_tab_pill_inactive
            else com.mhplayer.app.R.drawable.bg_tab_pill_active,
        )
        binding.tabM3u.setBackgroundResource(
            if (m3u) com.mhplayer.app.R.drawable.bg_tab_pill_active
            else com.mhplayer.app.R.drawable.bg_tab_pill_inactive,
        )
        binding.tabXtreamLabel.setTextColor(
            requireContext().getColor(
                if (m3u) com.mhplayer.app.R.color.color_text_muted
                else com.mhplayer.app.R.color.color_text_primary,
            ),
        )
        binding.tabM3uLabel.setTextColor(
            requireContext().getColor(
                if (m3u) com.mhplayer.app.R.color.color_text_primary
                else com.mhplayer.app.R.color.color_text_muted,
            ),
        )
        binding.loginFooter.text =
            if (m3u) "Connexion Playlist M3U" else "Connexion Xtream Codes"
        binding.errorText.visibility = View.GONE
    }

    private fun doLogin() {
        val app = requireContext().app()
        val activity = requireActivity() as MainActivity
        binding.errorText.visibility = View.GONE
        binding.loginButton.isEnabled = false
        binding.loginProgress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isM3uTab) {
                    val url = binding.inputM3uUrl.text.toString().trim()
                    val name = binding.inputM3uPlaylistName.text.toString().trim()
                    if (url.isBlank()) {
                        throw IllegalArgumentException("URL de la playlist manquante")
                    }
                    app.repository.loginM3u(url, name)
                } else {
                    val creds = Credentials(
                        serverUrl = binding.inputServer.text.toString().trim(),
                        username = binding.inputUsername.text.toString().trim(),
                        password = binding.inputPassword.text.toString(),
                        playlistName = binding.inputPlaylistName.text.toString().trim(),
                    )
                    if (creds.serverUrl.isBlank() || creds.username.isBlank() || creds.password.isBlank()) {
                        throw IllegalArgumentException("Veuillez remplir tous les champs")
                    }
                    app.repository.login(creds)
                }
                activity.openLoading()
            } catch (e: Exception) {
                binding.errorText.text =
                    "Échec de la connexion — vérifiez vos identifiants"
                binding.errorText.visibility = View.VISIBLE
            } finally {
                if (isAdded) {
                    binding.loginButton.isEnabled = true
                    binding.loginProgress.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
