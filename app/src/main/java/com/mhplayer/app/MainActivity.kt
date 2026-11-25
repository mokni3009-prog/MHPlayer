package com.mhplayer.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.mhplayer.app.player.DualEnginePlayer
import com.mhplayer.app.ui.ChannelsFragment
import com.mhplayer.app.ui.DetailsFragment
import com.mhplayer.app.ui.HomeFragment
import com.mhplayer.app.ui.LoadingFragment
import com.mhplayer.app.ui.LoginFragment
import com.mhplayer.app.ui.MoviesFragment
import com.mhplayer.app.ui.PlayerFragment
import com.mhplayer.app.ui.PlaylistFragment
import com.mhplayer.app.ui.SeriesFragment
import com.mhplayer.app.util.AppUpdater

/**
 * Single activity hosting every fragment with manual transactions.
 *
 * Routes: Login -> Loading -> Home. The sidebar navigates between
 * Home / Channels / Movies / Series / Playlist (top-level: no back-stack
 * entry, back stack cleared). Details and PlayerFragment go on top.
 *
 * BACK order: PlayerFragment exits to the previous screen first, then the
 * fragment back stack, then finish().
 */
class MainActivity : AppCompatActivity() {

    enum class Destination { HOME, CHANNELS, MOVIES, SERIES, PLAYLIST }

    /** Shared dual-engine player for all playback fragments. */
    val player: DualEnginePlayer
        get() = (application as App).dualPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            val app = application as App
            if (app.sessionManager.hasCredentials() || app.sessionManager.getM3uUrl() != null) {
                // v1.5 fast start: the catalog lives in Room now. When a
                // cached catalog exists, open Home immediately and refresh
                // in the background — no more loading from Xtream on every
                // launch. First run (empty cache) still goes to Loading.
                lifecycleScope.launch {
                    val hasCache = runCatching { app.repository.hasCachedCatalog() }
                        .getOrDefault(false)
                    if (hasCache) {
                        openHome()
                        app.repository.refreshInBackground()
                    } else {
                        showFragment(LoadingFragment(), TAG_LOADING, addToBackStack = false)
                    }
                }
            } else {
                showFragment(LoginFragment(), TAG_LOGIN, addToBackStack = false)
            }
        }
        // Self-update check (GitHub): once per launch, non-blocking.
        AppUpdater.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        // If the user just enabled "install unknown apps" in Settings,
        // offer the pending update again.
        AppUpdater.onResume(this)
    }

    /** Replaces the container content. Top-level screens clear the back stack. */
    fun showFragment(fragment: Fragment, tag: String, addToBackStack: Boolean = true) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .apply { if (addToBackStack) addToBackStack(tag) }
            .commit()
    }

    /** Sidebar navigation: top-level destination, back stack cleared. */
    fun navigateTo(destination: Destination) {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        val targetTag = when (destination) {
            Destination.HOME -> TAG_HOME
            Destination.CHANNELS -> TAG_CHANNELS
            Destination.MOVIES -> TAG_MOVIES
            Destination.SERIES -> TAG_SERIES
            Destination.PLAYLIST -> TAG_PLAYLIST
        }
        if (current?.tag == targetTag) return
        // Leave any fullscreen player / details screen first.
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val fragment: Fragment = when (destination) {
            Destination.HOME -> HomeFragment()
            Destination.CHANNELS -> ChannelsFragment()
            Destination.MOVIES -> MoviesFragment()
            Destination.SERIES -> SeriesFragment()
            Destination.PLAYLIST -> PlaylistFragment()
        }
        showFragment(fragment, targetTag, addToBackStack = false)
    }

    fun openHome() = navigateTo(Destination.HOME)

    fun openLoading() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        showFragment(LoadingFragment(), TAG_LOADING, addToBackStack = false)
    }

    fun openDetails(args: Bundle) {
        val fragment = DetailsFragment().apply { arguments = args }
        showFragment(fragment, TAG_DETAILS)
    }

    fun openPlayer(args: Bundle) {
        val fragment = PlayerFragment().apply { arguments = args }
        showFragment(fragment, TAG_PLAYER)
    }

    /** True when the fullscreen player is the visible fragment. */
    fun isPlayerVisible(): Boolean =
        supportFragmentManager.findFragmentById(R.id.fragmentContainer) is PlayerFragment

    /** Pops the top fragment (player / details) if any; false when at a top-level screen. */
    fun popTop(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // PlayerFragment exits to the previous screen first.
            if (isPlayerVisible()) {
                player.stop()
                popTop()
                return true
            }
            if (popTop()) return true
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Use the KEYCODE_BACK handling above for DPAD remotes.")
    override fun onBackPressed() {
        if (isPlayerVisible()) {
            player.stop()
            popTop()
            return
        }
        if (!popTop()) super.onBackPressed()
    }

    companion object {
        const val TAG_LOGIN = "login"
        const val TAG_LOADING = "loading"
        const val TAG_HOME = "home"
        const val TAG_CHANNELS = "channels"
        const val TAG_MOVIES = "movies"
        const val TAG_SERIES = "series"
        const val TAG_PLAYLIST = "playlist"
        const val TAG_DETAILS = "details"
        const val TAG_PLAYER = "player"
    }
}
