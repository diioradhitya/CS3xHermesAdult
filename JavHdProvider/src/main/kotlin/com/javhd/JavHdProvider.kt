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

    private val cat = "$mainUrl/en/japanese-porn-videos/category"

    override val mainPage = mainPageOf(
        // ── Main Sort Pages ──
        "$mainUrl/en/japanese-porn-videos/justadded/all/page=" to "Just Added",
        "$mainUrl/en/japanese-porn-videos/popular/all/page=" to "Most Popular",
        "$mainUrl/en/japanese-porn-videos/toprated/all/page=" to "Top Rated",
        "$mainUrl/en/japanese-porn-videos-long/justadded/all/page=" to "Long Videos",
        "$mainUrl/en/sex-categories/uncensored/page=" to "Uncensored",
        "$mainUrl/en/sex-categories/premium/page=" to "Premium",

        // ── Categories (justadded sort) ──
        "$cat/adorable/justadded/all/page=" to "Adorable",
        "$cat/adultery/justadded/all/page=" to "Adultery",
        "$cat/amateur/justadded/all/page=" to "Amateur",
        "$cat/anal/justadded/all/page=" to "Anal",
        "$cat/asian/justadded/all/page=" to "Asian",
        "$cat/bdsm/justadded/all/page=" to "BDSM",
        "$cat/beach/justadded/all/page=" to "Beach",
        "$cat/beauty/justadded/all/page=" to "Beauty",
        "$cat/bikini/justadded/all/page=" to "Bikini",
        "$cat/bisexual/justadded/all/page=" to "Bisexual",
        "$cat/blowjob/justadded/all/page=" to "Blowjob",
        "$cat/bondage/justadded/all/page=" to "Bondage",
        "$cat/bukkake/justadded/all/page=" to "Bukkake",
        "$cat/busty/justadded/all/page=" to "Busty",
        "$cat/cosplay/justadded/all/page=" to "Cosplay",
        "$cat/creampie/justadded/all/page=" to "Creampie",
        "$cat/cumswapping/justadded/all/page=" to "Cum Swapping",
        "$cat/doggystyle/justadded/all/page=" to "Doggystyle",
        "$cat/facial/justadded/all/page=" to "Facial",
        "$cat/gangbang/justadded/all/page=" to "Gangbang",
        "$cat/group/justadded/all/page=" to "Group",
        "$cat/handjob/justadded/all/page=" to "Handjob",
        "$cat/hardcore/justadded/all/page=" to "Hardcore",
        "$cat/hospital/justadded/all/page=" to "Hospital",
        "$cat/interracial/justadded/all/page=" to "Interracial",
        "$cat/kissing/justadded/all/page=" to "Kissing",
        "$cat/latex/justadded/all/page=" to "Latex",
        "$cat/lesbian/justadded/all/page=" to "Lesbian",
        "$cat/lingerie/justadded/all/page=" to "Lingerie",
        "$cat/maid/justadded/all/page=" to "Maid",
        "$cat/massage/justadded/all/page=" to "Massage",
        "$cat/masturbation/justadded/all/page=" to "Masturbation",
        "$cat/mature/justadded/all/page=" to "Mature",
        "$cat/medical/justadded/all/page=" to "Medical",
        "$cat/milf/justadded/all/page=" to "MILF",
        "$cat/miniskirt/justadded/all/page=" to "Miniskirt",
        "$cat/missionary/justadded/all/page=" to "Missionary",
        "$cat/mistress/justadded/all/page=" to "Mistress",
        "$cat/mmf/justadded/all/page=" to "MMF",
        "$cat/natural/justadded/all/page=" to "Natural",
        "$cat/naughty/justadded/all/page=" to "Naughty",
        "$cat/nipples/justadded/all/page=" to "Nipples",
        "$cat/nurse/justadded/all/page=" to "Nurse",
        "$cat/office/justadded/all/page=" to "Office",
        "$cat/outdoor/justadded/all/page=" to "Outdoor",
        "$cat/panties/justadded/all/page=" to "Panties",
        "$cat/party/justadded/all/page=" to "Party",
        "$cat/passionate/justadded/all/page=" to "Passionate",
        "$cat/pegging/justadded/all/page=" to "Pegging",
        "$cat/piercing/justadded/all/page=" to "Piercing",
        "$cat/pool/justadded/all/page=" to "Pool",
        "$cat/pornstar/justadded/all/page=" to "Pornstar",
        "$cat/pov/justadded/all/page=" to "POV",
        "$cat/pregnant/justadded/all/page=" to "Pregnant",
        "$cat/princess/justadded/all/page=" to "Princess",
        "$cat/prison/justadded/all/page=" to "Prison",
        "$cat/public/justadded/all/page=" to "Public",
        "$cat/reality/justadded/all/page=" to "Reality",
        "$cat/redhead/justadded/all/page=" to "Redhead",
        "$cat/riding/justadded/all/page=" to "Riding",
        "$cat/romantic/justadded/all/page=" to "Romantic",
        "$cat/sauna/justadded/all/page=" to "Sauna",
        "$cat/school/justadded/all/page=" to "School",
        "$cat/secretary/justadded/all/page=" to "Secretary",
        "$cat/sensual/justadded/all/page=" to "Sensual",
        "$cat/shaved/justadded/all/page=" to "Shaved",
        "$cat/skinny/justadded/all/page=" to "Skinny",
        "$cat/softcore/justadded/all/page=" to "Softcore",
        "$cat/solo/justadded/all/page=" to "Solo",
        "$cat/spanking/justadded/all/page=" to "Spanking",
        "$cat/squirting/justadded/all/page=" to "Squirting",
        "$cat/stockings/justadded/all/page=" to "Stockings",
        "$cat/strip/justadded/all/page=" to "Strip",
        "$cat/student/justadded/all/page=" to "Student",
        "$cat/submissive/justadded/all/page=" to "Submissive",
        "$cat/sucking/justadded/all/page=" to "Sucking",
        "$cat/swallowing/justadded/all/page=" to "Swallowing",
        "$cat/swimsuit/justadded/all/page=" to "Swimsuit",
        "$cat/tattoo/justadded/all/page=" to "Tattoo",
        "$cat/teacher/justadded/all/page=" to "Teacher",
        "$cat/teen/justadded/all/page=" to "Teen",
        "$cat/threesome/justadded/all/page=" to "Threesome",
        "$cat/toilet/justadded/all/page=" to "Toilet",
        "$cat/toys/justadded/all/page=" to "Toys",
        "$cat/trans/justadded/all/page=" to "Trans",
        "$cat/trimmed/justadded/all/page=" to "Trimmed",
        "$cat/uncensored/justadded/all/page=" to "Uncensored (Cat)",
        "$cat/uniform/justadded/all/page=" to "Uniform",
        "$cat/upskirt/justadded/all/page=" to "Upskirt",
        "$cat/vibrator/justadded/all/page=" to "Vibrator",
        "$cat/webcam/justadded/all/page=" to "Webcam",
        "$cat/wedding/justadded/all/page=" to "Wedding",
        "$cat/wet/justadded/all/page=" to "Wet",
        "$cat/whip/justadded/all/page=" to "Whip",
        "$cat/wife/justadded/all/page=" to "Wife",
        "$cat/wrestling/justadded/all/page=" to "Wrestling",
        "$cat/yoga/justadded/all/page=" to "Yoga"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.removeSuffix("page=")
        val items = fetchListing(baseUrl, page)
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
        // Try to extract videoId from URL first (listing format: /en/id/{id}/)
        var videoId = Regex("""/en/id/(\d+)/""").find(data)?.groupValues?.get(1)

        // If not found, fetch the detail page and extract from content-path attribute
        if (videoId == null) {
            try {
                val document = app.get(data, headers = headers).document
                val contentPath = document.selectFirst("[content-path]")?.attr("content-path") ?: ""
                videoId = Regex("""videoId=(\d+)""").find(contentPath)?.groupValues?.get(1)
            } catch (_: Exception) { }
        }

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
        var items: List<SearchResponse> = emptyList()

        // Try POST with XHR header first (returns JSON with template)
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

        // Fallback: GET SSR HTML
        if (items.isEmpty()) {
            try {
                val document = app.get(pageUrl, headers = headers).document
                items = parseDocumentResults(document)
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
