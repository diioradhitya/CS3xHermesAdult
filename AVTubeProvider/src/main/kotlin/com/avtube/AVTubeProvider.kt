package com.avtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element

class AVTubeProvider : MainAPI() {
    override var mainUrl = "https://avtubreal.com"
    override var name = "AVTube"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Update",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-jilbab/" to "Bokep Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = headers).document

        val home = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document

        return document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
        val href = fixUrl(link.attr("href"))
        val posterUrl = attr("data-main-thumb").ifBlank { selectFirst("img")?.attr("src") }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val durationMeta = document.selectFirst("meta[itemprop=duration]")?.attr("content")
        val duration = durationMeta?.let { parseISO8601(it) }

        val tags = document.select("a[rel=category tag], a[rel=tag]").map { it.text() }
            .filter { it.isNotBlank() }.distinct()

        val recommended = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.recommendations = recommended
        }
    }

    private fun parseISO8601(content: String): Int {
        // P0DT0H13M15S
        val h = Regex("(\\d+)H").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val s = Regex("(\\d+)S").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        val iframeSrc = document.selectFirst("div.responsive-player iframe, div.video-player iframe")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) return false

        // The ystream/Byse family rotates its API host (f7hyg4q.org, ystream.id,
        // n1mwq.org, ...). Verified live for embed code emuf2fon3lsy on a phone:
        //   GET /api/videos/<code>/embed/details -> f7hyg4q.org 403, ystream.id 200
        // A hardcoded host therefore returns 403 and loadLinks reports nothing,
        // which is what produced "no links found". Take the host from the iframe
        // we were actually handed - YstreamExtractor derives siteOrigin from it.
        val fixedUrl = iframeSrc

        withContext(Dispatchers.IO) {
            try {
                if (isYstreamFamily(fixedUrl)) {
                    YstreamExtractor().getUrl(fixedUrl, data, subtitleCallback, callback)
                } else if (isMorenciusFamily(fixedUrl)) {
                    Morencius().getUrl(fixedUrl, data, subtitleCallback, callback)
                } else if (!loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                    // Nothing recognised it - try the ystream path anyway, since a
                    // rotated host may not match any static list.
                    YstreamExtractor().getUrl(fixedUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Extractor error for $fixedUrl: ${e.message}", e)
            }
        }
        return true
    }

    /**
     * Byse/ystream embeds always carry a short alphanumeric code on an /e/<code>
     * or /d/<code> path. Match on that shape plus the known host words, so the
     * family is still recognised after the domain rotates.
     */
    private fun isYstreamFamily(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        if (listOf("ystream", "byse", "f7hyg4q", "n1mwq").any { host.contains(it) }) {
            return true
        }
        return Regex("""https?://[^/]+/(?:e|d|v)/[A-Za-z0-9]{6,}""").containsMatchIn(url)
    }

    private fun isMorenciusFamily(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return listOf("morencius", "dingtezuni", "mivalyo", "ryderjet", "bingezove", "movearnpre")
            .any { host.contains(it) }
    }

    companion object {
        private const val TAG = "AVTubeProvider"
    }
}