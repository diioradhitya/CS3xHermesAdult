package com.anilos

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnilosProvider : MainAPI() {
    override var mainUrl = "https://anilos.com"
    override var name = "Anilos"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    companion object {
        private const val TAG = "Anilos"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
    }

    override val mainPage = mainPageOf(
        "https://anilos.com/video/recent" to "Recent",
        "https://anilos.com/video/toprated" to "Top Rated",
        "https://anilos.com/video/trending" to "Trending",
        "https://anilos.com/video/alpha" to "A - Z",
        "https://anilos.com/video/random" to "Random"
    )

    private fun pageUrl(base: String, page: Int): String = if (page <= 1) base else "$base/page/$page"

    private suspend fun fetchDocument(url: String, attempts: Int = 3): org.jsoup.nodes.Document? {
        repeat(3) { attempt ->
            try {
                val doc = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Cache-Control" to "max-age=0",
                    "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )).document
                val html = doc.html()
                val isChallenge = html.contains("challenge") || html.contains("cf-mitigated") || html.contains("cf-challenge") || html.contains("Security Check") || html.contains("Just a moment")
                if (isChallenge) {
                    android.util.Log.w("Anilos", "Cloudflare challenge detected for $url (attempt ${attempt + 1})")
                    if (attempt >= 2) return null
                    delay(2000L)
                } else if (doc.select("h1, .video-wrapper, .video-container, article, .content-grid, .content-grid-item").isNotEmpty()) {
                    return doc
                } else {
                    android.util.Log.w("Anilos", "fetch attempt ${attempt + 1}/3 got empty/invalid document for $url")
                }
            } catch (e: Exception) {
                android.util.Log.w("Anilos", "fetch attempt ${attempt + 1}/3 failed for $url: ${e.message}")
            }
            delay(1000L * (attempt + 1))
        }
        android.util.Log.e("Anilos", "fetch gave up after 3 attempts for $url")
        return null
    }

    private fun Element.toSearch(): SearchResponse? {
        val link = selectFirst("a[href*=/video/watch/]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = link.attr("title").ifBlank { selectFirst("img")?.attr("alt")?.ifBlank { link.text() } ?: link.text() }.ifBlank { return null }
        val poster = selectFirst("img")?.attr("data-src")?.ifBlank { selectFirst("img")?.attr("src") }
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) { poster?.let { if (it.startsWith("http")) posterUrl = it } }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val doc = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList())
        val items = doc.select(".content-grid-item").mapNotNull { it.toSearch() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://anilos.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = fetchDocument(url) ?: return emptyList()
        return doc.select(".content-grid-item").mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDocument(url) ?: return null
        val title = doc.selectFirst("h1.entry-title, h1.video-title, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst(".video-wrapper img, .video-container img, .video-player img")?.attr("src")
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: doc.selectFirst(".video-description, .entry-content, .post-content")?.text()?.trim()
        val videoTags = doc.select(".tags a, .tags-links a, .video-tags a, .post-tags a").map { it.text().trim() }.distinct()
        val duration = doc.selectFirst(".duration, .video-duration, [itemprop=duration]")?.text()?.trim()?.toIntOrNull()

        val videoSources = mutableMapOf<String, String>()
        doc.select("source[src]").forEach { source ->
            val src = source.attr("src").trim()
            val type = source.attr("type").lowercase()
            if (src.startsWith("http") && (type.contains("mp4") || type.contains("video") || src.endsWith(".mp4"))) {
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
        doc.select("[data-video], [data-src], [data-video-url]").forEach { el ->
            val src = (el.attr("data-video") ?: el.attr("data-src") ?: el.attr("data-video-url")).trim()
            if (src.startsWith("http")) videoSources["Auto"] = src
        }
        doc.select("iframe[src*='player'], iframe[src*='video'], iframe[src*='embed']").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) videoSources["Iframe"] = src
        }
        val data = if (videoSources.isNotEmpty()) videoSources.entries.joinToString("|||") { "${it.key}|${it.value}" } else "Watch|$url"
        return newMovieLoadResponse(title, url, TvType.NSFW, data) { posterUrl = poster; plot = description; if (videoTags.isNotEmpty()) tags = videoTags.take(10); duration?.let { this.duration = it } }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.isBlank()) return false
        val servers = data.split("|||").map { it.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else "Watch" to it[0] } }
        for ((quality, url) in servers) {
            if (url.isBlank()) continue
            if (url.contains("images.anilos.com") && url.endsWith(".mp4")) {
                val link = newExtractorLink(source = "Anilos", name = "Anilos $quality", url = url, type = ExtractorLinkType.VIDEO) {
                    this.headers = mapOf(
                        "Referer" to "https://anilos.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Range" to "bytes=0-"
                    )
                    this.quality = when (quality.lowercase()) {
                        "1080p" -> 1080; "960p" -> 960; "720p" -> 720; "640p" -> 640; "480p" -> 480; "360p" -> 360; else -> -1
                    }
                }
                callback.invoke(link)
            } else if (url.startsWith("http")) {
                val collected = mutableListOf<ExtractorLink>()
                val success = loadExtractor(url, subtitleCallback, { collected.add(it) })
                if (success && collected.isNotEmpty()) {
                    val master = collected.firstOrNull { it.url.contains("master.m3u8") || it.url.contains("master_") }
                    val toEmit = master ?: collected.first()
                    callback(newExtractorLink(source = "Anilos", name = toEmit.name, url = toEmit.url, type = toEmit.type) {
                        this.referer = toEmit.referer; this.headers = toEmit.headers; this.quality = toEmit.quality
                        this.extractorData = toEmit.extractorData; this.audioTracks = toEmit.audioTracks
                    })
                }
            }
        }
        return false
    }
}