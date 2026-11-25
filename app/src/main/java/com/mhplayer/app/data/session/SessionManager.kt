package com.mhplayer.app.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.data.model.UserInfo

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
        // v1.6 — Settings + Account + Continue Watching.
        private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
        private const val KEY_AUDIO_LANGUAGE = "audio_language"
        private const val KEY_PARENTAL_CONTROL = "parental_control"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USER_USERNAME = "user_username"
        private const val KEY_USER_STATUS = "user_status"
        private const val KEY_USER_EXP_DATE = "user_exp_date"
        private const val KEY_USER_MAX_CONNECTIONS = "user_max_connections"
        private const val KEY_USER_CREATED_AT = "user_created_at"
        private const val KEY_USER_IS_TRIAL = "user_is_trial"
        private const val KEY_RECENT_ITEMS = "recent_items"
        private const val MAX_RECENT_ITEMS = 12

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

    // -- v1.6 Settings --------------------------------------------------------

    /** Show subtitles when the stream has them (default off). */
    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SUBTITLES_ENABLED, value).apply()
        }

    /** Preferred audio language: "auto" | "en" | "fr" | "ar" | "es". */
    var audioLanguage: String
        get() = prefs.getString(KEY_AUDIO_LANGUAGE, "auto") ?: "auto"
        set(value) {
            prefs.edit().putString(KEY_AUDIO_LANGUAGE, value).apply()
        }

    /** Parental control: hides adult categories and items everywhere. */
    var parentalControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARENTAL_CONTROL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PARENTAL_CONTROL, value).apply()
        }

    /** Display name shown on the Account screen (editable, "Edit Profile"). */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()
        }

    // -- Subscription info (Account screen, saved at login) -------------------

    fun saveUserInfo(info: UserInfo) {
        prefs.edit()
            .putString(KEY_USER_USERNAME, info.username)
            .putString(KEY_USER_STATUS, info.status)
            .putString(KEY_USER_EXP_DATE, info.expDate)
            .putString(KEY_USER_MAX_CONNECTIONS, info.maxConnections)
            .putString(KEY_USER_CREATED_AT, info.createdAt)
            .putString(KEY_USER_IS_TRIAL, info.isTrial)
            .apply()
    }

    val userUsername: String get() = prefs.getString(KEY_USER_USERNAME, null).orEmpty()
    val userStatus: String get() = prefs.getString(KEY_USER_STATUS, null).orEmpty()
    val userExpDate: String get() = prefs.getString(KEY_USER_EXP_DATE, null).orEmpty()
    val userMaxConnections: String get() = prefs.getString(KEY_USER_MAX_CONNECTIONS, null).orEmpty()
    val userCreatedAt: String get() = prefs.getString(KEY_USER_CREATED_AT, null).orEmpty()
    val userIsTrial: String get() = prefs.getString(KEY_USER_IS_TRIAL, null).orEmpty()

    // -- Continue Watching (Home) ----------------------------------------------

    /**
     * Recently played items, most recent first, as "type:id" entries
     * (type = live | vod | series). Max [MAX_RECENT_ITEMS]; duplicates move
     * to the front. Used by Home's "Continue Watching" row.
     */
    fun addRecentItem(type: String, id: Int) {
        val entry = "$type:$id"
        val list = getRecentItems().toMutableList()
        list.remove(entry)
        list.add(0, entry)
        val trimmed = list.take(MAX_RECENT_ITEMS)
        prefs.edit().putString(KEY_RECENT_ITEMS, trimmed.joinToString(",")).apply()
    }

    fun getRecentItems(): List<String> =
        prefs.getString(KEY_RECENT_ITEMS, null).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

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
