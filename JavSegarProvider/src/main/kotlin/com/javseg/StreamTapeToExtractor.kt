package com.javseg

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * Streamtape extractor for the `.to` domain.
 *
 * CloudStream ships StreamTape / StreamTapeNet / StreamTapeXyz, but they bind
 * only to streamtape.com / .net / .xyz. javsegar.com serves embeds on
 * streamtape.to, which therefore matched no extractor at all and produced
 * "no links found".
 *
 * Algorithm mirrors the upstream StreamTape.kt: fetch the embed page, locate
 * the packed script blob, run it through the sandboxed interpreter, then read
 * the media URL that Streamtape writes into the robotlink element.
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

        // Streamtape obfuscates its source inside eval(function(p,a,c,k,e,...)).
        val packed = PACKED.find(html)?.groupValues?.get(1)
        if (packed.isNullOrBlank()) {
            Log.e(TAG, "packed script not found for $id")
            return
        }

        val unpacked = try {
            evalJs(packed, "log", 10.seconds, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "evalJs failed for $id: ${e.message}")
            return
        }

        val unpackedStr = unpacked?.toString().orEmpty()
        val raw = ROBOTLINK.find(unpackedStr)?.groupValues?.getOrNull(1)
            ?: UNESCAPED_URL.find(unpackedStr)?.groupValues?.getOrNull(1)
            ?: ""

        var link = raw.trim().replace("\\/", "/").trim('\'', '"')
        if (link.startsWith("//")) link = "https:$link"
        if (!link.startsWith("http")) {
            Log.e(TAG, "no usable media URL for $id (got '${link.take(60)}')")
            return
        }

        // Relative sources point at this embed's own host, not streamtape.com.
        link = link.replaceFirst("#player*", base).replaceFirst("//stream=1", "&stream=1")

        Log.d(TAG, "resolved $id -> ${link.take(80)}")

        M3u8Helper.generateM3u8(
            name,
            link,
            "$base/",
            headers = mapOf("Referer" to "$base/", "User-Agent" to USER_AGENT)
        ).forEach(callback)
    }

    private fun idFromUrl(url: String): String {
        val m = Regex("""/[efv]/([A-Za-z0-9]+)""").find(url)
        if (m != null) return m.groupValues[1]
        return url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".mp4").substringBefore('?')
    }

    companion object {
        private const val TAG = "StreamTapeTo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        private val PACKED = Regex("""eval\(function\(p,a,c,k,e[^)]*\)\{.*?\}\(\)""", RegexOption.DOT_MATCHES_ALL)
        private val ROBOTLINK = Regex("""robotlink'?\)['"]\)\.innerHTML\s*=\s*['"]([^'"]+)""")
        private val UNESCAPED_URL = Regex("""['"](https?:\\?/\\?/[^'"\\]+\.(?:m3u8|mp4)[^'"]*)['"]""")
    }
}