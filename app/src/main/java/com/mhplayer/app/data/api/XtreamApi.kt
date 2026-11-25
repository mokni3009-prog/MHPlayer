package com.mhplayer.app.data.api

import com.mhplayer.app.data.model.AuthResult
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.CategoryDto
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.data.model.EpgListingDto
import com.mhplayer.app.data.model.EpgProgram
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.LiveStreamDto
import com.mhplayer.app.data.model.Season
import com.mhplayer.app.data.model.SeriesInfoDto
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.SeriesDto
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.data.model.VodStreamDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Xtream Codes API (player_api.php). Retrofit-free: OkHttp + kotlinx.serialization.
 *
 * All calls are suspend functions running on [Dispatchers.IO] with short
 * timeouts (5s connect / 10s read, per ARCHITECTURE.md).
 *
 * Credentials are NEVER logged. Only the action name and HTTP status are
 * ever included in an exception message.
 */
interface XtreamApi {
    suspend fun authenticate(creds: Credentials): AuthResult
    suspend fun getLiveCategories(): List<Category>
    suspend fun getLiveStreams(categoryId: String): List<LiveChannel>
    suspend fun getVodCategories(): List<Category>
    suspend fun getVodStreams(categoryId: String): List<VodItem>
    suspend fun getSeriesCategories(): List<Category>
    suspend fun getSeries(categoryId: String): List<SeriesItem>
    suspend fun getSeriesInfo(seriesId: Int): List<Season>

    /** Short EPG for one live channel (current + next programs). */
    suspend fun getShortEpg(streamId: Int): List<EpgProgram>
}

class XtreamApiImpl(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson(),
    /** Optional live credentials source (e.g. SessionManager). Used when no explicit credentials were set. */
    private val credentialsProvider: (() -> Credentials?)? = null,
) : XtreamApi {

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 10L

        /** Longer read timeout for stream-list calls: one category can hold
         *  thousands of items (multi-MB JSON) and 10 s is too short on a
         *  slow mobile connection (v1.5 — incomplete lists). */
        const val READ_TIMEOUT_STREAMS_SECONDS = 30L
        const val USER_AGENT = "MHPlayer/1.0 (Android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    /** Credentials set by [authenticate] (or [setCredentials]); used by all other calls. */
    @Volatile
    private var credentials: Credentials? = null

    /** Client with longer timeouts for stream-list calls (v1.5). It shares
     *  the connection pool of the app client, so no extra connections. */
    private val longClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_STREAMS_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_STREAMS_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun setCredentials(creds: Credentials) {
        credentials = creds
    }

    fun clearCredentials() {
        credentials = null
    }

    private fun requireCredentials(): Credentials =
        credentials ?: credentialsProvider?.invoke()
            ?: throw IllegalStateException("XtreamApi: no credentials set — call authenticate() first")

    // -- Auth ----------------------------------------------------------------

    override suspend fun authenticate(creds: Credentials): AuthResult =
        withContext(Dispatchers.IO) {
            val base = UrlBuilder.normalizeServerUrl(creds.serverUrl)
            val url = base.toHttpUrl().newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", creds.username)
                .addQueryParameter("password", creds.password)
                .build()
            val body = execute(url.toString())
            val result = json.decodeFromString<AuthResult>(body)
            // Only keep credentials for later calls when auth actually succeeded.
            if (result.userInfo?.status == "Active") {
                credentials = creds
            }
            result
        }

    // -- Categories / streams -------------------------------------------------

    override suspend fun getLiveCategories(): List<Category> =
        getList<CategoryDto, Category>("get_live_categories")

    override suspend fun getLiveStreams(categoryId: String): List<LiveChannel> =
        getList<LiveStreamDto, LiveChannel>(
            "get_live_streams",
            mapOf("category_id" to categoryId),
            longTimeout = true,
        )

    override suspend fun getVodCategories(): List<Category> =
        getList<CategoryDto, Category>("get_vod_categories")

    override suspend fun getVodStreams(categoryId: String): List<VodItem> =
        getList<VodStreamDto, VodItem>(
            "get_vod_streams",
            mapOf("category_id" to categoryId),
            longTimeout = true,
        )

    override suspend fun getSeriesCategories(): List<Category> =
        getList<CategoryDto, Category>("get_series_categories")

    override suspend fun getSeries(categoryId: String): List<SeriesItem> =
        getList<SeriesDto, SeriesItem>(
            "get_series",
            mapOf("category_id" to categoryId),
            longTimeout = true,
        )

    override suspend fun getSeriesInfo(seriesId: Int): List<Season> =
        withContext(Dispatchers.IO) {
            val body = getRaw(
                action = "get_series_info",
                extraParams = mapOf("series_id" to seriesId.toString()),
            )
            if (body.isBlank()) return@withContext emptyList()
            val dto = json.decodeFromString<SeriesInfoDto>(body)
            dto.toSeasons()
        }

    /**
     * Short EPG (v1.6): `get_short_epg&stream_id=...` returns an object
     * `{ "epg_listings": [...] }`, not an array, so it cannot use [getList].
     * A panel without EPG (or an error body) yields an empty list — the
     * Live TV screen shows "No program information" instead of crashing.
     */
    override suspend fun getShortEpg(streamId: Int): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val body = getRaw(
                action = "get_short_epg",
                extraParams = mapOf("stream_id" to streamId.toString()),
            )
            if (body.isBlank()) return@withContext emptyList()
            val dto = runCatching { json.decodeFromString<EpgListingDto>(body) }
                .getOrNull() ?: return@withContext emptyList()
            dto.epg_listings.map { it.toModel() }
        }

    // -- Internals ------------------------------------------------------------

    /**
     * Generic list endpoint: decodes the JSON array into DTOs and maps them
     * to domain models. [CategoryDto], [LiveStreamDto], [VodStreamDto] and
     * [SeriesDto] all expose `toModel()`.
     *
     * v1.5 — one malformed item must not kill the whole category: if the
     * whole-array decode fails, decode element-by-element and skip the bad
     * ones. A non-array body (e.g. a panel error object) yields an empty
     * list instead of a crash, so the repository keeps its cached rows.
     */
    private suspend inline fun <reified D, M> getList(
        action: String,
        extraParams: Map<String, String> = emptyMap(),
        longTimeout: Boolean = false,
    ): List<M> = withContext(Dispatchers.IO) {
        val body = getRaw(action, extraParams, longTimeout)
        if (body.isBlank() || body.trim() == "null") return@withContext emptyList<M>()
        val dtos: List<D> = try {
            json.decodeFromString<List<D>>(body)
        } catch (_: Exception) {
            val elements = runCatching { json.decodeFromString<List<JsonElement>>(body) }
                .getOrDefault(emptyList())
            elements.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<D>(element) }.getOrNull()
            }
        }
        @Suppress("UNCHECKED_CAST")
        when (val first = dtos.firstOrNull()) {
            is CategoryDto -> (dtos as List<CategoryDto>).map { it.toModel() } as List<M>
            is LiveStreamDto -> (dtos as List<LiveStreamDto>).map { it.toModel() } as List<M>
            is VodStreamDto -> (dtos as List<VodStreamDto>).map { it.toModel() } as List<M>
            is SeriesDto -> (dtos as List<SeriesDto>).map { it.toModel() } as List<M>
            null -> emptyList()
            else -> throw IllegalStateException("Unsupported DTO type: ${first::class}")
        }
    }

    /** Builds the player_api.php URL for [action] and returns the raw body. */
    private suspend fun getRaw(
        action: String,
        extraParams: Map<String, String> = emptyMap(),
        longTimeout: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val creds = requireCredentials()
        val base = UrlBuilder.normalizeServerUrl(creds.serverUrl)
        val urlBuilder = base.toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .addQueryParameter("action", action)
        extraParams.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        execute(urlBuilder.build().toString(), longTimeout)
    }

    /** Executes a GET and returns the response body as a String. Never logs credentials. */
    private fun execute(url: String, longTimeout: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val okClient = if (longTimeout) longClient else client
        val call = okClient.newCall(request)
        // Per-call guard on top of the client-level timeouts.
        val timeoutSeconds = if (longTimeout) READ_TIMEOUT_STREAMS_SECONDS else READ_TIMEOUT_SECONDS
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Xtream API request failed with HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }
}
