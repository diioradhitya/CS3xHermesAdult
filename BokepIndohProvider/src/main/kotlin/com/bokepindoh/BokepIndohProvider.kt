package com.bokepindoh

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Bokepindoh (bokepindoh.exchange) — WordPress tube, same theme family as AVTube.
 *
 * Verified live 2026-11-08 (fetched over Cloudflare primary IP bypassing local
 * ISP DNS poisoning — see the ISP note below):
 *   listing   article.loop-video.thumb-block.video-preview-item
 *             poster = data-main-thumb (img src is a lazy SVG placeholder),
 *             title = a[title], duration = .duration ("12:10")
 *   tabs      Latest (home), Bokep Indo, Bokep Viral, Bokep JAV
 *   paging    WordPress /page/<n>/  (home p1 vs p2: 0 overlap, p1 ids
 *             14037,14041,... vs p2 13977,13971,...)
 *   category paging  /category/<slug>/page/<n>/  (bokep-jav has only 5 posts,
 *             no pager; its /page/2/ 301-redirects to home)
 *   search    /?s=<q>  (20 cards for "Hijab")
 *   detail    h1 title, og:image poster, meta[itemprop=duration] P0DT0H12M10S,
 *             embed = div.responsive-player iframe[src]
 *             -> https://mumu.watch/v/<id> (see MumuExtractor)
 *
 * ISP note (Kominfo/DPI DNS block):
 * The Indonesian ISP resolves bokepindoh.exchange to blockpage.xlaxiata.id.
 * CloudStream resolves via the device DNS, so set Android Private DNS to
 * "one.one.one.one" (Cloudflare 1.1.1.1) or "dns.quad9.net" (Quad9 9.9.9.9),
 * or use a VPN, and the listing loads without any code change. Video host
 * m-cdn.video is not blocked. vpnStatus is set so the app shows the VPN hint.
 * The site sits behind Cloudflare (104.21.56.37 / 172.67.176.171), so a DoH
 * resolver returns the real IP while the ISP DNS returns the blockpage.
 */
class BokepIndohProvider : MainAPI() {
    override var mainUrl = "https://bokepindoh.exchange"
    override var name = "BokepIndoh"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Update",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    private fun pageUrl(base: String, page: Int): String {
        val clean = base.removeSuffix("/").ifBlank { mainUrl }
        // Home "/" with page 1 must keep the trailing slash: "/".
        if (page <= 1) return if (clean == mainUrl) "$mainUrl/" else clean
        return "$clean/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, headers = headers).document
        val home = document.select("article.loop-video.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val document = app.get(url, headers = headers).document
        return document.select("article.loop-video.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
            .ifBlank { selectFirst("header.entry-header span, .entry-header span")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null
        // img src is a lazy SVG placeholder; real cover = data-main-thumb / data-lazy-src.
        val posterUrl = attr("data-main-thumb").ifBlank {
            selectFirst("img")?.attr("data-lazy-src").orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("data-src").orEmpty()
        }.ifBlank {
            val src = selectFirst("img")?.attr("src").orEmpty()
            if (src.startsWith("data:")) "" else src
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl.ifBlank { null }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val durationMeta = document.selectFirst("meta[itemprop=duration]")?.attr("content")
        val duration = durationMeta?.let { parseISO8601(it) }

        // Detail page nests its category links differently from listing cards;
        // fall back to the URL slug so the tag list is never empty.
        val tags = document.select("a[rel=category tag], a[rel=tag], .tags-links a, .post-tags a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            .ifEmpty {
                runCatching {
                    listOfNotNull(url.substringAfter("bokepindoh.exchange/").substringBefore("/").takeIf { it.isNotBlank() })
                }.getOrDefault(emptyList())
            }

        val recommended = document.select("article.loop-video.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.recommendations = recommended
        }
    }

    private fun parseISO8601(content: String): Int {
        // P0DT0H12M10S
        val h = Regex("(\\d+)H").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val s = Regex("(\\d+)S").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        // LoadResponse.duration is in MINUTES (see FilmSemi res.runtime);
        // seconds here made a 3-minute video read as 3 hours.
        return (h * 3600 + m * 60 + s) / 60
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // Harvest every iframe, not just the first. These pages are padded with ad
        // and promo iframes, so selectFirst regularly landed on a tracking frame.
        // BokepIndoh lazy-loads the player with WP Rocket: iframe[src] is
        // "about:blank" and the real address is in data-lazy-src. Reading only
        // src yields 0 http links (the duplicate in <noscript> is not parsed by
        // jsoup), which surfaced as "no links found".
        val preferred = document.select("div.responsive-player iframe, div.video-player iframe, iframe")
            .mapNotNull { iframe ->
                val src = iframe.attr("src").trim().takeIf { it.startsWith("http") }
                    ?: iframe.attr("data-lazy-src").trim().takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-src").trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                runCatching { fixUrl(src) }.getOrDefault(src)
            }
            .filter { it.startsWith("http") }
            .distinct()

        if (preferred.isEmpty()) return false

        android.util.Log.d(TAG, "candidate iframes (${preferred.size}): ${preferred.take(6)}")

        for (fixedUrl in preferred) {
            val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val wrap: (ExtractorLink) -> Unit = { link ->
                    emitted.set(true)
                    callback(link)
                }

                withContext(Dispatchers.IO) {
                    // Our own mumu.watch / luluvdoo.com hosts go straight to our
                    // extractors — CloudStream's built-ins do not claim them
                    // (LuluStream's host list is streamwish.* / lulustream.com,
                    // and the embed is packer-encoded). Everything else gets
                    // first refusal from CloudStream's built-ins.
                    val host = hostOf(fixedUrl)
                    when {
                        host.contains("mumu.watch") ->
                            MumuExtractor().getUrl(fixedUrl, data, subtitleCallback, wrap)
                        host.contains("luluvdoo") || host.contains("lulustream") || host.contains("luluvdo") ->
                            LuluStreamExtractor().getUrl(fixedUrl, data, subtitleCallback, wrap)
                        else -> {
                            loadExtractor(fixedUrl, data, subtitleCallback, wrap)
                            if (!emitted.get() && host.contains("mumu")) {
                                MumuExtractor().getUrl(fixedUrl, data, subtitleCallback, wrap)
                            } else if (!emitted.get() && (host.contains("luluvdoo") || host.contains("lulustream"))) {
                                LuluStreamExtractor().getUrl(fixedUrl, data, subtitleCallback, wrap)
                            }
                        }
                    }
                }
                if (emitted.get()) return true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Extractor error for $fixedUrl: ${e.message}", e)
            }
        }
        android.util.Log.e(TAG, "no iframe of ${preferred.size} produced a link for $data")
        return false
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

    companion object {
        private const val TAG = "BokepIndohProvider"
    }
}
