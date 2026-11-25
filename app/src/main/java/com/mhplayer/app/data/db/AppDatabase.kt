package com.mhplayer.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem

/** Category type discriminator — one table holds live/VOD/series bouquets. */
object CategoryType {
    const val LIVE = "live"
    const val VOD = "vod"
    const val SERIES = "series"
}

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** One of [CategoryType.LIVE] / [CategoryType.VOD] / [CategoryType.SERIES]. */
    val type: String,
)

@Entity(tableName = "live_channels")
data class LiveChannelEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val isHbbtv: Boolean = false,
)

@Entity(tableName = "vod")
data class VodEntity(
    @PrimaryKey val streamId: Int,
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

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesId: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val rating: Double?,
    val year: Int?,
    val genre: String?,
    val plot: String?,
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: Int,
    val seriesId: Int,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String,
    val season: Int,
    val info: String?,
)

/**
 * Per-channel playback memory (DualEnginePlayer): the winning engine /
 * container / HW-decoder choice, the last error and the last success time.
 * Channel id is the Xtream stream id as text (M3U channels use the same
 * text form, so both sources share this table).
 */
@Entity(tableName = "channel_playback")
data class ChannelPlaybackEntity(
    @PrimaryKey val channelId: String,
    val preferredEngine: String,
    val preferredContainer: String,
    val hwDecoder: String,
    val lastError: String?,
    val lastSuccessAt: Long,
)

// ---------------------------------------------------------------------------
// Entity <-> model mapping
// ---------------------------------------------------------------------------

internal fun Category.toEntity(type: String) =
    CategoryEntity(id = id, name = name, type = type)

internal fun CategoryEntity.toModel() = Category(id = id, name = name)

internal fun LiveChannel.toEntity() = LiveChannelEntity(
    streamId = streamId,
    name = name,
    icon = icon,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    isHbbtv = isHbbtv,
)

internal fun LiveChannelEntity.toModel() = LiveChannel(
    streamId = streamId,
    name = name,
    icon = icon,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    isHbbtv = isHbbtv,
)

internal fun VodItem.toEntity() = VodEntity(
    streamId = streamId,
    name = name,
    icon = icon,
    categoryId = categoryId,
    rating = rating,
    year = year,
    genre = genre,
    plot = plot,
    durationSecs = durationSecs,
    containerExtension = containerExtension,
)

internal fun VodEntity.toModel() = VodItem(
    streamId = streamId,
    name = name,
    icon = icon,
    categoryId = categoryId,
    rating = rating,
    year = year,
    genre = genre,
    plot = plot,
    durationSecs = durationSecs,
    containerExtension = containerExtension,
)

internal fun SeriesItem.toEntity() = SeriesEntity(
    seriesId = seriesId,
    name = name,
    cover = cover,
    categoryId = categoryId,
    rating = rating,
    year = year,
    genre = genre,
    plot = plot,
)

internal fun SeriesEntity.toModel() = SeriesItem(
    seriesId = seriesId,
    name = name,
    cover = cover,
    categoryId = categoryId,
    rating = rating,
    year = year,
    genre = genre,
    plot = plot,
)

internal fun Episode.toEntity(seriesId: Int) = EpisodeEntity(
    id = id,
    seriesId = seriesId,
    episodeNum = episodeNum,
    title = title,
    containerExtension = containerExtension,
    season = season,
    info = info,
)

internal fun EpisodeEntity.toModel() = Episode(
    id = id,
    episodeNum = episodeNum,
    title = title,
    containerExtension = containerExtension,
    season = season,
    info = info,
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByType(type: String): List<CategoryEntity>

    /** Row count, for the v1.5 fast-start cache check. */
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM categories")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceForType(type: String, categories: List<CategoryEntity>) {
        deleteByType(type)
        insertAll(categories)
    }
}

@Dao
interface LiveChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<LiveChannelEntity>)

    @Query("SELECT * FROM live_channels WHERE categoryId = :categoryId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByCategory(categoryId: String): List<LiveChannelEntity>

    @Query("SELECT * FROM live_channels ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<LiveChannelEntity>

    /** Row count, for the v1.5 fast-start cache check. */
    @Query("SELECT COUNT(*) FROM live_channels")
    suspend fun countAll(): Int

    @Query("DELETE FROM live_channels WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Query("DELETE FROM live_channels")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceForCategory(categoryId: String, channels: List<LiveChannelEntity>) {
        deleteByCategory(categoryId)
        insertAll(channels)
    }

    @Transaction
    suspend fun replaceAll(channels: List<LiveChannelEntity>) {
        clearAll()
        insertAll(channels)
    }
}

@Dao
interface VodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VodEntity>)

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByCategory(categoryId: String): List<VodEntity>

    @Query("SELECT * FROM vod ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<VodEntity>

    /** Row count, for the v1.5 fast-start cache check. */
    @Query("SELECT COUNT(*) FROM vod")
    suspend fun countAll(): Int

    @Query("DELETE FROM vod WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Query("DELETE FROM vod")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceForCategory(categoryId: String, items: List<VodEntity>) {
        deleteByCategory(categoryId)
        insertAll(items)
    }

    @Transaction
    suspend fun replaceAll(items: List<VodEntity>) {
        clearAll()
        insertAll(items)
    }
}

@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SeriesEntity>)

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByCategory(categoryId: String): List<SeriesEntity>

    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SeriesEntity>

    /** Row count, for the v1.5 fast-start cache check. */
    @Query("SELECT COUNT(*) FROM series")
    suspend fun countAll(): Int

    @Query("DELETE FROM series WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Query("DELETE FROM series")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceForCategory(categoryId: String, items: List<SeriesEntity>) {
        deleteByCategory(categoryId)
        insertAll(items)
    }

    @Transaction
    suspend fun replaceAll(items: List<SeriesEntity>) {
        clearAll()
        insertAll(items)
    }
}

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season ASC, episodeNum ASC")
    suspend fun getBySeries(seriesId: Int): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun deleteBySeries(seriesId: Int)

    @Query("DELETE FROM episodes")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceForSeries(seriesId: Int, episodes: List<EpisodeEntity>) {
        deleteBySeries(seriesId)
        insertAll(episodes)
    }
}

@Dao
interface ChannelPlaybackDao {
    @Query("SELECT * FROM channel_playback WHERE channelId = :channelId")
    suspend fun get(channelId: String): ChannelPlaybackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChannelPlaybackEntity)

    @Update
    suspend fun update(entity: ChannelPlaybackEntity)

    @Query("UPDATE channel_playback SET lastError = :error WHERE channelId = :channelId")
    suspend fun updateLastError(channelId: String, error: String?)

    @Query("UPDATE channel_playback SET lastSuccessAt = :at WHERE channelId = :channelId")
    suspend fun updateLastSuccess(channelId: String, at: Long)

    @Query("DELETE FROM channel_playback")
    suspend fun clearAll()
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [
        CategoryEntity::class,
        LiveChannelEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ChannelPlaybackEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun liveChannelDao(): LiveChannelDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun channelPlaybackDao(): ChannelPlaybackDao

    companion object {
        const val DATABASE_NAME = "mhplayer.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
