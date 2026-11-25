package com.mhplayer.app.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mhplayer.app.data.model.Credentials

/**
 * Session storage: credentials + player settings + last-sync timestamps.
 *
 * Credentials live in [EncryptedSharedPreferences] (MasterKey AES256_GCM).
 * If encrypted prefs cannot be created on the device (e.g. broken keystore),
 * we fall back to plain [SharedPreferences] and log a warning — the app must
 * not crash because of storage. The fallback is a deliberate, documented
 * trade-off: login still works, credentials are just stored unencrypted.
 */
class SessionManager(context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        private const val ENCRYPTED_PREFS_NAME = "mhplayer_secure_prefs"
        private const val PLAIN_PREFS_NAME = "mhplayer_prefs"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PLAYLIST_NAME = "playlist_name"
        private const val KEY_M3U_URL = "m3u_url"
        private const val KEY_ENGINE_SETTING = "engine_setting"
        private const val KEY_QUALITY_LOCK = "quality_lock"
        private const val KEY_HBBTV_NOTE_SEEN = "hbbtv_note_seen"
        private const val KEY_LAST_SYNC_LIVE = "last_sync_live"
        private const val KEY_LAST_SYNC_VOD = "last_sync_vod"
        private const val KEY_LAST_SYNC_SERIES = "last_sync_series"

        // Engine setting values (ARCHITECTURE.md): AUTO | EXO | VLC
        const val ENGINE_AUTO = "AUTO"
        const val ENGINE_EXO = "EXO"
        const val ENGINE_VLC = "VLC"

        // Quality lock values (ARCHITECTURE.md): AUTO | 1080 | 720 | 480
        const val QUALITY_AUTO = "AUTO"
        const val QUALITY_1080 = "1080"
        const val QUALITY_720 = "720"
        const val QUALITY_480 = "480"

        /** Cache TTL used by the repository: content older than this is stale. */
        const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L // 24h
    }

    private val appContext = context.applicationContext

    /** True when the encrypted store was created; false when we fell back to plain prefs. */
    val isEncrypted: Boolean

    private val prefs: SharedPreferences

    init {
        var encrypted: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            encrypted = EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore / crypto failure on this device: fall back, do not crash.
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
        }
        isEncrypted = encrypted != null
        prefs = encrypted
            ?: appContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // -- Credentials --------------------------------------------------------

    fun saveCredentials(creds: Credentials) {
        prefs.edit()
            .putString(KEY_SERVER_URL, creds.serverUrl)
            .putString(KEY_USERNAME, creds.username)
            .putString(KEY_PASSWORD, creds.password)
            .putString(KEY_PLAYLIST_NAME, creds.playlistName)
            .apply()
    }

    fun getCredentials(): Credentials? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null).orEmpty()
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        val password = prefs.getString(KEY_PASSWORD, null).orEmpty()
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) return null
        return Credentials(
            serverUrl = serverUrl,
            username = username,
            password = password,
            playlistName = prefs.getString(KEY_PLAYLIST_NAME, null).orEmpty(),
        )
    }

    fun hasCredentials(): Boolean = getCredentials() != null

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_PLAYLIST_NAME)
            .remove(KEY_M3U_URL)
            .apply()
    }

    // -- M3U playlist ---------------------------------------------------------

    fun saveM3uUrl(url: String, playlistName: String = "") {
        prefs.edit()
            .putString(KEY_M3U_URL, url)
            .putString(KEY_PLAYLIST_NAME, playlistName)
            .apply()
    }

    fun getM3uUrl(): String? = prefs.getString(KEY_M3U_URL, null)

    fun getPlaylistName(): String =
        prefs.getString(KEY_PLAYLIST_NAME, null).orEmpty()

    // -- Player settings ------------------------------------------------------

    var engineSetting: String
        get() = prefs.getString(KEY_ENGINE_SETTING, ENGINE_AUTO) ?: ENGINE_AUTO
        set(value) {
            prefs.edit().putString(KEY_ENGINE_SETTING, value).apply()
        }

    var qualityLock: String
        get() = prefs.getString(KEY_QUALITY_LOCK, QUALITY_AUTO) ?: QUALITY_AUTO
        set(value) {
            prefs.edit().putString(KEY_QUALITY_LOCK, value).apply()
        }

    var hbbtvNoteSeen: Boolean
        get() = prefs.getBoolean(KEY_HBBTV_NOTE_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HBBTV_NOTE_SEEN, value).apply()
        }

    // -- Last-sync timestamps (repository staleness, >24h = stale) -------------

    fun getLastSyncMillis(type: String): Long =
        prefs.getLong(lastSyncKey(type), 0L)

    fun setLastSyncMillis(type: String, atMillis: Long) {
        prefs.edit().putLong(lastSyncKey(type), atMillis).apply()
    }

    fun isStale(type: String): Boolean {
        val last = getLastSyncMillis(type)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last > STALE_AFTER_MILLIS
    }

    private fun lastSyncKey(type: String): String = when (type) {
        "live" -> KEY_LAST_SYNC_LIVE
        "vod" -> KEY_LAST_SYNC_VOD
        "series" -> KEY_LAST_SYNC_SERIES
        else -> "last_sync_$type"
    }

    // -- Full reset ------------------------------------------------------------

    /** Clears everything: credentials, settings and sync timestamps. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
