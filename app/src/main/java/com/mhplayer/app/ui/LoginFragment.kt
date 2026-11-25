package com.mhplayer.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

/**
 * Zeno Play login (approved reference): Z logo + "Zeno Play" + tagline,
 * tabs Xtream Codes / Playlist M3U, 4 Xtream fields (URL / Username /
 * Password with eye toggle / Playlist Name (Optional)), "Save Account
 * Information" switch, gold->cyan "Sign In" button, "Forgot Password?" /
 * "Add User" links and the "Need an Xtream Code?" line.
 *
 * The M3U tab is kept (v1.5 functionality) — the reference image shows the
 * Xtream form only, the M3U tab stays as the second tab. On success ->
 * LoadingFragment.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private var isM3uTab = false
    private var passwordVisible = false

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

        binding.passwordToggle.setOnClickListener { togglePasswordVisibility() }
        binding.loginButton.setOnClickListener { doLogin() }
        binding.m3uLoginButton.setOnClickListener { doLogin() }
        binding.forgotPassword.setOnClickListener {
            Toast.makeText(requireContext(), R.string.login_need_code, Toast.LENGTH_LONG).show()
        }
        binding.addUser.setOnClickListener {
            Toast.makeText(requireContext(), R.string.login_need_code, Toast.LENGTH_LONG).show()
        }

        selectTab(false)
        FocusHelper.requestFocus(binding.inputServer)
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val input = binding.inputPassword
        input.inputType = if (passwordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        input.setSelection(input.text?.length ?: 0)
        binding.passwordToggle.setImageResource(
            if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
        )
    }

    private fun selectTab(m3u: Boolean) {
        isM3uTab = m3u
        binding.formXtream.visibility = if (m3u) View.GONE else View.VISIBLE
        binding.formM3u.visibility = if (m3u) View.VISIBLE else View.GONE
        binding.tabXtream.setBackgroundResource(
            if (m3u) R.drawable.bg_tab_pill_inactive else R.drawable.bg_tab_pill_active,
        )
        binding.tabM3u.setBackgroundResource(
            if (m3u) R.drawable.bg_tab_pill_active else R.drawable.bg_tab_pill_inactive,
        )
        // Dark text on the gradient pill, muted on the inactive one.
        binding.tabXtreamLabel.setTextColor(
            requireContext().getColor(
                if (m3u) R.color.zeno_text_muted else R.color.zeno_text_on_gradient,
            ),
        )
        binding.tabM3uLabel.setTextColor(
            requireContext().getColor(
                if (m3u) R.color.zeno_text_on_gradient else R.color.zeno_text_muted,
            ),
        )
        binding.loginFooter.text =
            if (m3u) getString(R.string.login_tab_m3u) else getString(R.string.login_tab_xtream)
        binding.errorText.visibility = View.GONE
    }

    private fun doLogin() {
        val app = requireContext().app()
        val activity = requireActivity() as MainActivity
        binding.errorText.visibility = View.GONE
        binding.loginButton.isEnabled = false
        binding.m3uLoginButton.isEnabled = false
        binding.loginProgress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isM3uTab) {
                    val url = binding.inputM3uUrl.text.toString().trim()
                    val name = binding.inputM3uPlaylistName.text.toString().trim()
                    if (url.isBlank()) {
                        throw IllegalArgumentException(getString(R.string.login_error_fill_all))
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
                        throw IllegalArgumentException(getString(R.string.login_error_fill_all))
                    }
                    app.repository.login(
                        creds,
                        saveCredentials = binding.saveSwitch.isChecked,
                    )
                }
                activity.openLoading()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.errorText.text = getString(R.string.login_error_invalid)
                binding.errorText.visibility = View.VISIBLE
            } finally {
                if (isAdded) {
                    binding.loginButton.isEnabled = true
                    binding.m3uLoginButton.isEnabled = true
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
