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
        "$mainUrl/en/japanese-porn-videos/justadded/all/" to "Just Added",
        "$mainUrl/en/japanese-porn-videos/popular/all/" to "Most Popular",
        "$mainUrl/en/japanese-porn-videos/top/all/" to "Top Rated",
        "$mainUrl/en/japanese-porn-videos-long/justadded/all/" to "Long Videos",

        // ── Top Categories (by scene count, correct slugs from javhd.com) ──
        "$cat/blowjob/justadded/all/" to "Blowjob",
        "$cat/hardcore/justadded/all/" to "Hardcore",
        "$cat/missionary/justadded/all/" to "Missionary",
        "$cat/doggystyle/justadded/all/" to "Doggystyle",
        "$cat/handjob/justadded/all/" to "Handjob",
        "$cat/fingering/justadded/all/" to "Fingering",
        "$cat/riding/justadded/all/" to "Riding",
        "$cat/brunette/justadded/all/" to "Brunette",
        "$cat/small-tits/justadded/all/" to "Small Tits",
        "$cat/lingerie/justadded/all/" to "Lingerie",
        "$cat/hairy-pussy/justadded/all/" to "Hairy Pussy",
        "$cat/big-tits/justadded/all/" to "Big Tits",
        "$cat/creampie/justadded/all/" to "Creampie",
        "$cat/toys/justadded/all/" to "Toys",
        "$cat/shaved-pussy/justadded/all/" to "Shaved Pussy",
        "$cat/cumshot/justadded/all/" to "Cumshot",
        "$cat/natural-tits/justadded/all/" to "Natural Tits",
        "$cat/vibrator/justadded/all/" to "Vibrator",
        "$cat/panties/justadded/all/" to "Panties",
        "$cat/pussy-licking/justadded/all/" to "Pussy Licking",
        "$cat/reverse-cowgirl/justadded/all/" to "Reverse Cowgirl",
        "$cat/cunnilingus/justadded/all/" to "Cunnilingus",
        "$cat/milf/justadded/all/" to "MILF",
        "$cat/amateur/justadded/all/" to "Amateur",
        "$cat/trimmed-pussy/justadded/all/" to "Trimmed Pussy",
        "$cat/pov/justadded/all/" to "POV",
        "$cat/cum-in-mouth/justadded/all/" to "Cum In Mouth",
        "$cat/stockings/justadded/all/" to "Stockings",
        "$cat/standing-sex/justadded/all/" to "Standing Sex",
        "$cat/perfect-ass/justadded/all/" to "Perfect Ass",
        "$cat/tit-fuck/justadded/all/" to "Tit Fuck",
        "$cat/mini-skirt/justadded/all/" to "Mini Skirt",
        "$cat/pov-blowjob/justadded/all/" to "POV Blowjob",
        "$cat/threesome/justadded/all/" to "Threesome",
        "$cat/69/justadded/all/" to "69",
        "$cat/solo/justadded/all/" to "Solo",
        "$cat/facial-cumshot/justadded/all/" to "Facial Cumshot",
        "$cat/masturbation-solo/justadded/all/" to "Masturbation Solo",
        "$cat/asian-teen-18/justadded/all/" to "Asian Teen 18+",
        "$cat/titjob/justadded/all/" to "Titjob",
        "$cat/tattoo/justadded/all/" to "Tattoo",
        "$cat/face-sitting/justadded/all/" to "Face Sitting",
        "$cat/bathroom/justadded/all/" to "Bathroom",
        "$cat/ass-licking/justadded/all/" to "Ass Licking",
        "$cat/blonde/justadded/all/" to "Blonde",
        "$cat/big-butt/justadded/all/" to "Big Butt",
        "$cat/facial/justadded/all/" to "Facial",
        "$cat/big-cock/justadded/all/" to "Big Cock",
        "$cat/redhead/justadded/all/" to "Redhead",
        "$cat/female-orgasm/justadded/all/" to "Female Orgasm",
        "$cat/busty-milf/justadded/all/" to "Busty MILF",
        "$cat/hot-milf/justadded/all/" to "Hot MILF",
        "$cat/fishnet/justadded/all/" to "Fishnet",
        "$cat/high-heels/justadded/all/" to "High Heels",
        "$cat/foot-fetish/justadded/all/" to "Foot Fetish",
        "$cat/socks/justadded/all/" to "Socks",
        "$cat/bikini/justadded/all/" to "Bikini",
        "$cat/lesbian/justadded/all/" to "Lesbian",
        "$cat/amateur-blowjob/justadded/all/" to "Amateur Blowjob",
        "$cat/fetish/justadded/all/" to "Fetish",
        "$cat/transgender/justadded/all/" to "Transgender",
        "$cat/bdsm/justadded/all/" to "BDSM",
        "$cat/outdoor/justadded/all/" to "Outdoor",
        "$cat/cum-on-tits/justadded/all/" to "Cum On Tits",
        "$cat/squirting/justadded/all/" to "Squirting",
        "$cat/gang-bang/justadded/all/" to "Gang Bang",
        "$cat/cosplay/justadded/all/" to "Cosplay",
        "$cat/double-penetration/justadded/all/" to "Double Penetration",
        "$cat/bondage/justadded/all/" to "Bondage",
        "$cat/squirt/justadded/all/" to "Squirt",
        "$cat/school/justadded/all/" to "School",
        "$cat/interracial/justadded/all/" to "Interracial",
        "$cat/office-lady/justadded/all/" to "Office Lady",
        "$cat/piercing/justadded/all/" to "Piercing",
        "$cat/glasses/justadded/all/" to "Glasses",
        "$cat/secretary/justadded/all/" to "Secretary",
        "$cat/mature/justadded/all/" to "Mature",
        "$cat/maid/justadded/all/" to "Maid",
        "$cat/nurse/justadded/all/" to "Nurse",
        "$cat/pool/justadded/all/" to "Pool",
        "$cat/bukkake/justadded/all/" to "Bukkake",
        "$cat/sport/justadded/all/" to "Sport",
        "$cat/fisting/justadded/all/" to "Fisting",
        "$cat/uncensored/justadded/all/" to "Uncensored",
        "$cat/pregnant/justadded/all/" to "Pregnant",
        "$cat/uniform/justadded/all/" to "Uniform",
        "$cat/upskirt/justadded/all/" to "Upskirt",
        "$cat/latex/justadded/all/" to "Latex",
        "$cat/webcam/justadded/all/" to "Webcam",
        "$cat/strip/justadded/all/" to "Strip",
        "$cat/student/justadded/all/" to "Student",
        "$cat/prison/justadded/all/" to "Prison",
        "$cat/swimsuit/justadded/all/" to "Swimsuit",
        "$cat/medical/justadded/all/" to "Medical",
        "$cat/mistress/justadded/all/" to "Mistress",
        "$cat/beach/justadded/all/" to "Beach",
        "$cat/teacher/justadded/all/" to "Teacher",
        "$cat/party/justadded/all/" to "Party",
        "$cat/yoga/justadded/all/" to "Yoga",
        "$cat/reality/justadded/all/" to "Reality",
        "$cat/bisexual/justadded/all/" to "Bisexual",
        "$cat/sensual/justadded/all/" to "Sensual",
        "$cat/softcore/justadded/all/" to "Softcore",
        "$cat/spreading/justadded/all/" to "Spreading",
        "$cat/naughty/justadded/all/" to "Naughty",
        "$cat/beauty/justadded/all/" to "Beauty",
        "$cat/wet/justadded/all/" to "Wet",
        "$cat/tall/justadded/all/" to "Tall",
        "$cat/wife/justadded/all/" to "Wife",
        "$cat/pegging/justadded/all/" to "Pegging",
        "$cat/princess/justadded/all/" to "Princess",
        "$cat/whip/justadded/all/" to "Whip",
        "$cat/wrestling/justadded/all/" to "Wrestling",
        "$cat/hospital/justadded/all/" to "Hospital",
        "$cat/sauna/justadded/all/" to "Sauna",
        "$cat/kissing/justadded/all/" to "Kissing",
        "$cat/sucking/justadded/all/" to "Sucking",
        "$cat/swallowing/justadded/all/" to "Swallowing",
        "$cat/vagina/justadded/all/" to "Vagina",
        "$cat/nipples/justadded/all/" to "Nipples",
        "$cat/nude/justadded/all/" to "Nude",
        "$cat/pornstar/justadded/all/" to "Pornstar",
        "$cat/adultery/justadded/all/" to "Adultery",
        "$cat/adorable/justadded/all/" to "Adorable"
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

        // Call player_api — returns JSON with "sources" array
        val apiUrl = "$mainUrl/en/player_api?videoId=$videoId"
        val response = app.get(apiUrl, headers = headers)
        if (!response.isSuccessful) return@coroutineScope false

        val body = response.body?.string() ?: return@coroutineScope false
        val json = JsonParser.parseString(body).asJsonObject

        // New API format: { sources: [{ label, res, src, type }, ...] }
        val sourcesArray = json.getAsJsonArray("sources")
        if (sourcesArray == null) {
            // Fallback: old format { data: { videos: { _sh, _hq, ... } } }
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

        // Parse new sources array format
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

    /**
     * Fetch listing via POST (XHR) for category/sort pages.
     * JavHD returns JSON {template, results_count, pagination_params} on POST with XHR header.
     * Adds ?content=all to include uncensored content.
     */
    private suspend fun fetchListing(url: String): List<SearchResponse> {
        // Add ?content=all for uncensored content
        val contentUrl = if (url.contains("content=all")) {
            url
        } else if (url.contains("?")) {
            "$url&content=all"
        } else {
            "$url?content=all"
        }

        val postHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest")

        // Try POST with XHR header first (returns JSON with template)
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

        // Fallback: GET SSR HTML
        try {
            val document = app.get(contentUrl, headers = headers).document
            return parseDocumentResults(document)
        } catch (_: Exception) { }

        return emptyList()
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
