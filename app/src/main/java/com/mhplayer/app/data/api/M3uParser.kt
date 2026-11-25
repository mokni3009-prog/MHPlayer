package com.mhplayer.app.data.api

import com.mhplayer.app.data.model.Category
import com.mhplayer.app.data.model.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Minimal M3U playlist parser.
 *
 * Parses line-by-line with a [BufferedReader] on [Dispatchers.IO]:
 *   #EXTM3U                          (header, ignored)
 *   #EXTINF:-1 tvg-id=".." tvg-logo=".." group-title=".." ,Channel Name
 *   http://.../stream.ts             (URL line that follows the #EXTINF)
 *
 * Robust to missing attributes, missing group-title, blank lines and
 * #EXTINF lines without a following URL (the channel is dropped).
 *
 * Returns categories (one per distinct group-title, in first-seen order)
 * plus the parsed live channels. Channels without a group-title land in
 * the [DEFAULT_GROUP].
 */
object M3uParser {

    const val DEFAULT_GROUP = "Autres"

    private val ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    suspend fun parseM3u(inputStream: InputStream): Pair<List<Category>, List<LiveChannel>> =
        withContext(Dispatchers.IO) {
            val channels = mutableListOf<LiveChannel>()
            val groupOrder = linkedMapOf<String, String>() // group id -> display name

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var pending: PendingChannel? = null

                for (rawLine in reader.lineSequence()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue

                    when {
                        line.startsWith("#EXTM3U") -> {
                            // Playlist header — nothing to do.
                        }
                        line.startsWith("#EXTINF") -> {
                            pending = parseExtInf(line)
                        }
                        line.startsWith("#") -> {
                            // Any other directive/comment — ignore, keep pending.
                        }
                        else -> {
                            // A URL line: completes the pending #EXTINF (if any).
                            val current = pending
                            pending = null
                            if (current != null && isPlausibleStreamUrl(line)) {
                                val group = current.groupTitle.ifBlank { DEFAULT_GROUP }
                                groupOrder.getOrPut(group) { group }
                                channels += LiveChannel(
                                    streamId = current.streamId(line),
                                    name = current.name,
                                    icon = current.logo?.takeIf { it.isNotBlank() },
                                    categoryId = group,
                                    epgChannelId = current.tvgId?.takeIf { it.isNotBlank() },
                                    isHbbtv = current.name.contains("hbbtv", ignoreCase = true) ||
                                        group.contains("hbbtv", ignoreCase = true),
                                )
                            }
                        }
                    }
                }
            }

            val categories = groupOrder.keys.map { Category(id = it, name = it) }
            categories to channels
        }

    private data class PendingChannel(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val groupTitle: String,
    ) {
        /** Stream id: numeric tvg-id when present, otherwise a stable hash of the URL. */
        fun streamId(url: String): Int {
            val numeric = tvgId?.toIntOrNull()
            if (numeric != null) return numeric
            // Negative ids would collide with real Xtream ids; keep the hash positive.
            return url.hashCode() and Int.MAX_VALUE
        }
    }

    private fun parseExtInf(line: String): PendingChannel {
        // Attributes live before the first comma; the display name after it.
        val commaIndex = line.indexOf(',')
        val header = if (commaIndex >= 0) line.substring(0, commaIndex) else line
        val name = if (commaIndex >= 0) {
            line.substring(commaIndex + 1).trim()
        } else {
            ""
        }
        val attrs = ATTRIBUTE_REGEX.findAll(header)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return PendingChannel(
            name = name,
            tvgId = attrs["tvg-id"],
            logo = attrs["tvg-logo"],
            groupTitle = attrs["group-title"]?.trim().orEmpty(),
        )
    }

    private fun isPlausibleStreamUrl(line: String): Boolean =
        line.startsWith("http://") || line.startsWith("https://") ||
            line.startsWith("rtmp://") || line.startsWith("rtsp://")
}
