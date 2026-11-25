package com.mhplayer.app.data.repo

import com.mhplayer.app.data.api.M3uParser
import com.mhplayer.app.data.api.XtreamApi
import com.mhplayer.app.data.api.XtreamApiImpl
import com.mhplayer.app.data.db.AppDatabase
import com.mhplayer.app.data.db.CategoryType
import com.mhplayer.app.data.db.toEntity
import com.mhplayer.app.data.db.toModel
import com.mhplayer.app.data.model.AuthResult
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.Season
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.data.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Repository: auth, full catalog load with progress, Room-first getters with
 * a 24h network refresh, M3U import and logout.
 *
 * All work runs on [Dispatchers.IO]. Network errors propagate to the caller
 * (UI shows them); cached Room data is returned whenever it is fresh enough.
 */
class XtreamRepository(
    private val session: SessionManager,
    private val api: XtreamApi,
    private val db: AppDatabase,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(XtreamApiImpl.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(XtreamApiImpl.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) {

    companion object {
        const val STEP_CATEGORIES = "categories"
        const val STEP_LIVE = "live"
        const val STEP_VOD = "vod"
        const val STEP_SERIES = "series"
        const val STEP_DONE = "done"
    }

    // -- Auth -----------------------------------------------------------------

    /**
     * Authenticates against player_api.php. On success (user_info.status ==
     * "Active") the credentials are stored in the session and the API client
     * is pointed at them. Throws [IllegalStateException] when the panel
     * rejects the login.
     */
    suspend fun login(credentials: Credentials): AuthResult = withContext(Dispatchers.IO) {
        val result = api.authenticate(credentials)
        val status = result.userInfo?.status
        if (status != "Active") {
            throw IllegalStateException(
                "Authentication failed: ${status ?: result.userInfo?.message ?: "unknown"}",
            )
        }
        session.saveCredentials(credentials)
        if (api is XtreamApiImpl) {
            api.setCredentials(credentials)
        }
        result
    }

    /** M3U login: no Xtream auth — just remember the playlist URL + name. */
    suspend fun loginM3u(url: String, playlistName: String = "") {
        withContext(Dispatchers.IO) {
            session.saveM3uUrl(url, playlistName)
        }
    }

    // -- Full load (Loading screen) --------------------------------------------

    /**
     * Fetches everything into Room: categories, then live / VOD / series
     * streams per category. Reports progress as (step, percent 0..100, message).
     * Steps: categories → live → vod → series → done.
     */
    suspend fun loadAll(progress: (step: String, percent: Int, message: String) -> Unit) {
        withContext(Dispatchers.IO) {
            requireCredentials()

            progress(STEP_CATEGORIES, 2, "Chargement des catégories…")
            val liveCategories = api.getLiveCategories()
            val vodCategories = api.getVodCategories()
            val seriesCategories = api.getSeriesCategories()
            db.categoryDao().replaceForType(
                CategoryType.LIVE,
                liveCategories.map { it.toEntity(CategoryType.LIVE) },
            )
            db.categoryDao().replaceForType(
                CategoryType.VOD,
                vodCategories.map { it.toEntity(CategoryType.VOD) },
            )
            db.categoryDao().replaceForType(
                CategoryType.SERIES,
                seriesCategories.map { it.toEntity(CategoryType.SERIES) },
            )
            progress(STEP_CATEGORIES, 10, "Catégories chargées")

            // Live channels (10% -> 45%).
            progress(STEP_LIVE, 12, "Chargement des chaînes…")
            val allLive = mutableListOf<LiveChannel>()
            liveCategories.forEachIndexed { index, category ->
                val streams = api.getLiveStreams(category.id)
                db.liveChannelDao().replaceForCategory(
                    category.id,
                    streams.map { it.toEntity() },
                )
                allLive += streams
                val pct = 12 + (index + 1) * 33 / liveCategories.size.coerceAtLeast(1)
                progress(STEP_LIVE, pct, "Chargement des chaînes… (${index + 1}/${liveCategories.size})")
            }
            session.setLastSyncMillis("live", System.currentTimeMillis())
            progress(STEP_LIVE, 45, "Chaînes chargées (${allLive.size})")

            // VOD (45% -> 70%).
            progress(STEP_VOD, 47, "Chargement des films…")
            var vodCount = 0
            vodCategories.forEachIndexed { index, category ->
                val streams = api.getVodStreams(category.id)
                db.vodDao().replaceForCategory(category.id, streams.map { it.toEntity() })
                vodCount += streams.size
                val pct = 47 + (index + 1) * 23 / vodCategories.size.coerceAtLeast(1)
                progress(STEP_VOD, pct, "Chargement des films… (${index + 1}/${vodCategories.size})")
            }
            session.setLastSyncMillis("vod", System.currentTimeMillis())
            progress(STEP_VOD, 70, "Films chargés ($vodCount)")

            // Series (70% -> 95%).
            progress(STEP_SERIES, 72, "Chargement des séries…")
            var seriesCount = 0
            seriesCategories.forEachIndexed { index, category ->
                val items = api.getSeries(category.id)
                db.seriesDao().replaceForCategory(category.id, items.map { it.toEntity() })
                seriesCount += items.size
                val pct = 72 + (index + 1) * 23 / seriesCategories.size.coerceAtLeast(1)
                progress(STEP_SERIES, pct, "Chargement des séries… (${index + 1}/${seriesCategories.size})")
            }
            session.setLastSyncMillis("series", System.currentTimeMillis())
            progress(STEP_SERIES, 95, "Séries chargées ($seriesCount)")

            progress(STEP_DONE, 100, "Terminé")
        }
    }

    // -- Room-first getters (network refresh when stale > 24h) -----------------

    suspend fun getLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        val cached = db.categoryDao().getByType(CategoryType.LIVE).map { it.toModel() }
        if (cached.isNotEmpty() && !session.isStale("live")) return@withContext cached
        if (requireCredentialsOrNull() == null) return@withContext cached
        runCatching {
            val fresh = api.getLiveCategories()
            db.categoryDao().replaceForType(
                CategoryType.LIVE,
                fresh.map { it.toEntity(CategoryType.LIVE) },
            )
            session.setLastSyncMillis("live", System.currentTimeMillis())
            fresh
        }.getOrDefault(cached)
    }

    suspend fun getLiveStreams(categoryId: String): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            val cached = db.liveChannelDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("live")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getLiveStreams(categoryId)
                db.liveChannelDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                session.setLastSyncMillis("live", System.currentTimeMillis())
                fresh
            }.getOrDefault(cached)
        }

    suspend fun getVodCategories(): List<Category> = withContext(Dispatchers.IO) {
        val cached = db.categoryDao().getByType(CategoryType.VOD).map { it.toModel() }
        if (cached.isNotEmpty() && !session.isStale("vod")) return@withContext cached
        if (requireCredentialsOrNull() == null) return@withContext cached
        runCatching {
            val fresh = api.getVodCategories()
            db.categoryDao().replaceForType(
                CategoryType.VOD,
                fresh.map { it.toEntity(CategoryType.VOD) },
            )
            session.setLastSyncMillis("vod", System.currentTimeMillis())
            fresh
        }.getOrDefault(cached)
    }

    suspend fun getVodStreams(categoryId: String): List<VodItem> =
        withContext(Dispatchers.IO) {
            val cached = db.vodDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("vod")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getVodStreams(categoryId)
                db.vodDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                session.setLastSyncMillis("vod", System.currentTimeMillis())
                fresh
            }.getOrDefault(cached)
        }

    suspend fun getSeriesCategories(): List<Category> = withContext(Dispatchers.IO) {
        val cached = db.categoryDao().getByType(CategoryType.SERIES).map { it.toModel() }
        if (cached.isNotEmpty() && !session.isStale("series")) return@withContext cached
        if (requireCredentialsOrNull() == null) return@withContext cached
        runCatching {
            val fresh = api.getSeriesCategories()
            db.categoryDao().replaceForType(
                CategoryType.SERIES,
                fresh.map { it.toEntity(CategoryType.SERIES) },
            )
            session.setLastSyncMillis("series", System.currentTimeMillis())
            fresh
        }.getOrDefault(cached)
    }

    suspend fun getSeries(categoryId: String): List<SeriesItem> =
        withContext(Dispatchers.IO) {
            val cached = db.seriesDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("series")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getSeries(categoryId)
                db.seriesDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                session.setLastSyncMillis("series", System.currentTimeMillis())
                fresh
            }.getOrDefault(cached)
        }

    /** Episodes for a series: Room first, network (get_series_info) when empty. */
    suspend fun getSeriesInfo(seriesId: Int): List<Season> = withContext(Dispatchers.IO) {
        val cachedEntities = db.episodeDao().getBySeries(seriesId)
        if (cachedEntities.isNotEmpty()) {
            return@withContext cachedEntities
                .groupBy { it.season }
                .toSortedMap()
                .map { (seasonNumber, eps) ->
                    Season(
                        seasonNumber = seasonNumber,
                        episodes = eps.sortedBy { it.episodeNum }.map { it.toModel() },
                    )
                }
        }
        requireCredentialsOrNull() ?: return@withContext emptyList()
        val fresh = api.getSeriesInfo(seriesId)
        val entities = fresh.flatMap { season ->
            season.episodes.map { episode -> episode.toEntity(seriesId) }
        }
        if (entities.isNotEmpty()) {
            db.episodeDao().replaceForSeries(seriesId, entities)
        }
        fresh
    }

    // -- M3U import -------------------------------------------------------------

    /**
     * Fetches an M3U playlist from [url], parses it and replaces the live
     * categories + channels in Room. [playlistName] is stored in the session.
     * Returns the parsed channels.
     */
    suspend fun importM3u(url: String, playlistName: String = ""): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", XtreamApiImpl.USER_AGENT)
                .get()
                .build()
            val call = httpClient.newCall(request)
            call.timeout().timeout(XtreamApiImpl.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val (categories, channels) = call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("M3U download failed with HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw IOException("M3U download returned an empty body")
                M3uParser.parseM3u(body.byteStream())
            }
            db.categoryDao().replaceForType(
                CategoryType.LIVE,
                categories.map { it.toEntity(CategoryType.LIVE) },
            )
            db.liveChannelDao().replaceAll(channels.map { it.toEntity() })
            session.saveM3uUrl(url, playlistName)
            session.setLastSyncMillis("live", System.currentTimeMillis())
            channels
        }

    // -- Logout -----------------------------------------------------------------

    /** Clears credentials, settings, sync state and all cached data. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        if (api is XtreamApiImpl) {
            api.clearCredentials()
        }
        session.clearAll()
        db.clearAllTables()
    }

    // -- Helpers ------------------------------------------------------------------

    private fun requireCredentials(): Credentials =
        session.getCredentials()
            ?: throw IllegalStateException("Not logged in — no credentials in session")

    private fun requireCredentialsOrNull(): Credentials? = session.getCredentials()

    /** Convenience: all live channels regardless of category (Home / Playlist counts). */
    suspend fun getAllLiveChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        db.liveChannelDao().getAll().map { it.toModel() }
    }

    /** Convenience: all VOD items regardless of category. */
    suspend fun getAllVod(): List<VodItem> = withContext(Dispatchers.IO) {
        db.vodDao().getAll().map { it.toModel() }
    }

    /** Convenience: all series regardless of category. */
    suspend fun getAllSeries(): List<SeriesItem> = withContext(Dispatchers.IO) {
        db.seriesDao().getAll().map { it.toModel() }
    }

    /** Convenience: episodes of one series, for the Details screen. */
    suspend fun getEpisodes(seriesId: Int): List<Episode> = withContext(Dispatchers.IO) {
        db.episodeDao().getBySeries(seriesId).map { it.toModel() }
    }
}
