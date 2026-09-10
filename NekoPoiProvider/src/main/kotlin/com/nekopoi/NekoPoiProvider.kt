package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class NekoPoiProvider : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "NekoPoi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/2d-animation/" to "2D Animation",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/jav-cosplay/" to "JAV Cosplay"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }
        val doc = Jsoup.connect(url).userAgent(userAgent).get()
        val items = doc.select("div.nk-post-card, div.nk-hentai-grid ul li, div.result ul li, article").mapNotNull { it ->
            val titleEl = it.selectFirst("h2, h3, .entry-title a, .nk-post-title a")
            val title = titleEl?.text()?.trim() ?: return@mapNotNull null
            val linkEl = it.selectFirst("a[href]")
            val href = linkEl?.attr("abs:href") ?: return@mapNotNull null
            val posterEl = it.selectFirst("img[data-src], img[src]")
            val poster = posterEl?.attr("abs:data-src") ?: posterEl?.attr("abs:src")
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}&post_type=anime"
        val doc = Jsoup.connect(url).userAgent(userAgent).get()
        return doc.select("div.nk-post-card, div.nk-hentai-grid ul li, div.result ul li").mapNotNull { it ->
            val titleEl = it.selectFirst("h2, h3, .entry-title a, .nk-post-title a")
            val title = titleEl?.text()?.trim() ?: return@mapNotNull null
            val linkEl = it.selectFirst("a[href]")
            val href = linkEl?.attr("abs:href") ?: return@mapNotNull null
            val posterEl = it.selectFirst("img[data-src], img[src]")
            val poster = posterEl?.attr("abs:data-src") ?: posterEl?.attr("abs:src")
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = Jsoup.connect(url).userAgent(userAgent).get()
        val title = doc.selectFirst("h1.entry-title, h1, .entry-title")?.text()?.trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?: doc.selectFirst("img[src]")?.attr("abs:src")
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()?.takeIf { it.isNotBlank() }
        val genres = doc.select(".tags-links a, .tagcloud a, .post-tags a, .genre").map { it.text().trim() }
        // Episodes
        val episodes = doc.select("ul.episodelist li a, div.episodelist a, div.nk-episode-card a").mapNotNull { el ->
            val href = el.attr("abs:href")
            val name = el.text().trim()
            if (href.isNotBlank() && name.isNotBlank()) {
                newEpisode(href) { this.name = name }
            } else null
        }
        if (title == null || title.isBlank()) return null
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            if (genres.isNotEmpty()) this.tags = genres.take(5)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.connect(data).userAgent(userAgent).get()
        // Look for iframes
        val iframes = doc.select("iframe[src]")
        if (iframes.isNotEmpty()) {
            for (iframe in iframes) {
                val src = iframe.attr("abs:src")
                if (src.isNotBlank()) {
                    // Use CloudStream's built-in extractor for the iframe src
                    loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                        callback.invoke(link)
                    }
                }
            }
            return true
        }
        // If no iframe, try to look for video tag directly
        val video = doc.selectFirst("video[src], source[src]")
        if (video != null) {
            val src = video.attr("abs:src")
            if (src.isNotBlank()) {
                loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                    callback.invoke(link)
                }
                return true
            }
        }
        return false
    }
}