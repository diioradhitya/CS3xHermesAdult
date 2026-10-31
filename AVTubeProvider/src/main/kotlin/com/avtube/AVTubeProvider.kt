package com.avtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element

class AVTubeProvider : MainAPI() {
    override var mainUrl = "https://avtubreal.com"
    override var name = "AVTube"
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
        "$mainUrl/category/bokep-jilbab/" to "Bokep Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = headers).document

        val home = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document

        return document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
        val href = fixUrl(link.attr("href"))
        val posterUrl = attr("data-main-thumb").ifBlank { selectFirst("img")?.attr("src") }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val durationMeta = document.selectFirst("meta[itemprop=duration]")?.attr("content")
        val duration = durationMeta?.let { parseISO8601(it) }

        val tags = document.select("a[rel=category tag], a[rel=tag]").map { it.text() }
            .filter { it.isNotBlank() }.distinct()

        val recommended = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.recommendations = recommended
        }
    }

    private fun parseISO8601(content: String): Int {
        // P0DT0H13M15S
        val h = Regex("(\\d+)H").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val s = Regex("(\\d+)S").find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

                // Harvest every iframe, not just the first. These pages are padded with ad
                // and promo iframes, so selectFirst regularly landed on a tracking frame and
                // loadLinks then reported nothing. Order the list the same way a reader's eye
                // would: the dedicated player container first, then anything left over.
                val preferred = document.select("div.responsive-player iframe[src], div.video-player iframe[src], iframe[src]")
                    .map { it.attr("src").trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { src -> runCatching { fixUrl(src) }.getOrDefault(src) }
                    .filter { it.startsWith("http") }

                if (preferred.isEmpty()) return false

                android.util.Log.d(TAG, "candidate iframes (${preferred.size}): ${preferred.take(6)}")

                // The ystream/Byse family rotates its API host (f7hyg4q.org, ystream.id,
                // n1mwq.org, ...). Verified live for embed code emuf2fon3lsy on a phone:
                //   GET /api/videos/<code>/embed/details -> f7hyg4q.org 403, ystream.id 200
                // A hardcoded host therefore returns 403 and loadLinks reports nothing,
                // which is what produced "no links found". Take the host from the iframe
                // we were actually handed - YstreamExtractor derives siteOrigin from it.
                //
                // Try each candidate until one actually emits a link: an extractor that
                // fails silently must not consume the only attempt.
                for (fixedUrl in preferred) {
                    val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
                    try {
                        // CloudStream's built-in extractors already cover doodstream,
                        // streamtape, vidara and friends, and they match on host name, so
                        // let them try first.
                        //
                        // This ordering is load-bearing: the Byse/ystream family also serves
                        // /e/<code> paths, so a shape-only match would hijack a Doodstream
                        // embed. v18 did exactly that - post 150197 sent doodstream code
                        // 66hek743yem1 to the ystream API, which answered
                        // "video record missing: video not found" and surfaced as
                        // "No Links Found" even though the video plays in a browser.
                        val wrap: (ExtractorLink) -> Unit = { link ->
                            emitted.set(true)
                            callback(link)
                        }

                        withContext(Dispatchers.IO) {
                            // Order still matters -- a shape-only match would
                            // hijack a Doodstream embed (v18's bug) -- but
                            // "an extractor claimed this host" is not the same
                            // as "an extractor produced a link". loadExtractor
                            // returns true on the claim, and Cloudstream's
                            // WebViewResolver matches *any* URL: it claimed
                            // ystream.id, spent 15s in a WebView on the SPA
                            // shell, produced nothing, and the early return
                            // meant YstreamExtractor never ran at all. The
                            // title then reported "No Links Found" even though
                            // /api/videos/<code>/embed/details answers 200.
                            //
                            // So skip loadExtractor only when the host name is
                            // one of ours -- that cannot collide with a built-in
                            // host, and it saves the 15s WebView wait.
                            val ours = isKnownFamilyHost(fixedUrl)
                            if (!ours) loadExtractor(fixedUrl, data, subtitleCallback, wrap)

                            if (!emitted.get()) {
                                when {
                                    isMorenciusFamily(fixedUrl) ->
                                        Morencius().getUrl(fixedUrl, data, subtitleCallback, wrap)
                                    isYstreamFamily(fixedUrl) ->
                                        YstreamExtractor().getUrl(fixedUrl, data, subtitleCallback, wrap)
                                    else -> android.util.Log.e(TAG, "no extractor claimed $fixedUrl")
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

    /**
     * Host-name match only, no path shape. Safe to act on without risking a
     * built-in extractor being bypassed, so it is what decides whether the
     * generic loadExtractor pass is worth running at all.
     */
    private fun isKnownFamilyHost(url: String): Boolean =
        isYstreamHost(url) || isMorenciusHost(url)

    /**
     * Hosts that serve an /e/<code> path but are NOT ours.
     *
     * The Byse/ystream family and the built-in Vidara family both use
     * /e/<code>, so the shape test alone cannot tell them apart. Measured on
     * the live site (avtubreal.com, 10 posts): 6 embeds are vidara.to/e/…,
     * 3 are ystream.id/e/…, 1 post has no iframe at all. Sending a vidara
     * code to the ystream API answers "video record missing" and the title
     * reports "No Links Found" even though the video plays in a browser -
     * the v18 bug in a new place.
     *
     * These are the built-in hosts CloudStream already covers, so the
     * built-in extractor gets first refusal and this list only has to keep
     * the shape test from stealing them afterwards.
     */
    private val FOREIGN_E_PATH_HOSTS = listOf(
        "vidara.",       // Vidara / vidarax / vidaraw / vidaraa / vidara.so
        "doodstream",
        "dood.to",
        "streamtape.",
        "streamtape",
        "playmogo",
        "mycloudz",
        "cloudwish",
        "streambeast",
    )

    private fun isForeignEPathHost(url: String): Boolean {
        val host = hostOf(url)
        return FOREIGN_E_PATH_HOSTS.any { host.contains(it) }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

    private fun isYstreamHost(url: String): Boolean {
        val host = hostOf(url)
        return YSTREAM_HOSTS.any { host.contains(it) }
    }

    private fun isMorenciusHost(url: String): Boolean {
        val host = hostOf(url)
        return MORENCIUS_HOSTS.any { host.contains(it) }
    }

    /**
     * Byse/ystream embeds always carry a short alphanumeric code on an /e/<code>
     * or /d/<code> path. The host-name list is checked first; the shape is only
     * trusted for a host nothing else claimed, which is how a rotated domain is
     * still recognised without hijacking a built-in host.
     */
    private fun isYstreamFamily(url: String): Boolean {
        if (isYstreamHost(url)) return true
        // A rotated domain is still recognised by the /e/<code> shape, but
        // that shape is shared with the built-in Vidara and DoodStream
        // families, so a foreign host is never taken on shape alone.
        if (isForeignEPathHost(url)) return false
        return Regex("""https?://[^/]+/(?:e|d|v)/[A-Za-z0-9]{6,}""").containsMatchIn(url)
    }

    private fun isMorenciusFamily(url: String): Boolean = isMorenciusHost(url)

    companion object {
        private const val TAG = "AVTubeProvider"

        // Host fragments seen for the Byse/ystream family. The API host rotates
        // per video, so these are matched as substrings of the host rather than
        // pinned domains.
        private val YSTREAM_HOSTS = listOf("ystream", "byse", "f7hyg4q", "n1mwq")
        private val MORENCIUS_HOSTS =
            listOf("morencius", "dingtezuni", "mivalyo", "ryderjet", "bingezove", "movearnpre")
    }
}