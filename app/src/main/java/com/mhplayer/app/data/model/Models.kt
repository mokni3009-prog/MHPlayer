package com.mhplayer.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeSerializableValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient serializers: Xtream panels are inconsistent — the same field may
 * arrive as a JSON string ("1", "7.5") or as a raw number, depending on the
 * panel version. These accept both shapes instead of crashing the parse.
 */
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }
}

object FlexibleIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.content.toIntOrNull()
    }
}

object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.content.toLongOrNull()
    }
}

object FlexibleDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }

    override fun deserialize(decoder: Decoder): Double? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
    }
}

object FlexibleBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.booleanOrNull
            ?: when (primitive.content) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
    }
}

// ---------------------------------------------------------------------------
// Domain models (used by UI / player layers)
// ---------------------------------------------------------------------------

@Serializable
data class Credentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val playlistName: String = "",
)

data class Category(
    val id: String,
    val name: String,
)

data class LiveChannel(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val isHbbtv: Boolean = false,
)

data class VodItem(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val rating: Double?,
    val year: Int?,
    val genre: String?,
    val plot: String?,
    val durationSecs: Int?,
    val containerExtension: String?,
)

data class SeriesItem(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val rating: Double?,
    val year: Int?,
    val genre: String?,
    val plot: String?,
)

data class Episode(
    val id: Int,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String,
    val season: Int,
    val info: String?,
)

data class Season(
    val seasonNumber: Int,
    val episodes: List<Episode>,
)

// ---------------------------------------------------------------------------
// Xtream API DTOs (player_api.php, snake_case JSON)
// ---------------------------------------------------------------------------

@Serializable
data class AuthResult(
    @kotlinx.serialization.SerialName("user_info")
    val userInfo: UserInfo? = null,
    @kotlinx.serialization.SerialName("server_info")
    val serverInfo: ServerInfo? = null,
)

@Serializable
data class UserInfo(
    val username: String? = null,
    val password: String? = null,
    val message: String? = null,
    val auth: Int? = null,
    val status: String? = null,
    @kotlinx.serialization.SerialName("exp_date")
    @Serializable(with = FlexibleStringSerializer::class)
    val expDate: String? = null,
    @kotlinx.serialization.SerialName("is_trial")
    @Serializable(with = FlexibleStringSerializer::class)
    val isTrial: String? = null,
    @kotlinx.serialization.SerialName("active_cons")
    @Serializable(with = FlexibleStringSerializer::class)
    val activeCons: String? = null,
    @kotlinx.serialization.SerialName("created_at")
    @Serializable(with = FlexibleStringSerializer::class)
    val createdAt: String? = null,
    @kotlinx.serialization.SerialName("max_connections")
    @Serializable(with = FlexibleStringSerializer::class)
    val maxConnections: String? = null,
    @kotlinx.serialization.SerialName("allowed_output_formats")
    val allowedOutputFormats: List<String> = emptyList(),
)

@Serializable
data class ServerInfo(
    val url: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val port: String? = null,
    @kotlinx.serialization.SerialName("https_port")
    @Serializable(with = FlexibleStringSerializer::class)
    val httpsPort: String? = null,
    @kotlinx.serialization.SerialName("server_protocol")
    val serverProtocol: String? = null,
    @kotlinx.serialization.SerialName("rtmp_port")
    @Serializable(with = FlexibleStringSerializer::class)
    val rtmpPort: String? = null,
    val timezone: String? = null,
    @kotlinx.serialization.SerialName("timestamp_now")
    @Serializable(with = FlexibleLongSerializer::class)
    val timestampNow: Long? = null,
    @kotlinx.serialization.SerialName("time_now")
    val timeNow: String? = null,
)

@Serializable
data class CategoryDto(
    @kotlinx.serialization.SerialName("category_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val categoryId: String? = null,
    @kotlinx.serialization.SerialName("category_name")
    val categoryName: String = "",
    @kotlinx.serialization.SerialName("parent_id")
    @Serializable(with = FlexibleIntSerializer::class)
    val parentId: Int? = null,
) {
    fun toModel(): Category = Category(id = categoryId ?: "", name = categoryName)
}

@Serializable
data class LiveStreamDto(
    @kotlinx.serialization.SerialName("stream_id")
    @Serializable(with = FlexibleIntSerializer::class)
    val streamId: Int? = null,
    val name: String = "",
    @kotlinx.serialization.SerialName("stream_icon")
    val streamIcon: String? = null,
    @kotlinx.serialization.SerialName("category_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val categoryId: String? = null,
    @kotlinx.serialization.SerialName("epg_channel_id")
    val epgChannelId: String? = null,
    @kotlinx.serialization.SerialName("tv_archive")
    @Serializable(with = FlexibleIntSerializer::class)
    val tvArchive: Int? = null,
    @kotlinx.serialization.SerialName("direct_source")
    val directSource: String? = null,
) {
    fun toModel(): LiveChannel {
        val hbbtv = name.contains("hbbtv", ignoreCase = true)
        return LiveChannel(
            streamId = streamId ?: 0,
            name = name,
            icon = streamIcon?.takeIf { it.isNotBlank() },
            categoryId = categoryId ?: "",
            epgChannelId = epgChannelId?.takeIf { it.isNotBlank() },
            isHbbtv = hbbtv,
        )
    }
}

@Serializable
data class VodInfoDto(
    val plot: String? = null,
    val genre: String? = null,
    @kotlinx.serialization.SerialName("releaseDate")
    val releaseDate: String? = null,
    @kotlinx.serialization.SerialName("duration_secs")
    @Serializable(with = FlexibleIntSerializer::class)
    val durationSecs: Int? = null,
    val director: String? = null,
    val cast: String? = null,
    @Serializable(with = FlexibleDoubleSerializer::class)
    val rating: Double? = null,
)

@Serializable
data class VodStreamDto(
    @kotlinx.serialization.SerialName("stream_id")
    @Serializable(with = FlexibleIntSerializer::class)
    val streamId: Int? = null,
    val name: String = "",
    @kotlinx.serialization.SerialName("stream_icon")
    val streamIcon: String? = null,
    @Serializable(with = FlexibleDoubleSerializer::class)
    val rating: Double? = null,
    @kotlinx.serialization.SerialName("category_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val categoryId: String? = null,
    @kotlinx.serialization.SerialName("container_extension")
    val containerExtension: String? = null,
    // Some panels embed these flat; others nest them under "info".
    val genre: String? = null,
    val plot: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val year: Int? = null,
    @kotlinx.serialization.SerialName("duration_secs")
    @Serializable(with = FlexibleIntSerializer::class)
    val durationSecs: Int? = null,
    val info: VodInfoDto? = null,
) {
    fun toModel(): VodItem {
        val releaseYear = year
            ?: info?.releaseDate?.take(4)?.toIntOrNull()
        return VodItem(
            streamId = streamId ?: 0,
            name = name,
            icon = streamIcon?.takeIf { it.isNotBlank() },
            categoryId = categoryId ?: "",
            rating = rating ?: info?.rating,
            year = releaseYear,
            genre = genre ?: info?.genre,
            plot = plot ?: info?.plot,
            durationSecs = durationSecs ?: info?.durationSecs,
            containerExtension = containerExtension,
        )
    }
}

@Serializable
data class SeriesDto(
    @kotlinx.serialization.SerialName("series_id")
    @Serializable(with = FlexibleIntSerializer::class)
    val seriesId: Int? = null,
    val name: String = "",
    val cover: String? = null,
    @kotlinx.serialization.SerialName("category_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val categoryId: String? = null,
    @Serializable(with = FlexibleDoubleSerializer::class)
    val rating: Double? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val year: Int? = null,
    val genre: String? = null,
    val plot: String? = null,
    @kotlinx.serialization.SerialName("last_modified")
    @Serializable(with = FlexibleStringSerializer::class)
    val lastModified: String? = null,
) {
    fun toModel(): SeriesItem = SeriesItem(
        seriesId = seriesId ?: 0,
        name = name,
        cover = cover?.takeIf { it.isNotBlank() },
        categoryId = categoryId ?: "",
        rating = rating,
        year = year,
        genre = genre,
        plot = plot,
    )
}

@Serializable
data class SeriesInfoEpisodeInfoDto(
    val plot: String? = null,
    @kotlinx.serialization.SerialName("duration_secs")
    @Serializable(with = FlexibleIntSerializer::class)
    val durationSecs: Int? = null,
    @kotlinx.serialization.SerialName("movie_image")
    val movieImage: String? = null,
)

@Serializable
data class SeriesInfoEpisodeDto(
    @Serializable(with = FlexibleIntSerializer::class)
    val id: Int? = null,
    @kotlinx.serialization.SerialName("episode_num")
    @Serializable(with = FlexibleIntSerializer::class)
    val episodeNum: Int? = null,
    val title: String = "",
    @kotlinx.serialization.SerialName("container_extension")
    val containerExtension: String = "",
    val info: SeriesInfoEpisodeInfoDto? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val season: Int? = null,
) {
    fun toModel(seasonNumber: Int): Episode = Episode(
        id = id ?: 0,
        episodeNum = episodeNum ?: 0,
        title = title,
        containerExtension = containerExtension,
        season = season ?: seasonNumber,
        info = info?.plot,
    )
}

@Serializable
data class SeriesInfoSeasonDto(
    @kotlinx.serialization.SerialName("season_number")
    @Serializable(with = FlexibleIntSerializer::class)
    val seasonNumber: Int? = null,
    val name: String? = null,
    @kotlinx.serialization.SerialName("episode_count")
    @Serializable(with = FlexibleIntSerializer::class)
    val episodeCount: Int? = null,
)

/**
 * Response of get_series_info: an object with "seasons" and "episodes"
 * (episodes is a JSON object keyed by season number as a string).
 */
@Serializable
data class SeriesInfoDto(
    val seasons: List<SeriesInfoSeasonDto> = emptyList(),
    val episodes: Map<String, List<SeriesInfoEpisodeDto>> = emptyMap(),
    val info: SeriesInfoMetaDto? = null,
) {
    fun toSeasons(): List<Season> {
        val bySeason = episodes.entries.mapNotNull { (key, eps) ->
            val seasonNumber = key.toIntOrNull() ?: return@mapNotNull null
            Season(
                seasonNumber = seasonNumber,
                episodes = eps.map { it.toModel(seasonNumber) }
                    .sortedBy { it.episodeNum },
            )
        }.sortedBy { it.seasonNumber }
        if (bySeason.isNotEmpty()) return bySeason
        // Fallback: panel returned a seasons list but no episodes map.
        return seasons.mapNotNull { dto ->
            val n = dto.seasonNumber ?: return@mapNotNull null
            Season(seasonNumber = n, episodes = emptyList())
        }.sortedBy { it.seasonNumber }
    }
}

@Serializable
data class SeriesInfoMetaDto(
    val name: String? = null,
    val cover: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val year: Int? = null,
    @Serializable(with = FlexibleDoubleSerializer::class)
    val rating: Double? = null,
)
