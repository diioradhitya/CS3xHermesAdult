package com.javseg

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

/**
 * Streamtape extractor for the `.to` domain.
 *
 * CloudStream ships StreamTape / StreamTapeNet / StreamTapeXyz, but they bind
 * only to streamtape.com / .net / .xyz. javsegar.com serves embeds on
 * streamtape.to, which therefore matched no extractor at all and produced
 * "no links found".
 *
 * Flow, verified live against l4yRKvqVXqF7qOX on 2026-10-06:
 *   1. GET /e/<id> -> the embed page.
 *   2. Streamtape no longer uses a packed "eval(function(p,a,c,k,e" script. It
 *      hides the URL with substring obfuscation in an inline script:
 *        document.getElementById('botlink').innerHTML =
 *            '//str' + ('eamtape.to/get_video?...').substring(4);
 *      The static <span id="botlink"> text in the HTML is a DECOY whose token
 *      differs, and <span id="robotlink"> is a trap that shifts the file id by
 *      one character (l4yRK... -> dl4yRK...). Only the rebuilt value is valid,
 *      which is why the v7 evalJs-based matcher never fired.
 *   3. GET that URL + "&stream=1" answers 302 to
 *      https://<node>.tapecontent.net/radosgw/<id>/<signed>/<title>.mp4
 *      — a direct MP4. The Referer must stay on the embed page, otherwise
 *      Streamtape rejects the token.
 */
class StreamTapeToExtractor : ExtractorApi() {
    override val name = "StreamTapeTo"
    override val mainUrl = "https://streamtape.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: runCatching { URI(referer ?: "").host }.getOrNull()
            ?: "streamtape.to"
        val base = "https://$host"
        val refererPage = "$base${pathOf(url)}"

        val id = idFromUrl(url)
        if (id.isBlank()) {
            Log.e(TAG, "cannot parse id from $url")
            return
        }

        val html = try {
            app.get("$base/e/$id", referer = "$base/").document.outerHtml()
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed for $url: ${e.message}")
            return
        }

        if (html.contains("Video not found", ignoreCase = true)) {
            // Streamtape removed the upload; nothing any extractor can do.
            Log.e(TAG, "upstream video is gone ($id)")
            return
        }

        val media = resolveMediaUrl(html) ?: run {
            Log.e(TAG, "could not rebuild the get_video URL for $id")
            return
        }

        Log.d(TAG, "resolved $id -> ${media.take(70)}...")

        // CloudStream's signature is POSITIONAL:
        //   newExtractorLink(source, name, url, type, initializer)
        // Passing the media URL as `source` leaves `url` holding the embed
        // HTML page, the player gets markup instead of a stream and reports
        // "encoding error 3002". `source` must be the extractor label.
        val link = newExtractorLink(
            name,
            name,
            media,
            ExtractorLinkType.M3U8,
        ) {
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to refererPage,
            )
        }
        callback.invoke(link)
    }

    /**
     * Rebuilds the signed get_video URL. Handles the current substring
     * obfuscation first and keeps the older literal assignment as a fallback.
     */
    private fun resolveMediaUrl(html: String): String? {
        val qs = SUBSTRING_ASSIGN.find(html)?.let { m ->
            val prefix = m.groupValues[1]
            val fragment = m.groupValues[2]
            val offset = m.groupValues[3].toIntOrNull() ?: 0
            if (offset !in 0..fragment.length) return@let null
            prefix + fragment.substring(offset)
        } ?: LITERAL_ASSIGN.find(html)?.let { m ->
            m.groupValues[1].replace("\\/", "/")
        } ?: return null

        val queryAt = qs.indexOf("get_video?")
        if (queryAt < 0) return null
        val query = qs.substring(queryAt + "get_video?".length)
            .substringBefore('"')
            .substringBefore('\'')
            .substringBefore('`')
            .trim()

        return "https://streamtape.to/get_video?$query&stream=1"
    }

    private fun pathOf(url: String): String {
        val rest = url.substringAfter("://").substringAfter('/', "")
        return if (rest.isBlank()) "/" else "/$rest"
    }

    private fun idFromUrl(url: String): String {
        val m = Regex("""/[efv]/([A-Za-z0-9]+)""").find(url)
        if (m != null) return m.groupValues[1]
        return url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".mp4")
    }

    companion object {
        private const val TAG = "StreamTapeTo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        private val SUBSTRING_ASSIGN = Regex(
            """getElementById\('botlink'\)\.innerHTML\s*=\s*'([^']*)'\s*\+\s*\('([^']*)'\)\.substring\((\d+)\)"""
        )
        private val LITERAL_ASSIGN = Regex(
            """getElementById\('botlink'\)\.innerHTML\s*=\s*["']([^"']*get_video\?[^"']*)["']"""
        )
    }
}