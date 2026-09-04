package com.podjav

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class PodJavProvider : MainAPI() {
    override var mainUrl = "https://podjav.tv"
    override var name = "PodJav"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page=" to "Latest",
        "$mainUrl/genre/big-tits/page=" to "Big Tits",
        "$mainUrl/genre/uncensored/page=" to "Uncensored",
        "$mainUrl/genre/married-woman/page=" to "Married Woman",
        "$mainUrl/genre/mature-woman/page=" to "Mature Woman",
        "$mainUrl/genre/cuckold/page=" to "Cuckold",
        "$mainUrl/genre/solowork/page=" to "Solowork",
        "$mainUrl/genre/amateur/page=" to "Amateur"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("/page=") else "${request.data}$page"
        val document = app.get(url, headers = headers).document
        val home = document.select("a.video-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document
        return document.select("a.video-card").mapNotNull { it.toSearchResult() }
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val title = selectFirst(".card-title")?.text()?.trim() ?: attr("data-title")?.trim() ?: return null
        val posterUrl = selectFirst("img.thumb")?.attr("src")
        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Title"

        val poster = document.selectFirst("video#podjavPlayer")?.attr("data-poster")
            ?: document.selectFirst("img[src*=cdn.podjav.tv]")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("section[class*=synopsis] span")
            ?.text()?.trim()
            ?: document.selectFirst(".synopsis")
            ?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val genreEls = document.select("a[href*='/genre/']")
        val tags = genreEls.map { it.text().trim() }.filter { it.isNotBlank() }

        val houseEls = document.select("a[href*='/cast/']")
        val actorList = houseEls.mapNotNull { el ->
            val n = el.text().trim()
            if (n.isBlank() || n.equals("All", true)) null else ActorData(Actor(n))
        }

        val yearStr = document.select("a[href*='/release/']").text()
        val yearInt = Regex("""\d{4}""").find(yearStr)?.value?.toIntOrNull()

        val recommended = document.select("a.video-card").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actorList
            this.year = yearInt
            this.recommendations = recommended
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data, headers = headers).document

        val video = document.selectFirst("video#podjavPlayer")
        val sourcesJson = video?.attr("data-sources")
        if (!sourcesJson.isNullOrBlank()) {
            val urls = Regex("""https?[^"\\]+\.(?:m3u8|mp4)[^"\\]*""")
                .findAll(sourcesJson)
                .map { it.value.replace("\\/", "/") }
                .toList()
            urls.forEach { streamUrl ->
                launch(Dispatchers.IO) {
                    try {
                        if (streamUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                name,
                                streamUrl,
                                mainUrl,
                                headers = mapOf(
                                    "Referer" to mainUrl,
                                    "User-Agent" to headers.getValue("User-Agent")
                                )
                            ).forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(
                                    name,
                                    name,
                                    streamUrl,
                                    ExtractorLinkType.VIDEO
                                ) { this.referer = mainUrl }
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        return@coroutineScope true
    }
}
