package com.javstory

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class JavStoryProvider : MainAPI() {
    override var mainUrl = "https://javstory1.com"
    override var name = "JavStory1"
    override var lang = "id"
    override var hasMainPage = true
    override val hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/indosub/" to "Indo Subtitle",
        "$mainUrl/category/engsub/" to "English Subtitle",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a.ct-media-container") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = selectFirst("h1.entry-title a")?.text() ?: a.attr("aria-label") ?: return null
        val img = a.selectFirst("img")
            ?.attr("data-src")
            ?.ifBlank { a.selectFirst("img")?.attr("src") }
            ?: ""
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = img
        }
    }

    private fun org.jsoup.nodes.Document.extractServerUrls(): List<String> {
        return select(".server-button").mapNotNull { btn ->
            val direct = btn.attr("data-stream-url")
            val base = btn.attr("data-stream-base")
            val id = btn.attr("data-stream-id")
            when {
                direct.isNotBlank() -> direct
                base.isNotBlank() && id.isNotBlank() -> base + id.reversed()
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.entry-card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}/"
        val document = app.get(url).document
        return document.select("article.entry-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title()
            .replace(" - JAVStory1", "")
            .trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val tags = document.select("a[href*=/tag/]").mapNotNull { it.text().trim().ifBlank { null } }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
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
        val document = app.get(data).document
        val serverUrls = document.extractServerUrls()
        var found = false
        for (url in serverUrls) {
            found = loadExtractor(url, data, subtitleCallback, callback) || found
        }
        return found
    }
}