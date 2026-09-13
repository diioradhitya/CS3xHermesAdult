package com.javhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

class JavHdProvider : MainAPI() {
    override var mainUrl = "https://javhd.com"
    override var name = "JavHD"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/en/japanese-porn-videos/justadded/all/page=" to "Latest",
        "$mainUrl/en/japanese-porn-videos/popular/all/page=" to "Popular",
        "$mainUrl/en/japanese-porn-videos/toprated/all/page=" to "Top Rated",
        "$mainUrl/en/japanese-porn-videos-long/justadded/all/page=" to "Long",
        "$mainUrl/en/sex-categories/uncensored/page=" to "Uncensored",
        "$mainUrl/en/sex-categories/premium/page=" to "Premium"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.removeSuffix("page=")
        val items = fetchListing(baseUrl, page)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/?s=$query"
        val document = app.get(url, headers = headers).document
        return parseDocumentResults(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.video-thumbnail img, video")?.attr("poster")

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("div.video-description p, .synopsis")?.text()?.trim()

        val durationStr = document.selectFirst("div.video-duration, span.duration")?.text()
        val durationMs = parseDurationToMs(durationStr)

        val tags = document.select("a[href*='/sex-categories/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = document.select("a[href*='/model/']")
            .mapNotNull { el ->
                val n = el.text().trim()
                if (n.isNotBlank()) ActorData(Actor(n)) else null
            }

        val recommended = document.select("div.related-videos a, a.thumb")
            .mapNotNull { el ->
                val href = el.attr("href")
                val t = el.attr("title")?.trim() ?: el.selectFirst("img")?.attr("alt")?.trim() ?: ""
                val img = el.selectFirst("img")?.attr("src")
                if (href.isNotBlank() && t.isNotBlank()) {
                    newMovieSearchResponse(t, fixUrl(href), TvType.NSFW) { this.posterUrl = img }
                } else null
            }.take(10)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = durationMs
            this.tags = tags
            this.actors = actors
            this.recommendations = recommended
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val videoId = Regex("""/en/id/(\d+)/""").find(data)?.groupValues?.get(1)
        if (videoId == null) {
            return@coroutineScope false
        }

        val apiUrl = "$mainUrl/en/player_api?videoId=$videoId"
        val response = app.get(apiUrl, headers = headers)
        if (!response.isSuccessful) return@coroutineScope false

        val body = response.body?.string() ?: return@coroutineScope false
        val json = JsonParser.parseString(body).asJsonObject
        val dataObj = json.getAsJsonObject("data") ?: return@coroutineScope false
        val videosObj = dataObj.getAsJsonObject("videos") ?: return@coroutineScope false

        val qualities = mapOf(
            "_sh" to "1080p",
            "_hq" to "720p",
            "_med" to "480p",
            "_low" to "240p"
        )

        qualities.forEach { (key, label) ->
            val videoUrl = videosObj.get(key)?.asString
            if (!videoUrl.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name $label",
                            videoUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        return@coroutineScope true
    }

    /**
     * Fetch listing via POST (XHR) for category/genre pages.
     * JavHD returns JSON {template, results_count, pagination_params} on POST with XHR header.
     */
    private suspend fun fetchListing(baseUrl: String, page: Int): List<SearchResponse> {
        val pageUrl = "${baseUrl.trimEnd('/')}$page"
        val document = app.get(pageUrl, headers = headers).document
        var items = parseDocumentResults(document)

        if (items.isEmpty()) {
            // Try POST with XHR header
            try {
                val postUrl = pageUrl.removeSuffix("/$page")
                val postHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                val response = app.post(postUrl, headers = postHeaders, data = emptyMap())
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank() && body.trimStart().startsWith("{")) {
                        val json = JsonParser.parseString(body).asJsonObject
                        val template = json.get("template")?.asString
                        if (!template.isNullOrBlank()) {
                            val doc = Jsoup.parseBodyFragment(template)
                            items = parseDocumentResults(doc)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        return items
    }

    /**
     * Parse search results from a Jsoup document.
     * Tries thumb-component first, then generic selectors.
     */
    private fun parseDocumentResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Try thumb-component (from XHR template)
        val thumbComponents = document.select("thumb-component")
        thumbComponents.forEach { el ->
            val linkContent = el.attr("link-content")
            val title = el.attr("title")?.trim() ?: ""
            val urlThumb = el.attr("url-thumb")
            if (linkContent.isNotBlank() && title.isNotBlank()) {
                val href = if (linkContent.startsWith("http")) linkContent else fixUrl(linkContent)
                results.add(newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = if (urlThumb.startsWith("http")) urlThumb else fixUrl(urlThumb)
                })
            }
        }
        if (results.isNotEmpty()) return results

        // Fallback: generic selectors for SSR HTML
        document.select("a.thumb, a[href*='/en/id/'], div.video-item a, article a").forEach { el ->
            val href = el.attr("href")
            val title = el.attr("title")?.trim()
                ?: el.selectFirst("img")?.attr("alt")?.trim()
                ?: el.text().trim()
            val poster = el.selectFirst("img")?.attr("src")
            if (href.isNotBlank() && title.isNotBlank()) {
                results.add(newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                    this.posterUrl = poster
                })
            }
        }

        return results.distinctBy { it.url }
    }

    private fun parseDurationToMs(str: String?): Int? {
        if (str.isNullOrBlank()) return null
        val parts = str.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> null
        }
    }
}
