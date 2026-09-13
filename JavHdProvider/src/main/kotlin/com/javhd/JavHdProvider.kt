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
        "$mainUrl/en/japanese-porn-videos/justadded/all/" to "Just Added",
        "$mainUrl/en/japanese-porn-videos/popular/all/" to "Most Popular",
        "$mainUrl/en/japanese-porn-videos/top/all/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val items = fetchListing(url)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val postHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
        return try {
            val response = app.post("$mainUrl/en/search?q=$query", headers = postHeaders)
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            if (!body.trimStart().startsWith("{")) return emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val template = json.get("template")?.asString ?: return emptyList()
            val doc = Jsoup.parseBodyFragment(template)
            parseDocumentResults(doc)
        } catch (_: Exception) { emptyList() }
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
        var videoId = Regex("""/en/id/(\d+)/""").find(data)?.groupValues?.get(1)

        if (videoId == null) {
            try {
                val document = app.get(data, headers = headers).document
                val contentPath = document.selectFirst("[content-path]")?.attr("content-path") ?: ""
                videoId = Regex("""videoId=(\d+)""").find(contentPath)?.groupValues?.get(1)
            } catch (_: Exception) { }
        }

        if (videoId == null) return@coroutineScope false

        val apiUrl = "$mainUrl/en/player_api?videoId=$videoId"
        val response = app.get(apiUrl, headers = headers)
        if (!response.isSuccessful) return@coroutineScope false

        val body = response.body?.string() ?: return@coroutineScope false
        val json = JsonParser.parseString(body).asJsonObject

        val sourcesArray = json.getAsJsonArray("sources")
        if (sourcesArray == null) {
            val dataObj = json.getAsJsonObject("data")
            val videosObj = dataObj?.getAsJsonObject("videos")
            if (videosObj == null) return@coroutineScope false

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
                            newExtractorLink(name, "$name $label", videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
            return@coroutineScope true
        }

        sourcesArray.forEach { element ->
            val source = element.asJsonObject
            val label = source.get("label")?.asString ?: "Unknown"
            val videoUrl = source.get("src")?.asString
            if (!videoUrl.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    callback(
                        newExtractorLink(name, "$name $label", videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        return@coroutineScope true
    }

    private suspend fun fetchListing(url: String): List<SearchResponse> {
        val contentUrl = if (url.contains("content=all")) {
            url
        } else if (url.contains("?")) {
            "$url&content=all"
        } else {
            "$url?content=all"
        }

        val postHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest")

        try {
            val response = app.post(contentUrl, headers = postHeaders, data = emptyMap())
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank() && body.trimStart().startsWith("{")) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val template = json.get("template")?.asString
                    if (!template.isNullOrBlank()) {
                        val doc = Jsoup.parseBodyFragment(template)
                        return parseDocumentResults(doc)
                    }
                }
            }
        } catch (_: Exception) { }

        try {
            val document = app.get(contentUrl, headers = headers).document
            return parseDocumentResults(document)
        } catch (_: Exception) { }

        return emptyList()
    }

    private fun parseDocumentResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

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
