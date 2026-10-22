package com.anilos

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AnilosProvider - Mature Women of Interest
 *
 * Source: anilos.com
 * - Direct MP4 URLs in video page (multiple qualities: 480p, 640p, 960p, 1280p)
 * - URLs are signed with expiring tokens (st=...&e=timestamp)
 * - Requires Referer header when requesting MP4
 * - Tokens auto-refreshed by fetching video page before playback
 */
class AnilosProvider : MainAPI() {
    override var mainUrl = "https://anilos.com"
    override var name = "Anilos"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val mainPageBase = "$mainUrl/video/gallery"
    private val watchBase = "$mainUrl/video/watch"
    private val galleryBase = "$mainUrl/video/gallery"
    private val categoryBase = "$mainUrl/category/video"

    companion object {
        private const val TAG = "Anilos"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
    }

    override val mainPage = mainPageOf(
        "$mainPageBase" to "Latest",
        "$mainPageBase?orderby=date&order=desc" to "Recent",
        "$mainPageBase?orderby=title&order=asc" to "A - Z",
        "$categoryBase" to "Categories",
        "$mainPageBase?orderby=rand" to "Random",
    )

    private fun pageUrl(base: String, page: Int): String {
        return if (page <= 1) base else "$base/page/$page"
    }

    private suspend fun fetchDocument(url: String, attempts: Int = MAX_RETRIES): org.jsoup.nodes.Document? {
        repeat(attempts) { attempt ->
            try {
                val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
                if (doc.select("h1, .video-wrapper, .video-container, article, .content-grid").isNotEmpty()) {
                    if (attempt > 0) Log.d(TAG, "fetch ok on attempt ${attempt + 1} for $url")
                    return doc
                }
                Log.w(TAG, "fetch attempt ${attempt + 1}/$attempts got empty document for $url")
            } catch (e: Exception) {
                Log.w(TAG, "fetch attempt ${attempt + 1}/$attempts failed for $url: ${e.message}")
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        Log.e(TAG, "fetch gave up after $attempts attempts for $url")
        return null
    }

    private fun Element.toSearch(): SearchResponse? {
        val link = selectFirst("a[href*=/video/watch/]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = link.attr("title").ifBlank {
            selectFirst("img")?.attr("alt")?.ifBlank { link.text() } ?: link.text()
        }.ifBlank { return null }

        val poster = selectFirst("img")?.attr("data-src")?.ifBlank {
            selectFirst("img")?.attr("src")
        }

        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            poster?.let { if (it.startsWith("http")) posterUrl = it }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList())
        // Anilos uses .content-grid-item for video items
        val items = doc.select(".content-grid-item").mapNotNull { it.toSearch() }
        Log.d(TAG, "page $page -> ${items.size} items, ${items.count { !it.posterUrl.isNullOrBlank() }} with poster")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = fetchDocument(url) ?: return emptyList()
        return doc.select(".content-grid-item").mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDocument(url) ?: return null

        val title = doc.selectFirst("h1.entry-title, h1.video-title, h1")?.text()?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".video-wrapper img, .video-container img, .video-player img")?.attr("src")

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".video-description, .entry-content, .post-content")?.text()?.trim()

        val tags = doc.select(".tags a, .tags-links a, .video-tags a, .post-tags a").map { it.text().trim() }.distinct()

        val durationStr = doc.selectFirst(".duration, .video-duration, [itemprop=duration]")?.text()?.trim() ?: ""
        val duration = durationStr.toIntOrNull()

        // Find all available video sources from the page
        val videoSources = mutableMapOf<String, String>()
        val seenUrls = mutableSetOf<String>()

        // Extract from <source> tags
        doc.select("source[src]").forEach { source ->
            val src = source.attr("src").trim()
            val type = source.attr("type").lowercase()
            if (src.startsWith("http") && (type.contains("mp4") || type.contains("video") || src.endsWith(".mp4")) && seenUrls.add(src)) {
                val quality = when {
                    src.contains("1280") || src.contains("_1280") || src.contains("1080") -> "1080p"
                    src.contains("960") || src.contains("_960") -> "960p"
                    src.contains("720") || src.contains("720p") -> "720p"
                    src.contains("640") || src.contains("640p") -> "640p"
                    src.contains("480") || src.contains("480p") -> "480p"
                    else -> "Auto"
                }
                videoSources[quality] = src
            }
        }

        // Extract from data attributes / JSON
        doc.select("[data-video], [data-src], [data-video-url]").forEach { el ->
            val src = (el.attr("data-video") ?: el.attr("data-src") ?: el.attr("data-video-url")).trim()
            if (src.startsWith("http") && seenUrls.add(src)) {
                videoSources["Auto"] = src
            }
        }

        // Find player iframe sources
        doc.select("iframe[src*='player'], iframe[src*='video'], iframe[src*='embed']").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http") && seenUrls.add(src)) {
                videoSources["Iframe"] = src
            }
        }

        // Default to page URL if no sources found
        val data = if (videoSources.isNotEmpty()) {
            videoSources.entries.joinToString("|||") { entry -> "${entry.key}|${entry.value}" }
        } else {
            "Watch|$url"
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = description
            if (tags.isNotEmpty()) this.tags = tags.take(10)
            if (duration != null) this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val servers = data.split("|||").map { part ->
            val pieces = part.split("|", limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else "Watch" to part
        }

        var hasLinks = false

        for ((quality, url) in servers) {
            if (url.isBlank()) continue

            // Handle direct MP4 URLs with token refresh
            if (url.contains("images.anilos.com") && url.endsWith(".mp4")) {
                // Emit with Referer header - tokens are refreshed by re-fetching video page if needed
                val qualityInt = qualityToInt(quality)
                val link = newExtractorLink(
                    source = "Anilos",
                    name = "Anilos $quality",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "Referer" to "https://anilos.com/",
                        "User-Agent" to userAgent
                    )
                    this.quality = qualityInt
                }
                callback.invoke(link)
                hasLinks = true
                Log.d(TAG, "emitted $quality: $url")
            } else if (url.startsWith("http")) {
                // Fallback to generic extractor for iframe/embed URLs
                val collected = mutableListOf<ExtractorLink>()
                val success = loadExtractor(data, url, subtitleCallback, { collected.add(it) })
                if (success && collected.isNotEmpty()) {
                    // Keep only master playlist, drop variants
                    val master = collected.firstOrNull { it.url.contains("master.m3u8") || it.url.contains("master_") }
                    val toEmit = master ?: collected.first()
                    callback(renameSource(toEmit, "Anilos"))
                    hasLinks = true
                }
            }
        }
        return hasLinks
    }

    private fun qualityToInt(quality: String): Int {
        return when (quality.lowercase()) {
            "1080p" -> 1080
            "960p" -> 960
            "720p" -> 720
            "640p" -> 640
            "480p" -> 480
            "360p" -> 360
            "auto" -> Qualities.Unknown.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun renameSource(link: ExtractorLink, sourceName: String): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = link.name,
            url = link.url,
            type = link.type
        ) {
            this.referer = link.referer
            this.headers = link.headers
            this.quality = link.quality
            this.extractorData = link.extractorData
            this.audioTracks = link.audioTracks
        }
    }
}