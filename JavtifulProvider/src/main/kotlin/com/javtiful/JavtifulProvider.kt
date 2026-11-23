package com.javtiful

import com.fasterxml.jackson.databind.ObjectMapper
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
        "$mainUrl/id/uncensored/page=" to "Uncensored",
        "$mainUrl/id/reducing-mosaic/page=" to "Reducing Mosaic",
        "$mainUrl/id/shorts" to "Shorts"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isShorts = request.data.contains("/shorts")
        // request.data ends with "/page=" or "?sort=...&page=". Strip the
        // placeholder, then re-attach page=N with the right separator (? or &).
        // Shorts uses cursor-based pagination via its JS API, so it has no
        // stable ?page=N support; only the first page is served here.
        val stripped = request.data
            .removeSuffix("/page=")
            .removeSuffix("&page=")
            .removeSuffix("?page=")
            .removeSuffix("page=")
        val url = if (page <= 1) {
            stripped
        } else if (isShorts) {
            return newHomePageResponse(request.name, emptyList())
        } else {
            val sep = if (stripped.contains("?")) "&" else "?"
            "$stripped${sep}page=$page"
        }
        val doc = app.get(url, headers = baseHeaders).document
        val items = if (isShorts) doc.parseShortsList() else doc.parseVideoList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = baseHeaders).document
        return doc.parseVideoList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.selectFirst("video#front-player")?.attr("poster")
            ?.let { p ->
                when {
                    p.isBlank() -> null
                    p.startsWith("http") -> p
                    else -> "$mainUrl$p"
                }
            }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { p ->
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

        val durationMin = durationSec?.let { it / 60 }
        val durationText = durationSec?.takeIf { it > 0 }?.let { sec ->
            val mm = sec / 60
            val ss = sec % 60
            "Durasi: $mm menit $ss detik ($mm:${ss.toString().padStart(2, '0')})"
        }
        val plotWithDuration = when {
            durationText != null && !plot.isNullOrBlank() -> "$durationText\n$plot"
            durationText != null -> durationText
            else -> plot
        }

        // Ekstrak video ID dari URL: /id/video/116220/slug
        val videoId = url.substringAfter("/id/video/").substringBefore("/").takeWhile { it.isDigit() }
        if (videoId.isBlank()) return null

        // Ambil watch-config dari halaman embed untuk playback endpoint & ticket
        val embedUrl = "$mainUrl/embed/$videoId"
        val embedDoc = app.get(embedUrl, headers = baseHeaders).document
        val configScript = embedDoc.selectFirst("script#watch-config") ?: return null
        val configJson = configScript.data().ifBlank { configScript.html() }.ifBlank { return null }
        val config = shortsMapper.readTree(configJson)
        val playback = config.path("playback") ?: return null
        val endpoint = playback.path("endpoint").asText().ifBlank { null } ?: return null
        val ticket = playback.path("ticket").asText().ifBlank { null } ?: return null

        val fullEndpoint = if (endpoint.startsWith("http")) endpoint else "$mainUrl$endpoint"
        val apiUrl = fetchPlaybackSources(fullEndpoint, ticket)
        if (apiUrl.isNullOrBlank()) return null

        val recommended = doc.parseVideoList()

        return newMovieLoadResponse(title, url, TvType.NSFW, apiUrl) {
            this.posterUrl = poster
            this.plot = plotWithDuration
            this.tags = allTags
            this.duration = durationMin
            this.recommendations = recommended
        }
    }

    private suspend fun fetchPlaybackSources(endpoint: String, ticket: String): String? {
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )
        var currentTicket = ticket
        var renewed = false
        var result: String? = null
        for (attempt in 0 until 2) {
            if (result != null) break
            val postBody = "ticket=${URLEncoder.encode(currentTicket, "UTF-8")}"
            val bodyRes = app.post(endpoint, headers, postBody)
            val respBody = bodyRes.body?.string() ?: continue
            try {
                val node = shortsMapper.readTree(respBody)
                if (node.path("success").asBoolean(false) && node.path("sources").isArray) {
                    result = node.path("sources").mapNotNull { s ->
                        val src = s.path("src").asText().ifBlank { null } ?: return@mapNotNull null
                        val size = s.path("size").asInt(720)
                        "${src}|${size}"
                    }.joinToString(",,,")
                    break
                }
                // 409 + renewTicket -> coba sekali lagi dengan ticket baru
                if (!renewed && node.path("renewTicket").isTextual) {
                    currentTicket = node.path("renewTicket").asText()
                    renewed = true
                    continue
                }
                continue
            } catch (e: Exception) {
                continue
            }
        }
        return result
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
        return this.select("article.video-card:not(.video-card--partner)").mapNotNull { card ->
            val anchor = card.selectFirst("a.video-card__thumbnail") ?: return@mapNotNull null
            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            val title = card.selectFirst("a.video-card__title")?.text()?.trim()
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

            val details = card.selectFirst(".video-card__details")
            val quality = details?.selectFirst("strong")?.text()?.trim()
            val durationStr = details?.ownText()?.trim()
            val duration = durationStr?.let { parseDurationString(it) }

            newMovieSearchResponse(title, fullUrl, TvType.NSFW) {
                this.posterUrl = poster
                // quality & duration not available in SearchResponse
            }
        }
    }

    private val shortsMapper = ObjectMapper()

    private fun org.jsoup.nodes.Document.parseShortsList(): List<SearchResponse> {
        val script = this.selectFirst("script#shorts-bootstrap") ?: return emptyList()
        val jsonText = script.data().ifBlank { script.html() }.ifBlank { return emptyList() }
        return try {
            val root = shortsMapper.readTree(jsonText)
            val items = root.path("feed").path("items")
            if (!items.isArray) return emptyList()
            items.mapNotNull { item ->
                val url = item.path("url").asText().ifBlank { null } ?: return@mapNotNull null
                val caption = item.path("caption").asText().ifBlank { null } ?: return@mapNotNull null
                val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
                val poster = item.path("poster").asText().let { p ->
                    when {
                        p.isBlank() -> null
                        p.startsWith("http") -> p
                        else -> "$mainUrl$p"
                    }
                }
                newMovieSearchResponse(caption, fullUrl, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            emptyList()
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