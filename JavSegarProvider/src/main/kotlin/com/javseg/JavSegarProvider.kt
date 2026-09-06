package com.javseg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
class JavSegarProvider : MainAPI() {
    override var mainUrl = "https://javsegar.com"
    override var name = "JavSegar"
    override var lang = "id"
    override var hasMainPage = true
    override val hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/bokep-jepang/" to "Bokep Jepang",
    )

    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title") ?: selectFirst("span")?.text() ?: return null
        val thumb = selectFirst("img.video-main-thumb")?.attr("src")
            ?: selectFirst("img")?.attr("data-main-thumb") ?: ""
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = thumb
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.thumb-block").mapNotNull { it.toSearch() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.thumb-block").mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.title().replace(" - JavSegar", "").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val duration = doc.selectFirst("meta[itemprop=duration]")?.attr("content") ?: ""
        val tags = doc.select("a[href*=/tags/]").mapNotNull { it.text().trim().ifBlank { null } }

        val iframeUrl = doc.selectFirst("div.video-player iframe")?.attr("src") ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeUrl.ifBlank { url }) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return when {
            // ystream.id embed: handle with PoW-protected extractor
            data.contains("ystream.id") -> {
                YstreamExtractor().getUrl(data, data, subtitleCallback, callback)
                true
            }
            else -> loadExtractor(data, data, subtitleCallback, callback)
        }
    }
}