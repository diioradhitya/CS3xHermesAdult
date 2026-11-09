package com.bokepindo13

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * MumuStream (mumu.watch) — embed player used by bokepindo13.motorcycles.
 *
 * Verified live 2026-11-08:
 *   embed page  https://mumu.watch/v/<id>
 *   contains    MASTER_URL = "https://m-cdn.video/hls/<id>/master.m3u8"
 *               LOWEST_RENDITION_URL = ".../480p/playlist.m3u8"
 *
 * The master playlist (m-cdn.video) answers a plain GET with no Referer/Origin
 * or cookies required, so no token dance is needed — the FRAME_URL token on the
 * embed page is only for the click-to-play gate iframe. We just pull the
 * MASTER_URL and hand it to ExoPlayer as hls.
 */
class MumuExtractor : ExtractorApi() {
    override val name = "MumuStream"
    override val mainUrl = "https://mumu.watch"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = mapOf("Referer" to (referer ?: "https://bokepindo13.motorcycles/"))).text
                .replace("\\/", "/")

            // 1) Prefer the declared MASTER_URL (single trusted source).
            val master = Regex("""MASTER_URL\s*=\s*"([^"]+master\.m3u8)""")
                .find(html)?.groupValues?.get(1)

            // 2) Fallback: first absolute master.m3u8 anywhere on the page.
            val fallback = Regex("""https?://[^\s"'\\]+/hls/[^"'\\\s]*master\.m3u8""")
                .find(html)?.value

            val streamUrl = master ?: fallback ?: return

            M3u8Helper.generateM3u8(
                name,
                streamUrl,
                referer = mainUrl,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
