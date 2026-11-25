package com.mhplayer.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.mhplayer.app.data.api.XtreamApiImpl
import com.mhplayer.app.data.db.AppDatabase
import com.mhplayer.app.data.repo.XtreamRepository
import com.mhplayer.app.data.session.SessionManager
import com.mhplayer.app.player.DualEnginePlayer
import com.mhplayer.app.player.EngineMode
import com.mhplayer.app.player.ExoLivePlayer
import com.mhplayer.app.player.VlcLivePlayer
import com.mhplayer.app.util.CrashReporter
import com.mhplayer.app.util.NetworkMonitor
import com.mhplayer.app.util.RemoteTestMode
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application: lazy singletons shared by the whole app.
 *
 * - ONE shared [OkHttpClient] for the Xtream API, the players and image
 *   loading (ARCHITECTURE.md: same client everywhere, UA `MHPlayer/1.0`).
 * - ONE [ExoLivePlayer] + ONE [VlcLivePlayer] for the process lifetime,
 *   owned by [DualEnginePlayer].
 * - TV profile detection: `isTv` is true on the X96 Mate / TV box
 *   (`UI_MODE_TYPE_TELEVISION`); it drives the ExoPlayer 1080p cap and
 *   tunnelling (see [ExoLivePlayer]).
 */
class App : Application() {

    /** True on a TV box (X96 Mate), false on phones. */
    val isTv: Boolean by lazy {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** ONE shared OkHttp client (API + players + Coil). */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(XtreamApiImpl.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(XtreamApiImpl.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(XtreamApiImpl.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val sessionManager: SessionManager by lazy { SessionManager(this) }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }

    val xtreamApi: XtreamApiImpl by lazy {
        XtreamApiImpl(
            client = httpClient,
            credentialsProvider = { sessionManager.getCredentials() },
        ).also { api ->
            // Restore the saved session so API calls work after a restart.
            sessionManager.getCredentials()?.let { api.setCredentials(it) }
        }
    }

    val repository: XtreamRepository by lazy {
        XtreamRepository(sessionManager, xtreamApi, database, httpClient)
    }

    val exoPlayer: ExoLivePlayer by lazy { ExoLivePlayer(this, httpClient, isTv) }

    val vlcPlayer: VlcLivePlayer by lazy { VlcLivePlayer(this, httpClient) }

    val dualPlayer: DualEnginePlayer by lazy {
        DualEnginePlayer(
            exoPlayer = exoPlayer,
            vlcPlayer = vlcPlayer,
            playbackDao = database.channelPlaybackDao(),
            networkMonitor = networkMonitor,
            engineMode = when (sessionManager.engineSetting) {
                SessionManager.ENGINE_EXO -> EngineMode.EXO
                SessionManager.ENGINE_VLC -> EngineMode.VLC
                else -> EngineMode.AUTO
            },
        ).also { player ->
            // Saved quality lock applies at startup (and live on Exo).
            player.setQualityLock(
                when (sessionManager.qualityLock) {
                    SessionManager.QUALITY_1080 -> 1080
                    SessionManager.QUALITY_720 -> 720
                    SessionManager.QUALITY_480 -> 480
                    else -> 0
                },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // FIRST: install the crash reporter so any later startup crash is
        // captured and sent to our server (it also uploads queued reports
        // from a previous crashed run).
        CrashReporter.install(this)
        // Remote test mode (v1.4, DEV ONLY): lets Sahboush drive the app on
        // the user's phone through our Cloudflare Worker. Gated by the
        // REMOTE_TEST_ENABLED build flag — removed in the final release.
        if (BuildConfig.REMOTE_TEST_ENABLED) {
            RemoteTestMode.start(this)
        }
        networkMonitor.start()
    }
}

/** Convenience accessor used by fragments. */
fun Context.app(): App = applicationContext as App
