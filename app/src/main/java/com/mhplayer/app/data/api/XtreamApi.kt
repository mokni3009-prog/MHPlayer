package com.mhplayer.app.data.api

import com.mhplayer.app.data.model.AuthResult
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.CategoryDto
import com.mhplayer.app.data.model.Credentials
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
        getList<LiveStreamDto, LiveChannel>("get_live_streams", mapOf("category_id" to categoryId))

    override suspend fun getVodCategories(): List<Category> =
        getList<CategoryDto, Category>("get_vod_categories")

    override suspend fun getVodStreams(categoryId: String): List<VodItem> =
        getList<VodStreamDto, VodItem>("get_vod_streams", mapOf("category_id" to categoryId))

    override suspend fun getSeriesCategories(): List<Category> =
        getList<CategoryDto, Category>("get_series_categories")

    override suspend fun getSeries(categoryId: String): List<SeriesItem> =
        getList<SeriesDto, SeriesItem>("get_series", mapOf("category_id" to categoryId))

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

    // -- Internals ------------------------------------------------------------

    /**
     * Generic list endpoint: decodes the JSON array into DTOs and maps them
     * to domain models. [CategoryDto], [LiveStreamDto], [VodStreamDto] and
     * [SeriesDto] all expose `toModel()`.
     */
    private suspend inline fun <reified D, M> getList(
        action: String,
        extraParams: Map<String, String> = emptyMap(),
    ): List<M> = withContext(Dispatchers.IO) {
        val body = getRaw(action, extraParams)
        if (body.isBlank() || body.trim() == "null") return@withContext emptyList<M>()
        val dtos = json.decodeFromString<List<D>>(body)
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
    ): String = withContext(Dispatchers.IO) {
        val creds = requireCredentials()
        val base = UrlBuilder.normalizeServerUrl(creds.serverUrl)
        val urlBuilder = base.toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .addQueryParameter("action", action)
        extraParams.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        execute(urlBuilder.build().toString())
    }

    /** Executes a GET and returns the response body as a String. Never logs credentials. */
    private fun execute(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = client.newCall(request)
        // Per-call guard on top of the client-level timeouts.
        call.timeout().timeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Xtream API request failed with HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }
}
