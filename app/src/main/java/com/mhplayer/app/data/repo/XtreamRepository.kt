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
import kotlinx.coroutines.CancellationException
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
         *  clear error) within 10 minutes, never hangs forever (v1.4: 3 min;
         *  v1.5: raised for very large providers — thousands of items). */
        const val LOAD_ALL_TIMEOUT_MS = 600_000L
        /** Max concurrent per-category stream requests (v1.4 speed). */
        const val MAX_PARALLEL_STREAMS = 6
        /** Network retries per call, with exponential backoff (v1.4). */
        const val RETRY_ATTEMPTS = 2
        const val RETRY_BACKOFF_BASE_MS = 500L
        /** Second pass over failed categories (v1.5): extra attempts,
         *  sequential, with a longer backoff, before a category is
         *  declared failed. */
        const val RETRY_PASS_ATTEMPTS = 2
        const val RETRY_PASS_BACKOFF_MS = 1000L
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
     * - an overall timeout guarantees the load always ends (success or
     *   clear error), never hangs forever.
     *
     * v1.5 — COMPLETE LISTS (fixes the incomplete Xtream lists):
     * - a category that fails the parallel pass is RETRIED (sequential,
     *   longer backoff, 2 more attempts) instead of being silently
     *   skipped — a failed category no longer loses its content quietly;
     * - if a category still fails after the retry pass, the load FAILS
     *   with a clear error naming the type and the number of categories,
     *   instead of reporting a fake "Terminé";
     * - a failed/empty category list NEVER wipes the cached categories
     *   (v1.4 replaced them with an empty list); an empty stream response
     *   NEVER wipes the cached rows of that category — the cache is kept
     *   and the next refresh re-fetches it;
     * - the overall timeout is 10 min (was 3) for very large providers.
     * Progress callbacks run on Dispatchers.IO — callers must post them
     * to the Main thread before touching Views.
     */
    suspend fun loadAll(progress: (step: String, percent: Int, message: String) -> Unit) {
        withContext(Dispatchers.IO) {
            requireCredentials()

            withTimeout(LOAD_ALL_TIMEOUT_MS) {
                progress(STEP_CATEGORIES, 2, "Chargement des catégories…")
                // Category lists in parallel; a failed list gets a second
                // (sequential, longer-backoff) pass before giving up.
                val liveResult = async { fetchCategoryList { api.getLiveCategories() } }
                val vodResult = async { fetchCategoryList { api.getVodCategories() } }
                val seriesResult = async { fetchCategoryList { api.getSeriesCategories() } }
                val liveCatResult = liveResult.await()
                val vodCatResult = vodResult.await()
                val seriesCatResult = seriesResult.await()
                val liveCategories = liveCatResult.getOrDefault(emptyList())
                val vodCategories = vodCatResult.getOrDefault(emptyList())
                val seriesCategories = seriesCatResult.getOrDefault(emptyList())
                // Only replace a type's categories when that type's fetch
                // returned a NON-EMPTY list: an empty/failed fetch keeps
                // the cached categories instead of wiping them (v1.4 bug).
                if (liveCategories.isNotEmpty()) {
                    db.categoryDao().replaceForType(
                        CategoryType.LIVE,
                        liveCategories.map { it.toEntity(CategoryType.LIVE) },
                    )
                }
                if (vodCategories.isNotEmpty()) {
                    db.categoryDao().replaceForType(
                        CategoryType.VOD,
                        vodCategories.map { it.toEntity(CategoryType.VOD) },
                    )
                }
                if (seriesCategories.isNotEmpty()) {
                    db.categoryDao().replaceForType(
                        CategoryType.SERIES,
                        seriesCategories.map { it.toEntity(CategoryType.SERIES) },
                    )
                }
                // A type whose category list still fails after the retry
                // pass is a clear error, not a silent gap.
                val failedTypes = listOfNotNull(
                    if (liveCatResult.isFailure) "chaînes" else null,
                    if (vodCatResult.isFailure) "films" else null,
                    if (seriesCatResult.isFailure) "séries" else null,
                )
                if (failedTypes.isNotEmpty()) {
                    throw IOException(
                        "Échec du chargement des catégories (${failedTypes.joinToString(", ")})",
                    )
                }
                if (liveCategories.isEmpty() && vodCategories.isEmpty() &&
                    seriesCategories.isEmpty() && !hasCachedCatalog()
                ) {
                    throw IOException("Échec du chargement des catégories")
                }
                progress(STEP_CATEGORIES, 10, "Catégories chargées")

                // Live channels (10% -> 45%): parallel, max 6 concurrent.
                progress(STEP_LIVE, 12, "Chargement des chaînes…")
                val liveFetcher: suspend (Category) -> Int = { category ->
                    val streams = api.getLiveStreams(category.id)
                    if (streams.isEmpty()) {
                        // Empty response: never wipe the cached rows (a
                        // transient empty body must not delete content).
                        // Count what's cached; the next refresh re-fetches.
                        db.liveChannelDao().getByCategory(category.id).size
                    } else {
                        db.liveChannelDao().replaceForCategory(
                            category.id,
                            streams.map { it.toEntity() },
                        )
                        streams.size
                    }
                }
                val liveOutcome = fetchStreamsParallel(
                    items = liveCategories,
                    fromPercent = 12,
                    toPercent = 45,
                    step = STEP_LIVE,
                    label = "chaînes",
                    progress = progress,
                    fetcher = liveFetcher,
                )
                val liveCount = liveOutcome.total + retryFailedCategories(
                    failed = liveOutcome.failed,
                    step = STEP_LIVE,
                    label = "chaînes",
                    percent = 45,
                    progress = progress,
                    fetcher = liveFetcher,
                )
                session.setLastSyncMillis("live", System.currentTimeMillis())
                progress(STEP_LIVE, 45, "Chaînes chargées ($liveCount)")

                // VOD (45% -> 70%).
                progress(STEP_VOD, 47, "Chargement des films…")
                val vodFetcher: suspend (Category) -> Int = { category ->
                    val streams = api.getVodStreams(category.id)
                    if (streams.isEmpty()) {
                        db.vodDao().getByCategory(category.id).size
                    } else {
                        db.vodDao().replaceForCategory(category.id, streams.map { it.toEntity() })
                        streams.size
                    }
                }
                val vodOutcome = fetchStreamsParallel(
                    items = vodCategories,
                    fromPercent = 47,
                    toPercent = 70,
                    step = STEP_VOD,
                    label = "films",
                    progress = progress,
                    fetcher = vodFetcher,
                )
                val vodCount = vodOutcome.total + retryFailedCategories(
                    failed = vodOutcome.failed,
                    step = STEP_VOD,
                    label = "films",
                    percent = 70,
                    progress = progress,
                    fetcher = vodFetcher,
                )
                session.setLastSyncMillis("vod", System.currentTimeMillis())
                progress(STEP_VOD, 70, "Films chargés ($vodCount)")

                // Series (70% -> 95%).
                progress(STEP_SERIES, 72, "Chargement des séries…")
                val seriesFetcher: suspend (Category) -> Int = { category ->
                    val items = api.getSeries(category.id)
                    if (items.isEmpty()) {
                        db.seriesDao().getByCategory(category.id).size
                    } else {
                        db.seriesDao().replaceForCategory(category.id, items.map { it.toEntity() })
                        items.size
                    }
                }
                val seriesOutcome = fetchStreamsParallel(
                    items = seriesCategories,
                    fromPercent = 72,
                    toPercent = 95,
                    step = STEP_SERIES,
                    label = "séries",
                    progress = progress,
                    fetcher = seriesFetcher,
                )
                val seriesCount = seriesOutcome.total + retryFailedCategories(
                    failed = seriesOutcome.failed,
                    step = STEP_SERIES,
                    label = "séries",
                    percent = 95,
                    progress = progress,
                    fetcher = seriesFetcher,
                )
                session.setLastSyncMillis("series", System.currentTimeMillis())
                progress(STEP_SERIES, 95, "Séries chargées ($seriesCount)")

                progress(STEP_DONE, 100, "Terminé")
            }
        }
    }

    // -- Local storage / fast start (v1.5) ------------------------------------

    /** True when Room already holds a catalog (categories or streams). */
    suspend fun hasCachedCatalog(): Boolean = withContext(Dispatchers.IO) {
        db.categoryDao().countAll() > 0 ||
            db.liveChannelDao().countAll() > 0 ||
            db.vodDao().countAll() > 0 ||
            db.seriesDao().countAll() > 0
    }

    /**
     * Background refresh (v1.5 fast start): re-loads the full catalog from
     * Xtream (or re-imports the M3U playlist) without blocking the UI and
     * without ever wiping the cache on failure. Only runs when something is
     * stale (> 24 h, or never synced); a fresh cache starts instantly with
     * no network at all. Failures are swallowed — the cached catalog stays
     * and the next start retries — so a flaky panel never blocks the app.
     * The catalog never stays stale forever: every start re-checks the
     * 24 h staleness and refreshes when due.
     */
    suspend fun refreshInBackground() {
        val isXtream = session.getCredentials() != null
        val needsRefresh = if (isXtream) {
            session.isStale("live") || session.isStale("vod") || session.isStale("series")
        } else {
            session.getM3uUrl() != null && session.isStale("live")
        }
        if (!needsRefresh) return
        runCatching {
            if (isXtream) {
                loadAll { _, _, _ -> }
            } else {
                val url = session.getM3uUrl() ?: return@runCatching
                importM3u(url, session.getPlaylistName())
            }
        }
    }

    /**
     * Fetches one category list with the first-pass retries ([retryIo])
     * plus a second pass (2 more attempts, longer backoff) before giving
     * up (v1.5 — a flaky list must not wipe a whole type silently).
     */
    private suspend fun fetchCategoryList(
        fetch: suspend () -> List<Category>,
    ): Result<List<Category>> {
        val first = runCatching { retryIo { fetch() } }
        if (first.isSuccess) return first
        var last: Throwable? = first.exceptionOrNull()
        repeat(RETRY_PASS_ATTEMPTS) { attempt ->
            delay(RETRY_PASS_BACKOFF_MS * (attempt + 1))
            val retry = runCatching { fetch() }
            if (retry.isSuccess) return retry
            last = retry.exceptionOrNull()
        }
        return Result.failure(last ?: IOException("Category fetch failed"))
    }

    /** Result of one parallel stream pass: how many items were loaded, and
     *  which categories failed (they go to the retry pass, v1.5). */
    private data class StreamLoadOutcome(val total: Int, val failed: List<Category>)

    /**
     * Fetches [fetcher] for every category IN PARALLEL (max [MAX_PARALLEL_STREAMS]
     * concurrent requests). A failing category is NOT skipped silently — it
     * is reported in the returned [StreamLoadOutcome.failed] so the caller
     * retries it (v1.5). Progress advances as categories complete, in list
     * order.
     */
    private suspend fun fetchStreamsParallel(
        items: List<Category>,
        fromPercent: Int,
        toPercent: Int,
        step: String,
        label: String,
        progress: (step: String, percent: Int, message: String) -> Unit,
        fetcher: suspend (Category) -> Int,
    ): StreamLoadOutcome = coroutineScope {
        if (items.isEmpty()) return@coroutineScope StreamLoadOutcome(0, emptyList())
        val semaphore = Semaphore(MAX_PARALLEL_STREAMS)
        val jobs = items.map { category ->
            async {
                semaphore.withPermit {
                    runCatching { retryIo { fetcher(category) } }
                }
            }
        }
        var total = 0
        val failed = mutableListOf<Category>()
        jobs.forEachIndexed { index, job ->
            job.await().onSuccess { total += it }.onFailure { failed.add(items[index]) }
            val pct = fromPercent + (index + 1) * (toPercent - fromPercent) / items.size
            progress(step, pct, "Chargement des $label… (${index + 1}/${items.size})")
        }
        StreamLoadOutcome(total, failed)
    }

    /**
     * Second pass over categories that failed the parallel pass: retries
     * them SEQUENTIALLY (one at a time, longer backoff) instead of silently
     * skipping them (v1.5). Returns the extra item count. Throws a clear
     * IOException when a category still fails after the retries, so the
     * load ends with an explicit error instead of a fake "Terminé".
     */
    private suspend fun retryFailedCategories(
        failed: List<Category>,
        step: String,
        label: String,
        percent: Int,
        progress: (step: String, percent: Int, message: String) -> Unit,
        fetcher: suspend (Category) -> Int,
    ): Int {
        if (failed.isEmpty()) return 0
        var total = 0
        val stillFailed = mutableListOf<Category>()
        for ((index, category) in failed.withIndex()) {
            progress(step, percent, "Nouvelle tentative ($label)… (${index + 1}/${failed.size})")
            val result = runCatching {
                retryAny(times = RETRY_PASS_ATTEMPTS, baseDelayMs = RETRY_PASS_BACKOFF_MS) {
                    fetcher(category)
                }
            }
            result.onSuccess { total += it }.onFailure { stillFailed.add(category) }
        }
        if (stillFailed.isNotEmpty()) {
            throw IOException("Échec du chargement de ${stillFailed.size} catégorie(s) ($label)")
        }
        return total
    }

    /**
     * Retries a call up to [times] times on ANY exception (network, decode,
     * panel glitch) with a longer backoff than [retryIo]. Used by the v1.5
     * retry pass over failed categories. [CancellationException] is never
     * swallowed (so the overall timeout still works).
     */
    private suspend fun <T> retryAny(
        times: Int = RETRY_PASS_ATTEMPTS,
        baseDelayMs: Long = RETRY_PASS_BACKOFF_MS,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var last: Throwable? = null
        while (attempt <= times) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
            attempt++
            if (attempt <= times) delay(baseDelayMs * attempt)
        }
        throw last ?: IOException("Network call failed")
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
            // v1.5: an empty response never wipes the cached categories.
            if (fresh.isNotEmpty()) {
                db.categoryDao().replaceForType(
                    CategoryType.LIVE,
                    fresh.map { it.toEntity(CategoryType.LIVE) },
                )
                session.setLastSyncMillis("live", System.currentTimeMillis())
                fresh
            } else {
                cached
            }
        }.getOrDefault(cached)
    }

    suspend fun getLiveStreams(categoryId: String): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            val cached = db.liveChannelDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("live")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getLiveStreams(categoryId)
                // v1.5: an empty response never wipes the cached rows.
                if (fresh.isNotEmpty()) {
                    db.liveChannelDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                    session.setLastSyncMillis("live", System.currentTimeMillis())
                    fresh
                } else {
                    cached
                }
            }.getOrDefault(cached)
        }

    suspend fun getVodCategories(): List<Category> = withContext(Dispatchers.IO) {
        val cached = db.categoryDao().getByType(CategoryType.VOD).map { it.toModel() }
        if (cached.isNotEmpty() && !session.isStale("vod")) return@withContext cached
        if (requireCredentialsOrNull() == null) return@withContext cached
        runCatching {
            val fresh = api.getVodCategories()
            // v1.5: an empty response never wipes the cached categories.
            if (fresh.isNotEmpty()) {
                db.categoryDao().replaceForType(
                    CategoryType.VOD,
                    fresh.map { it.toEntity(CategoryType.VOD) },
                )
                session.setLastSyncMillis("vod", System.currentTimeMillis())
                fresh
            } else {
                cached
            }
        }.getOrDefault(cached)
    }

    suspend fun getVodStreams(categoryId: String): List<VodItem> =
        withContext(Dispatchers.IO) {
            val cached = db.vodDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("vod")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getVodStreams(categoryId)
                // v1.5: an empty response never wipes the cached rows.
                if (fresh.isNotEmpty()) {
                    db.vodDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                    session.setLastSyncMillis("vod", System.currentTimeMillis())
                    fresh
                } else {
                    cached
                }
            }.getOrDefault(cached)
        }

    suspend fun getSeriesCategories(): List<Category> = withContext(Dispatchers.IO) {
        val cached = db.categoryDao().getByType(CategoryType.SERIES).map { it.toModel() }
        if (cached.isNotEmpty() && !session.isStale("series")) return@withContext cached
        if (requireCredentialsOrNull() == null) return@withContext cached
        runCatching {
            val fresh = api.getSeriesCategories()
            // v1.5: an empty response never wipes the cached categories.
            if (fresh.isNotEmpty()) {
                db.categoryDao().replaceForType(
                    CategoryType.SERIES,
                    fresh.map { it.toEntity(CategoryType.SERIES) },
                )
                session.setLastSyncMillis("series", System.currentTimeMillis())
                fresh
            } else {
                cached
            }
        }.getOrDefault(cached)
    }

    suspend fun getSeries(categoryId: String): List<SeriesItem> =
        withContext(Dispatchers.IO) {
            val cached = db.seriesDao().getByCategory(categoryId).map { it.toModel() }
            if (cached.isNotEmpty() && !session.isStale("series")) return@withContext cached
            if (requireCredentialsOrNull() == null) return@withContext cached
            runCatching {
                val fresh = api.getSeries(categoryId)
                // v1.5: an empty response never wipes the cached rows.
                if (fresh.isNotEmpty()) {
                    db.seriesDao().replaceForCategory(categoryId, fresh.map { it.toEntity() })
                    session.setLastSyncMillis("series", System.currentTimeMillis())
                    fresh
                } else {
                    cached
                }
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
            // v1.5: an empty playlist never wipes the cached catalog.
            if (categories.isNotEmpty()) {
                db.categoryDao().replaceForType(
                    CategoryType.LIVE,
                    categories.map { it.toEntity(CategoryType.LIVE) },
                )
            }
            if (channels.isNotEmpty()) {
                db.liveChannelDao().replaceAll(channels.map { it.toEntity() })
            }
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
