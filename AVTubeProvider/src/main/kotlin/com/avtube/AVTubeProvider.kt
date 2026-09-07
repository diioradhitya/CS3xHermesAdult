package com.avtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

class AVTubeProvider : MainAPI() {
    override var mainUrl = "https://avtubreal.com"
    override var name = "AVTube"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Authority" to "avtubreal.com",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page=" to "Latest Update",
        "$mainUrl/category/bokep-indo/page=" to "Bokep Indo",
        "$mainUrl/category/bokep-jilbab/page=" to "Bokep Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("/page=") else "${request.data}$page"
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
    ): Boolean = coroutineScope {
        val document = app.get(data, headers = headers).document

        val iframeSrc = document.selectFirst("div.responsive-player iframe, div.video-player iframe")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) return@coroutineScope false

        val morenciusDomains = listOf("morencius.com", "dingtezuni.com", "mivalyo.com", "ryderjet.com", "bingezove.com", "movearnpre.com")
        val fixedUrl = if (morenciusDomains.any { iframeSrc.contains(it) }) {
            iframeSrc.replace(Regex("https?://[^/]+"), "https://morencius.com")
        } else iframeSrc

        launch(Dispatchers.IO) {
            try {
                Morencius().getUrl(fixedUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                kotlin.runCatching { e.printStackTrace() }
            }
        }
        return@coroutineScope true
    }
}