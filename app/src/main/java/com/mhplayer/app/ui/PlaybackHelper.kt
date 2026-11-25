package com.mhplayer.app.ui

import android.os.Bundle
import com.mhplayer.app.data.api.UrlBuilder
import com.mhplayer.app.data.model.Credentials
import com.mhplayer.app.data.model.Episode
import com.mhplayer.app.data.model.LiveChannel
import com.mhplayer.app.data.model.SeriesItem
import com.mhplayer.app.data.model.VodItem
import com.mhplayer.app.player.Container
import com.mhplayer.app.player.PlayItem

/**
 * Shared helpers that build [PlayItem]s and fragment arguments from the
 * repository models, using the real [UrlBuilder] (never a hard-coded host).
 */
object PlaybackHelper {

    // -- PlayerFragment argument keys -----------------------------------------

    const val ARG_KIND = "kind"               // "live" | "vod" | "episode"
    const val ARG_STREAM_ID = "stream_id"
    const val ARG_TITLE = "title"
    const val ARG_EXT = "ext"
    const val ARG_IS_LIVE = "is_live"
    const val ARG_CHANNEL_ID = "channel_id"
    const val ARG_IS_HBBTV = "is_hbbtv"

    // -- DetailsFragment argument keys ----------------------------------------

    const val ARG_DETAILS_KIND = "details_kind" // "vod" | "series" | "live"
    const val ARG_ID = "id"
    const val ARG_COVER = "cover"
    const val ARG_YEAR = "year"
    const val ARG_GENRE = "genre"
    const val ARG_PLOT = "plot"
    const val ARG_SEASONS = "seasons"

    fun liveArgs(channel: LiveChannel): Bundle = Bundle().apply {
        putString(ARG_KIND, "live")
        putInt(ARG_STREAM_ID, channel.streamId)
        putString(ARG_TITLE, channel.name)
        putString(ARG_EXT, "m3u8")
        putBoolean(ARG_IS_LIVE, true)
        putString(ARG_CHANNEL_ID, channel.streamId.toString())
        putBoolean(ARG_IS_HBBTV, channel.isHbbtv)
    }

    fun vodArgs(vod: VodItem): Bundle = Bundle().apply {
        putString(ARG_KIND, "vod")
        putInt(ARG_STREAM_ID, vod.streamId)
        putString(ARG_TITLE, vod.name)
        putString(ARG_EXT, vod.containerExtension ?: "mp4")
        putBoolean(ARG_IS_LIVE, false)
        putString(ARG_CHANNEL_ID, vod.streamId.toString())
    }

    fun episodeArgs(series: SeriesItem, episode: Episode): Bundle = Bundle().apply {
        putString(ARG_KIND, "episode")
        putInt(ARG_STREAM_ID, episode.id)
        putString(ARG_TITLE, "${series.name} — ${episodeTitle(episode)}")
        putString(ARG_EXT, episode.containerExtension.ifBlank { "mp4" })
        putBoolean(ARG_IS_LIVE, false)
        putString(ARG_CHANNEL_ID, episode.id.toString())
    }

    fun episodeTitle(episode: Episode): String =
        if (episode.title.isBlank()) "Episode ${episode.episodeNum}" else episode.title

    fun vodDetailsArgs(vod: VodItem): Bundle = Bundle().apply {
        putString(ARG_DETAILS_KIND, "vod")
        putInt(ARG_ID, vod.streamId)
        putString(ARG_TITLE, vod.name)
        putString(ARG_COVER, vod.icon)
        putInt(ARG_YEAR, vod.year ?: 0)
        putString(ARG_GENRE, vod.genre ?: "")
        putString(ARG_PLOT, vod.plot ?: "")
        putInt(ARG_SEASONS, 0)
        putString(ARG_EXT, vod.containerExtension ?: "mp4")
    }

    fun seriesDetailsArgs(series: SeriesItem, seasonCount: Int = 0): Bundle = Bundle().apply {
        putString(ARG_DETAILS_KIND, "series")
        putInt(ARG_ID, series.seriesId)
        putString(ARG_TITLE, series.name)
        putString(ARG_COVER, series.cover)
        putInt(ARG_YEAR, series.year ?: 0)
        putString(ARG_GENRE, series.genre ?: "")
        putString(ARG_PLOT, series.plot ?: "")
        putInt(ARG_SEASONS, seasonCount)
    }

    fun liveDetailsArgs(channel: LiveChannel): Bundle = Bundle().apply {
        putString(ARG_DETAILS_KIND, "live")
        putInt(ARG_ID, channel.streamId)
        putString(ARG_TITLE, channel.name)
        putString(ARG_COVER, channel.icon)
        putInt(ARG_YEAR, 0)
        putString(ARG_GENRE, "")
        putString(ARG_PLOT, "")
        putInt(ARG_SEASONS, 0)
        putBoolean(ARG_IS_HBBTV, channel.isHbbtv)
    }

    // -- PlayItem builders ------------------------------------------------------

    fun livePlayItem(creds: Credentials, channel: LiveChannel, output: String = "m3u8"): PlayItem =
        PlayItem(
            url = UrlBuilder.liveUrl(creds, channel.streamId, output),
            title = channel.name,
            isLive = true,
            container = if (output == "ts") Container.TS else Container.M3U8,
            channelId = channel.streamId.toString(),
            isVod = false,
        )

    /** Rebuilds the live URL for the wanted container (DualEnginePlayer failover provider). */
    fun liveUrlProvider(creds: Credentials, streamId: Int): (Container) -> String =
        { container ->
            UrlBuilder.liveUrl(
                creds,
                streamId,
                if (container == Container.TS) "ts" else "m3u8",
            )
        }

    fun vodPlayItem(creds: Credentials, vod: VodItem): PlayItem =
        PlayItem(
            url = UrlBuilder.vodUrl(creds, vod.streamId, vod.containerExtension ?: "mp4"),
            title = vod.name,
            isLive = false,
            container = Container.M3U8,
            channelId = vod.streamId.toString(),
            isVod = true,
        )

    fun episodePlayItem(creds: Credentials, series: SeriesItem, episode: Episode): PlayItem =
        PlayItem(
            url = UrlBuilder.seriesUrl(creds, episode.id, episode.containerExtension.ifBlank { "mp4" }),
            title = "${series.name} — ${episodeTitle(episode)}",
            isLive = false,
            container = Container.M3U8,
            channelId = episode.id.toString(),
            isVod = true,
        )

    /** Formats milliseconds as "14:32" or "1:45:00". */
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
