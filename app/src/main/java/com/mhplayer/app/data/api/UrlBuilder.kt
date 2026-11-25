package com.mhplayer.app.data.api

import com.mhplayer.app.data.model.Credentials
import java.net.URLEncoder

/**
 * Builds Xtream stream URLs from the server URL + credentials.
 * Never hard-codes a host: the base always comes from [Credentials.serverUrl]
 * (or from server_info.url returned by the auth call).
 */
object UrlBuilder {

    /**
     * Normalizes a raw server URL: trims whitespace, drops trailing slashes,
     * and prepends "http://" when no scheme is present.
     */
    internal fun normalizeServerUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (!base.contains("://")) {
            base = "http://$base"
        }
        return base
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Live stream URL: /live/<user>/<pass>/<streamId>.m3u8 | .ts
     * @param output "m3u8" or "ts" (extension without the dot).
     */
    fun liveUrl(creds: Credentials, streamId: Int, output: String): String {
        val base = normalizeServerUrl(creds.serverUrl)
        val ext = output.trimStart('.').lowercase()
        return "$base/live/${encode(creds.username)}/${encode(creds.password)}/$streamId.$ext"
    }

    /** VOD URL: /movie/<user>/<pass>/<streamId>.<ext> */
    fun vodUrl(creds: Credentials, streamId: Int, ext: String): String {
        val base = normalizeServerUrl(creds.serverUrl)
        val cleanExt = ext.trimStart('.').lowercase().ifBlank { "mp4" }
        return "$base/movie/${encode(creds.username)}/${encode(creds.password)}/$streamId.$cleanExt"
    }

    /** Series episode URL: /series/<user>/<pass>/<streamId>.<ext> */
    fun seriesUrl(creds: Credentials, streamId: Int, ext: String): String {
        val base = normalizeServerUrl(creds.serverUrl)
        val cleanExt = ext.trimStart('.').lowercase().ifBlank { "mp4" }
        return "$base/series/${encode(creds.username)}/${encode(creds.password)}/$streamId.$cleanExt"
    }
}
