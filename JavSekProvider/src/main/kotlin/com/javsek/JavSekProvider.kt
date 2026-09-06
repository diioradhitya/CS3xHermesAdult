package com.javsek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JavSekProvider : MainAPI() {
    override var mainUrl = "https://javsek.net"
    override var name = "JavSek"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest",
        "$mainUrl/category/jav-sub-indo" to "JAV SUB INDO",
        "$mainUrl/category/english-sub" to "ENGLISH SUB",
        "$mainUrl/category/jav" to "JAV",
        "$mainUrl/category/amateur" to "AMATEUR",
        "$mainUrl/category/chinese-porn-streaming" to "CHINESE",
        "$mainUrl/category/jav-reducing-mosaic-decensored-streaming-and-download" to "REDUCING MOSAIC"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return newHomePageResponse(request.name, doc.parseVideoList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/page/1/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return doc.parseVideoList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select(".tags-links a, .tagcloud a, .post-tags a").map { it.text().trim() }
        val duration = doc.selectFirst(".gmr-format, .video-duration")?.text()?.trim() ?: ""

        // Get server tabs
        val playerTabs = doc.select("#dropdown-container .player-tabs li a")
        val serverUrlMap = mutableListOf<Pair<String, String>>()
        for (tab in playerTabs) {
            val href = tab.attr("href")
            val name = tab.text()
            if (href.isNotBlank()) {
                serverUrlMap.add(name to href)
            }
        }

        // Store servers as JSON in data
        val serverJson = if (serverUrlMap.isNotEmpty()) {
            serverUrlMap.joinToString("|||") { "${it.first}|${it.second}" }
        } else {
            "Server 1|$url"
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, data = serverJson) {
            this.posterUrl = poster
            this.plot = description
            if (tags.isNotEmpty()) this.tags = tags.take(5)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = data.split("|||").map { part ->
            val pieces = part.split("|", limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else "Server" to part
        }

        for ((serverName, serverUrl) in servers) {
            try {
                val doc = app.get(serverUrl, headers = mapOf("User-Agent" to userAgent)).document

                // Find iframe src
                val iframe = doc.selectFirst("iframe[src]")
                val embedUrl = iframe?.attr("src")?.trim() ?: continue

                val resolvedUrl = if (embedUrl.startsWith("http")) embedUrl else "https:$embedUrl"

                // Call loadExtractor via CloudStream helper
                loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (e: Exception) {
                // Skip failed servers
            }
        }
        return true
    }

    private fun org.jsoup.nodes.Document.parseVideoList(): List<SearchResponse> {
        return this.select("article.post").mapNotNull { article ->
            val thumbLink = article.selectFirst("a.post-thumbnail") ?: return@mapNotNull null
            val titleEl = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null

            val href = thumbLink.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val title = titleEl.attr("title").ifBlank { titleEl.text() }
            val poster = thumbLink.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }
}