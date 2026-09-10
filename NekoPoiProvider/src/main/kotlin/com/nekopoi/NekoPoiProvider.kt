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

    /**
     * Parse one search/card item. Handles both site layouts:
     * 1. Category/search: <li> > <a.nk-search-item> > (.nk-search-thumb[style] + .nk-search-info>h2)
     * 2. Home / recent:   <div.nk-post-card> > (.nk-thumb-crop[style] + .nk-post-meta>h2>a)
     * Posters are inline style background-image, NOT <img>.
     */
    private fun Element.parseCard(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val href = linkEl.attr("abs:href")
        if (href.isBlank()) return null

        val title = selectFirst(".nk-search-info h2, .nk-post-meta h2 a, h2")?.text()?.trim() ?: return null

        val poster = selectFirst(".nk-search-thumb, .nk-thumb-crop, div[style]")?.attr("style")?.let { style ->
            Regex("""url\(['\"]?([^'\"]+?)['\"]?\)""").find(style)?.groupValues?.get(1)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )
        val document = app.get(url, headers = headers).document
        val items = document.select("div.nk-search-results ul li, div.nk-post-card").mapNotNull { it.parseCard() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8") + "&post_type=anime"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )
        val document = app.get(url, headers = headers).document
        return document.select("div.nk-search-results ul li, div.nk-post-card").mapNotNull { it.parseCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )
        val document = app.get(url, headers = headers).document
        // Try multiple selectors for title - ensure we get the main article title
        val title = document.selectFirst("div.nk-article h1, div.nk-post-header h1, h1, h2, .entry-title")?.text()?.trim() ?: return null
        
        // Get description from multiple possible locations
        val description = document.selectFirst(".konten, .entry-content, .nk-entry-content, .summary, .nk-post-body")?.text()?.trim()
        
        // Get poster image - try featured image first, then other selectors
        val poster = document.selectFirst(".nk-featured-img img, .poster img, .nk-poster-img, .thumb img, .entry-content img, .nk-entry-content img, .wp-post-image")?.attr("abs:src")
        
        // Get genres from multiple possible locations
        val genres = document.select(".genre a, .nk-genre a, .tags a, .nk-tags a, .nk-search-info h2, .nk-post-meta h2 a").map { it.text().trim() }.filter { it.isNotBlank() }
        
        // Fallback: try to extract from text containing "Genre :" or "GENRE :"
        val fallbackGenres = if (genres.isEmpty()) {
            val genreText = document.select("p:containsOwn(Genre), p:containsOwn(GENRE)").firstOrNull()?.text()
                ?.replace(Regex("(?i)\\bgenre\\s*:"), "")?.trim()
            genreText?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(5) ?: emptyList()
        } else {
            emptyList()
        }
        val allGenres = (genres + fallbackGenres).distinct().take(5)
        
        // NekoPoi detail page doesn't have episode list; it's a single episode page with multiple player options.
        // We'll store the URL itself as data for loadLinks to reuse.
        return newMovieLoadResponse(title, url, TvType.NSFW, data = url) {
            this.posterUrl = poster
            this.plot = description
            if (allGenres.isNotEmpty()) this.tags = allGenres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to episodeUrl
        )
        val document = app.get(episodeUrl, headers = headers).document
        // Only player iframes (skip ad/discord/widget frames)
        val playerHosts = listOf("playmogo", "streampoi", "dood", "streamruby", "embed", "ystream", "cdn")
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank() && playerHosts.any { src.contains(it, ignoreCase = true) }) {
                // Referer can be the episode URL or mainUrl; we'll use episodeUrl as referer for safety.
                loadExtractor(src, episodeUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}