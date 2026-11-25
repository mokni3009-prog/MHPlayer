package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.databinding.FragmentAccountBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zeno Play Account (approved reference): fixed sidebar (Account active),
 * profile card with avatar + display name + Premium badge, subscription
 * details (status / expiry / max connections / created) and "Edit
 * Profile" / "Sign Out" buttons. All data comes from the real Xtream
 * login (user_info saved at login) — nothing is invented.
 */
class AccountFragment : Fragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.ACCOUNT)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.FAVORITES -> activity.navigateTo(MainActivity.Destination.FAVORITES)
                SidebarView.Item.SETTINGS -> activity.navigateTo(MainActivity.Destination.SETTINGS)
                SidebarView.Item.ACCOUNT -> Unit
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        val session = requireContext().app().sessionManager
        val creds = session.getCredentials()
        val username = session.userUsername.ifBlank { creds?.username.orEmpty() }
        binding.displayName.text = session.displayName.ifBlank { username }
        binding.usernameText.text = username
        val isTrial = session.userIsTrial == "1"
        binding.premiumBadge.text =
            if (isTrial) getString(R.string.account_standard)
            else getString(R.string.account_premium)
        binding.statusText.text = when (session.userStatus) {
            "Active" -> getString(R.string.account_active)
            "Expired" -> getString(R.string.account_expired)
            else -> session.userStatus.ifBlank { "—" }
        }
        binding.expiresText.text = formatUnixSeconds(session.userExpDate)
        binding.maxConnectionsText.text =
            session.userMaxConnections.ifBlank { "—" }
        binding.createdText.text = formatUnixSeconds(session.userCreatedAt)

        binding.editProfileButton.setOnClickListener { showEditProfile() }
        binding.signOutButton.setOnClickListener { showSignOutConfirm() }
    }

    /** exp_date / created_at from the panel are Unix seconds ("1754000000"). */
    private fun formatUnixSeconds(value: String): String {
        val seconds = value.toLongOrNull() ?: return if (value.isBlank()) "—" else value
        return try {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(Date(seconds * 1000L))
        } catch (_: Exception) {
            value
        }
    }

    private fun showEditProfile() {
        val session = requireContext().app().sessionManager
        val input = EditText(requireContext()).apply {
            setText(session.displayName.ifBlank { session.userUsername })
            hint = getString(R.string.account_edit)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.account_edit)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                session.displayName = input.text.toString().trim()
                binding.displayName.text = session.displayName.ifBlank { session.userUsername }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSignOutConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.account_sign_out)
            .setMessage(R.string.account_sign_out_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                val activity = requireActivity() as MainActivity
                viewLifecycleOwner.lifecycleScope.launch {
                    requireContext().app().repository.logout()
                    if (!isAdded) return@launch
                    activity.showFragment(
                        LoginFragment(),
                        MainActivity.TAG_LOGIN,
                        addToBackStack = false,
                    )
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
