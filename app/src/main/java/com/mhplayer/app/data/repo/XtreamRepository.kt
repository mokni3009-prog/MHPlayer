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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

        /** Overall guard for a full catalog load: it always ends (success or
         *  clear error) within 3 minutes, never hangs forever (v1.4). */
        const val LOAD_ALL_TIMEOUT_MS = 180_000L
        /** Max concurrent per-category stream requests (v1.4 speed). */
        const val MAX_PARALLEL_STREAMS = 6
        /** Network retries per call, with exponential backoff (v1.4). */
        const val RETRY_ATTEMPTS = 2
        const val RETRY_BACKOFF_BASE_MS = 500L
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
     *
     * v1.4 — FAST + RESILIENT (fixes the loading screen stuck at 10%):
     * - the 3 category lists are fetched IN PARALLEL (async), not one by one;
     * - streams are fetched per category IN PARALLEL (max 6 concurrent),
     *   not one category after another;
     * - every call is retried twice on network errors (exponential backoff);
     * - ONE failing category is SKIPPED (counted, load goes on) instead of
     *   aborting the whole load; the load only fails when ALL THREE
     *   category lists fail (server/auth problem -> error screen + retry);
     * - an overall timeout (3 min) guarantees the load always ends
     *   (success or clear error), never hangs forever.
     * Progress callbacks run on Dispatchers.IO — callers must post them
     * to the Main thread before touching Views.
     */
    suspend fun loadAll(progress: (step: String, percent: Int, message: String) -> Unit) {
        withContext(Dispatchers.IO) {
            requireCredentials()

            withTimeout(LOAD_ALL_TIMEOUT_MS) {
                progress(STEP_CATEGORIES, 2, "Chargement des catégories…")
                val liveDeferred = async { runCatching { retryIo { api.getLiveCategories() } } }
                val vodDeferred = async { runCatching { retryIo { api.getVodCategories() } } }
                val seriesDeferred = async { runCatching { retryIo { api.getSeriesCategories() } } }
                val liveCategories = liveDeferred.await().getOrDefault(emptyList())
                val vodCategories = vodDeferred.await().getOrDefault(emptyList())
                val seriesCategories = seriesDeferred.await().getOrDefault(emptyList())
                if (liveCategories.isEmpty() && vodCategories.isEmpty() && seriesCategories.isEmpty()) {
                    throw IOException("Échec du chargement des catégories")
                }
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

                // Live channels (10% -> 45%): parallel, max 6 concurrent.
                progress(STEP_LIVE, 12, "Chargement des chaînes…")
                val liveCount = fetchStreamsParallel(
                    items = liveCategories,
                    fromPercent = 12,
                    toPercent = 45,
                    step = STEP_LIVE,
                    label = "chaînes",
                    progress = progress,
                ) { category ->
                    api.getLiveStreams(category.id).also { streams ->
                        db.liveChannelDao().replaceForCategory(
                            category.id,
                            streams.map { it.toEntity() },
                        )
                    }.size
                }
                session.setLastSyncMillis("live", System.currentTimeMillis())
                progress(STEP_LIVE, 45, "Chaînes chargées ($liveCount)")

                // VOD (45% -> 70%).
                progress(STEP_VOD, 47, "Chargement des films…")
                val vodCount = fetchStreamsParallel(
                    items = vodCategories,
                    fromPercent = 47,
                    toPercent = 70,
                    step = STEP_VOD,
                    label = "films",
                    progress = progress,
                ) { category ->
                    api.getVodStreams(category.id).also { streams ->
                        db.vodDao().replaceForCategory(category.id, streams.map { it.toEntity() })
                    }.size
                }
                session.setLastSyncMillis("vod", System.currentTimeMillis())
                progress(STEP_VOD, 70, "Films chargés ($vodCount)")

                // Series (70% -> 95%).
                progress(STEP_SERIES, 72, "Chargement des séries…")
                val seriesCount = fetchStreamsParallel(
                    items = seriesCategories,
                    fromPercent = 72,
                    toPercent = 95,
                    step = STEP_SERIES,
                    label = "séries",
                    progress = progress,
                ) { category ->
                    api.getSeries(category.id).also { items ->
                        db.seriesDao().replaceForCategory(category.id, items.map { it.toEntity() })
                    }.size
                }
                session.setLastSyncMillis("series", System.currentTimeMillis())
                progress(STEP_SERIES, 95, "Séries chargées ($seriesCount)")

                progress(STEP_DONE, 100, "Terminé")
            }
        }
    }

    /**
     * Fetches [fetcher] for every category IN PARALLEL (max [MAX_PARALLEL]
     * concurrent requests). One failing category is skipped (returns 0 for
     * it) so a single bad category never blocks the whole load. Progress
     * advances as categories complete, in list order.
     */
    private suspend fun fetchStreamsParallel(
        items: List<Category>,
        fromPercent: Int,
        toPercent: Int,
        step: String,
        label: String,
        progress: (step: String, percent: Int, message: String) -> Unit,
        fetcher: suspend (Category) -> Int,
    ): Int = coroutineScope {
        if (items.isEmpty()) return@coroutineScope 0
        val semaphore = Semaphore(MAX_PARALLEL_STREAMS)
        val jobs = items.map { category ->
            async {
                semaphore.withPermit {
                    runCatching { retryIo { fetcher(category) } }.getOrDefault(0)
                }
            }
        }
        var total = 0
        jobs.forEachIndexed { index, job ->
            total += job.await()
            val pct = fromPercent + (index + 1) * (toPercent - fromPercent) / items.size
            progress(step, pct, "Chargement des $label… (${index + 1}/${items.size})")
        }
        total
    }

    /**
     * Retries a network call up to [RETRY_ATTEMPTS] times on IOException
     * (connect/read timeout, unreachable host) with exponential backoff.
     * Non-IO exceptions (bad JSON, auth) are NOT retried — they fail fast.
     */
    private suspend fun <T> retryIo(times: Int = RETRY_ATTEMPTS, block: suspend () -> T): T {
        var attempt = 0
        var last: IOException? = null
        while (attempt <= times) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
            }
            attempt++
            if (attempt <= times) delay(RETRY_BACKOFF_BASE_MS * attempt)
        }
        throw last ?: IOException("Network call failed")
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
