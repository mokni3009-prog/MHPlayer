package com.mhplayer.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhplayer.app.BuildConfig
import com.mhplayer.app.MainActivity
import com.mhplayer.app.R
import com.mhplayer.app.app
import com.mhplayer.app.data.session.SessionManager
import com.mhplayer.app.databinding.DialogPlayerSettingsBinding
import com.mhplayer.app.databinding.FragmentSettingsBinding
import com.mhplayer.app.player.Engine
import com.mhplayer.app.player.EngineMode
import com.mhplayer.app.ui.adapters.SettingsAdapter
import com.mhplayer.app.util.AppUpdater

/**
 * Zeno Play Settings (approved reference): fixed sidebar (Settings
 * active), rows with gold->cyan switches: Player / Subtitles /
 * Audio Language / Video Quality / Appearance / Parental Control /
 * Updates / About. Every row does something real — no decoration.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val adapter = SettingsAdapter(
        onToggle = { id, checked -> onToggle(id, checked) },
        onClick = { id -> onRowClick(id) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        binding.sidebar.setSelected(SidebarView.Item.SETTINGS)
        binding.sidebar.onItemSelected = { item ->
            when (item) {
                SidebarView.Item.HOME -> activity.navigateTo(MainActivity.Destination.HOME)
                SidebarView.Item.CHANNELS -> activity.navigateTo(MainActivity.Destination.CHANNELS)
                SidebarView.Item.MOVIES -> activity.navigateTo(MainActivity.Destination.MOVIES)
                SidebarView.Item.SERIES -> activity.navigateTo(MainActivity.Destination.SERIES)
                SidebarView.Item.FAVORITES -> activity.navigateTo(MainActivity.Destination.FAVORITES)
                SidebarView.Item.SETTINGS -> Unit
                SidebarView.Item.ACCOUNT -> activity.navigateTo(MainActivity.Destination.ACCOUNT)
            }
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.sidebar.setHorizontal(true)
        }

        binding.settingsList.layoutManager = LinearLayoutManager(requireContext())
        binding.settingsList.adapter = adapter
        refreshRows()
    }

    private fun refreshRows() {
        val session = requireContext().app().sessionManager
        adapter.submitList(
            listOf(
                SettingsAdapter.SettingRow(
                    id = "player",
                    titleRes = R.string.settings_player,
                    descRes = R.string.settings_player_desc,
                    iconRes = R.drawable.ic_settings,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
                SettingsAdapter.SettingRow(
                    id = "subtitles",
                    titleRes = R.string.settings_subtitles,
                    descRes = R.string.settings_subtitles_desc,
                    iconRes = R.drawable.ic_list,
                    kind = SettingsAdapter.Kind.SWITCH,
                    switchValue = session.subtitlesEnabled,
                ),
                SettingsAdapter.SettingRow(
                    id = "audio",
                    titleRes = R.string.settings_audio,
                    descRes = R.string.settings_audio_desc,
                    iconRes = R.drawable.ic_globe,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
                SettingsAdapter.SettingRow(
                    id = "quality",
                    titleRes = R.string.settings_quality,
                    descRes = R.string.settings_quality_desc,
                    iconRes = R.drawable.ic_tv,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
                SettingsAdapter.SettingRow(
                    id = "appearance",
                    titleRes = R.string.settings_appearance,
                    descRes = R.string.settings_appearance_desc,
                    iconRes = R.drawable.ic_grid,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
                SettingsAdapter.SettingRow(
                    id = "parental",
                    titleRes = R.string.settings_parental,
                    descRes = R.string.settings_parental_desc,
                    iconRes = R.drawable.ic_lock,
                    kind = SettingsAdapter.Kind.SWITCH,
                    switchValue = session.parentalControlEnabled,
                ),
                SettingsAdapter.SettingRow(
                    id = "updates",
                    titleRes = R.string.settings_updates,
                    descRes = R.string.settings_updates_desc,
                    iconRes = R.drawable.ic_check,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
                SettingsAdapter.SettingRow(
                    id = "about",
                    titleRes = R.string.settings_about,
                    descRes = R.string.settings_about_desc,
                    iconRes = R.drawable.ic_clapperboard,
                    kind = SettingsAdapter.Kind.ACTION,
                ),
            ),
        )
    }

    private fun onToggle(id: String, checked: Boolean) {
        val app = requireContext().app()
        when (id) {
            "subtitles" -> {
                app.sessionManager.subtitlesEnabled = checked
                app.dualPlayer.setSubtitlesEnabled(checked)
            }
            "parental" -> {
                app.sessionManager.parentalControlEnabled = checked
                Toast.makeText(
                    requireContext(),
                    if (checked) "Adult content hidden" else "Adult content shown",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun onRowClick(id: String) {
        when (id) {
            "player" -> showPlayerSettings()
            "audio" -> showAudioLanguageDialog()
            "quality" -> showQualityDialog()
            "appearance" -> showAppearanceDialog()
            "updates" -> AppUpdater.checkNow(requireActivity() as MainActivity)
            "about" -> showAboutDialog()
        }
    }

    /** Engine / quality / container — the same dialog as the player screen. */
    private fun showPlayerSettings() {
        val app = requireContext().app()
        val session = app.sessionManager
        val dialogBinding = DialogPlayerSettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        when (session.engineSetting) {
            SessionManager.ENGINE_EXO -> dialogBinding.engineExo.isChecked = true
            SessionManager.ENGINE_VLC -> dialogBinding.engineVlc.isChecked = true
            else -> dialogBinding.engineAuto.isChecked = true
        }
        when (session.qualityLock) {
            SessionManager.QUALITY_1080 -> dialogBinding.quality1080.isChecked = true
            SessionManager.QUALITY_720 -> dialogBinding.quality720.isChecked = true
            SessionManager.QUALITY_480 -> dialogBinding.quality480.isChecked = true
            else -> dialogBinding.qualityAuto.isChecked = true
        }
        // The container (HLS/TS) preference lives only in the player
        // screen's dialog (PlayerFragment.containerPref) — hide it here
        // instead of showing a control that does nothing.
        dialogBinding.containerTitle.visibility = View.GONE
        dialogBinding.containerGroup.visibility = View.GONE

        dialogBinding.settingsOkButton.setOnClickListener {
            val engineSetting = when (dialogBinding.engineGroup.checkedRadioButtonId) {
                R.id.engineExo -> SessionManager.ENGINE_EXO
                R.id.engineVlc -> SessionManager.ENGINE_VLC
                else -> SessionManager.ENGINE_AUTO
            }
            val qualityLock = when (dialogBinding.qualityGroup.checkedRadioButtonId) {
                R.id.quality1080 -> SessionManager.QUALITY_1080
                R.id.quality720 -> SessionManager.QUALITY_720
                R.id.quality480 -> SessionManager.QUALITY_480
                else -> SessionManager.QUALITY_AUTO
            }
            session.engineSetting = engineSetting
            session.qualityLock = qualityLock
            val player = app.dualPlayer
            player.engineMode = when (engineSetting) {
                SessionManager.ENGINE_EXO -> EngineMode.EXO
                SessionManager.ENGINE_VLC -> EngineMode.VLC
                else -> EngineMode.AUTO
            }
            player.setQualityLock(
                when (qualityLock) {
                    SessionManager.QUALITY_1080 -> 1080
                    SessionManager.QUALITY_720 -> 720
                    SessionManager.QUALITY_480 -> 480
                    else -> 0
                },
            )
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showAudioLanguageDialog() {
        val app = requireContext().app()
        val session = app.sessionManager
        val labels = arrayOf(
            getString(R.string.settings_lang_auto),
            getString(R.string.settings_lang_en),
            getString(R.string.settings_lang_fr),
            getString(R.string.settings_lang_ar),
            getString(R.string.settings_lang_es),
        )
        val values = arrayOf("auto", "en", "fr", "ar", "es")
        val current = values.indexOf(session.audioLanguage).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_audio)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val lang = values[which]
                session.audioLanguage = lang
                app.dualPlayer.setPreferredAudioLanguage(if (lang == "auto") null else lang)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val app = requireContext().app()
        val session = app.sessionManager
        val labels = arrayOf(
            getString(R.string.settings_quality_auto),
            getString(R.string.settings_quality_1080),
            getString(R.string.settings_quality_720),
            getString(R.string.settings_quality_480),
        )
        val values = arrayOf(
            SessionManager.QUALITY_AUTO,
            SessionManager.QUALITY_1080,
            SessionManager.QUALITY_720,
            SessionManager.QUALITY_480,
        )
        val current = values.indexOf(session.qualityLock).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_quality)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val lock = values[which]
                session.qualityLock = lock
                app.dualPlayer.setQualityLock(
                    when (lock) {
                        SessionManager.QUALITY_1080 -> 1080
                        SessionManager.QUALITY_720 -> 720
                        SessionManager.QUALITY_480 -> 480
                        else -> 0
                    },
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppearanceDialog() {
        // Zeno Play is dark by design (the approved identity); the dialog
        // says so plainly instead of offering a light theme that does not
        // exist.
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_appearance)
            .setMessage(getString(R.string.settings_theme_dark))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_about)
            .setMessage(
                getString(
                    R.string.settings_about_text,
                    BuildConfig.VERSION_NAME,
                ),
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
