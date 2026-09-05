package com.javtiful

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JavtifulProvider : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/id/videos"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/id/videos/page=" to "Latest",
        "$mainUrl/id/videos?sort=most_viewed&page=" to "Most Viewed",
        "$mainUrl/id/videos?sort=popular_week&page=" to "Popular Week",
        "$mainUrl/id/censored/page=" to "Censored",
        "$mainUrl/id/uncensored/page=" to "Uncensored"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.removeSuffix("/page=")
        } else {
            "${request.data.removeSuffix("/page=")}?page=$page"
        }
        val doc = app.get(url, headers = baseHeaders).document
        return newHomePageResponse(request.name, doc.parseVideoList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = baseHeaders).document
        return doc.parseVideoList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val video = doc.selectFirst("video#front-player") ?: return null
        val sources = video.select("source").mapNotNull { src ->
            val srcUrl = src.attr("src")
            if (srcUrl.isBlank()) null
            else {
                val size = src.attr("size").ifBlank { "720" }.toIntOrNull() ?: 720
                srcUrl to size
            }
        }
        if (sources.isEmpty()) return null

        val apiUrl = sources.joinToString(",,,") { "${it.first}|${it.second}" }

        val poster = video.attr("poster").let { p ->
            when {
                p.isBlank() -> null
                p.startsWith("http") -> p
                else -> "$mainUrl$p"
            }
        }

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
        val tags = doc.select("a[href*='/id/tag/']").map { it.text().trim() }
        val categories = doc.select("a[href*='/id/category/']").map { it.text().trim() }
        val allTags = (tags + categories).distinct()

        val durationSec = doc.select("script[type='application/ld+json']").firstNotNullOfOrNull { script ->
            try {
                val json = script.data()
                val regex = Regex(""""duration"\s*:\s*"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?"""")
                val m = regex.find(json) ?: return@firstNotNullOfOrNull null
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                val s = m.groupValues[3].toIntOrNull() ?: 0
                h * 3600 + mi * 60 + s
            } catch (e: Exception) { null }
        }

        val recommended = doc.parseVideoList()

        return newMovieLoadResponse(title, url, TvType.NSFW, apiUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = allTags
            this.duration = durationSec
            this.recommendations = recommended
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val items = data.split(",,,").mapNotNull { entry ->
            val parts = entry.trim().split("|")
            if (parts.size >= 2) {
                val url = parts[0]
                val quality = parts[1].toIntOrNull() ?: 720
                url to quality
            } else null
        }
        if (items.isEmpty()) return false

        items.forEach { (url, quality) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${quality}p",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("User-Agent" to userAgent)
                    this.quality = quality
                }
            )
        }
        return true
    }

    private fun org.jsoup.nodes.Document.parseVideoList(): List<SearchResponse> {
        return this.select("article.front-video-card:not(.front-partner-card)").mapNotNull { card ->
            val anchor = card.selectFirst("a.front-video-thumb") ?: return@mapNotNull null
            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            val title = card.selectFirst("a.front-video-title")?.text()?.trim()
                ?: anchor.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = anchor.selectFirst("img")?.let { img ->
                val src = img.attr("data-front-lazy-src").ifBlank { img.attr("src") }
                when {
                    src.isBlank() -> null
                    src.startsWith("http") -> src
                    else -> "$mainUrl$src"
                }
            }

            val quality = card.selectFirst(".front-quality-tag")?.text()?.trim()
            val durationStr = card.selectFirst(".front-duration-tag")?.text()?.trim()
            val duration = durationStr?.let { parseDurationString(it) }

            newMovieSearchResponse(title, fullUrl, TvType.NSFW) {
                this.posterUrl = poster
                // quality & duration not available in SearchResponse
            }
        }
    }

    private fun parseDurationString(text: String): Int? {
        val parts = text.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> null
            }
        } catch (e: Exception) { null }
    }
}