package com.javdoe

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Javdoe (javdoe.sh) — Indonesian adult streaming, multi-server.
 *
 * Flow, verified live on 2026-10-07:
 *
 *   post        https://javdoe.sh/287017/<slug>/                (WordPress listing)
 *   embed page  https://javdoe.sh/embed/287017/                 iframe id=main-player
 *   player      https://server.javdoe.sh/javdoe_play/268544      a DIFFERENT subdomain, and the
 *                                                                play id is NOT the post id
 *                                                                (268544 vs 287017)
 *   servers     7 playEmbed(...) targets, in the order the site lists them:
 *                 turbonewvid.com/t/688f25394dd89
 *                 cloudwish.xyz/e/67d1ufd6nkdr
 *                 mycloudz.cc/v/xv6tluon2kjt
 *                 streambeast.upn.one/#menwpt
 *                 playmogo.com/e/q5cwco3nlsnj                  (DoodStream clone)
 *                 playmogo.com/e/b2ro0davj8xc
 *                 lulustream.fit/e/49krjie0mbra
 *
 * None of those seven hosts is in CloudStream's catalogue. The final stream URL is produced by JS
 * in the browser, so it is invisible to a plain HTTP fetch — hence loadExtractor() first (it wins
 * by host name for anything known), then the Byse/ystream extractor for the /e/<code> shaped ones.
 *
 * javdoe.sh resolves to an ISP block page on an unproxied network, so the structure above was
 * mapped through a third-party text proxy. That is why this ships as status=1 (beta) until it is
 * proven on hardware.
 */
class JavdoeProvider : MainAPI() {

    // Without these two the app filters the provider out of the homepage source list:
    // MainAPI.hasMainPage defaults to false, and supportedTypes defaults to
    // Movie/TvSeries/Cartoon/Anime/OVA - never NSFW. filterProviderByPreferredMedia()
    // then drops it, so the plugin can be installed and listed under Extensions yet
    // never appear as a source on the home screen.
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // MainAPI.lang defaults to "en". filterProviderByPreferredMedia() keeps a provider only when
    // hasUniversal || langs.contains(api.lang), so with provider language pinned to Indonesian
    // an "en" provider is silently dropped from the home source list - while plugins that do
    // declare lang = "id" survive. This must match plugins.json's "language": "id".
    override var lang = "id"

    // MainAPI.name defaults to "NONE", so without this the provider registers as
    // "NONE" and collides with the app's own built-in NONE entry. Every other plugin
    // in this repo overrides it; the log showed "Adding NONE (NONE) MainAPI".
    override var name = "Javdoe"

    private val base = "https://javdoe.sh"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$base/",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
    )

    private suspend fun get(url: String, referer: String = "$base/"): org.jsoup.nodes.Document? = try {
        Log.d(TAG, "GET $url")
                val doc = app.get(url, headers = headers + ("Referer" to referer)).document
                Log.d(TAG, "GET $url -> ok (${doc.title().take(60)})")
                doc
    } catch (e: Exception) {
            Log.e(TAG, "GET $url failed: ${e.message}")
            null
        }

        /** Raw body text, for endpoints that return JSON or an HTML fragment. */
        private suspend fun getText(url: String, referer: String = "$base/"): String? = try {
            val text = app.get(url, headers = headers + ("Referer" to referer)).text
            Log.d(TAG, "GETTEXT $url -> ${text.length} chars")
            text
        } catch (e: Exception) {
            Log.e(TAG, "GETTEXT $url -> FAILED ${e.message}")
            null
        }

        /**
     * One listing card. Measured on the device 2026-10-08 against the ajax response:
     *
     *   <ul class="videos videosf">
     *     <li id="video-287060">
     *       <div class="video">
     *         <a href="/287060/mosaic-pgd-494-3d-beautiful-picture-nude-biera-kaori-blu-ray-disc-\u00a0/"
     *            title="Mosaic PGD-494 3D Beautiful Picture Nude Biera Kaori..." class="thumbnail">
     *           ... <img src=...>
     *         <div class="video-title">...</div>
     *
     * The anchor itself carries href, title and class=thumbnail, so it is the only element that
     * needs reading; `.video-title` is only a fallback for a card whose anchor lacks title=.
     */
    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("a.thumbnail[href], a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val title = a.attr("title").trim()
            .ifBlank { selectFirst(".video-title")?.text()?.trim().orEmpty() }
            .ifBlank { a.text().trim() }
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = when {
            img == null -> ""
            else -> img.attr("data-src").ifBlank {
                img.attr("data-lazy-src").ifBlank {
                    img.attr("data-original").ifBlank {
                        img.attr("data-srcset").ifBlank { img.attr("src") }
                    }
                }
            }
        }.trim()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            if (poster.startsWith("http")) posterUrl = poster
        }
    }

    /**
     * The homepage is a shell: every grid container is a shimmer placeholder
     * (`.video-thumb.sk-pulse`, `.fp-sk-line`) and the real markup is fetched per section by
     * `fetch('?ajax=fp_section&s=' + section)`. So the listing has to come from that endpoint.
     *
     * Verified responses (device log, tag Javdoe):
     *   ?ajax=fp_section&s=javdoe   {"status":0,"html":"","pagination":"","total":0}
     *   ?ajax=fp_section&s=watched   {"status":1,"html":"<ul class=\"videos videosf\">...","pagination":"","total":48}
     *   links parsed from watched = 97, from engsub/bigtit/titfuk/mature/javhd = 27 each
     *
     * The section list comes from the sk- and fc- prefixed id pairs in the shell markup.
     */
    private val sections = listOf("watched", "engsub", "bigtit", "titfuk", "mature", "javhd")

    private suspend fun sectionPosts(section: String): List<SearchResponse> {
        val body = getText("$base/?ajax=fp_section&s=$section") ?: return emptyList()
        val html = jsonString(body, "html") ?: run {
            Log.e(TAG, "$section: no html field")
            return emptyList()
        }
        val parsed = org.jsoup.parser.Parser.parse(html, "$base/")
        val items = parsed.select("li[id^=video-]").mapNotNull { it.toSearch() }
        Log.d(TAG, "section $section -> ${items.size} items, " +
            "${items.count { !it.posterUrl.isNullOrBlank() }} with poster")
        return items
    }

    /** Search hits DO come from the server-rendered page - that one is not client-rendered. */
    private suspend fun searchPosts(url: String): List<SearchResponse> {
        val doc = get(url) ?: return emptyList()
        val items = doc.select("li[id^=video-]").mapNotNull { it.toSearch() }
        Log.d(TAG, "search $url -> ${items.size} items")
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The shell has no items of its own; each section has to be fetched, and page 1 is the
        // only page worth building - the endpoint returns a fixed set with its own pagination
        // markup that CloudStream's page model does not consume.
        val items = if (page <= 1) {
            sections.flatMap { sectionPosts(it) }
                .distinctBy { it.url }
                .also { list ->
                    Log.d(TAG, "main page 1 -> ${list.size} items, " +
                        "${list.count { !it.posterUrl.isNullOrBlank() }} with poster")
                }
        } else emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchPosts("$base/?s=${query.trim().replace(" ", "+")}")

    override suspend fun load(url: String): LoadResponse? {
        val doc = get(url) ?: return null

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()
        if (title.isBlank()) return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
        val tags = doc.select("a[href*=/category/]").mapNotNull { it.text().trim().ifBlank { null } }

        // Hand loadLinks the post URL, not the shell iframe: the shell resolves to
        // server.javdoe.sh/javdoe_play/<id> and that id is not derivable from the iframe src.
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val postDoc = get(data) ?: return false

        val embedSrc = postDoc
            .selectFirst("div.responsive-player iframe[src], .video-player iframe[src], iframe[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        if (embedSrc.isNullOrBlank()) {
            Log.e(TAG, "no embed iframe on $data")
            return false
        }
        Log.d(TAG, "embed page: $embedSrc")

        // The real hosts live one hop further: a different subdomain, behind a play id that is not
        // the post id. Without this hop there is nothing to resolve.
        val embedDoc = get(embedSrc, referer = data) ?: return false
        val playerPage = embedDoc.select("script")
            .firstNotNullOfOrNull { extractPlayerUrl(it.data()) }
            ?: embedDoc.select("a[href], [data-url]")
                .firstOrNull { (it.attr("href") + it.attr("data-url")).contains("javdoe_play") }
                ?.let { it.attr("href").ifBlank { it.attr("data-url") }.trim() }
                ?.let { fixUrl(it) }

        if (playerPage.isNullOrBlank()) {
            Log.e(TAG, "no javdoe_play page on $embedSrc")
            return false
        }
        Log.d(TAG, "player page: $playerPage")

        val playerDoc = get(playerPage, referer = embedSrc) ?: return false

        // playEmbed('...') appears once per server button.
        val servers = Regex("""playEmbed\(\s*['"]([^'"]+)['"]\s*\)""")
            .findAll(playerDoc.html())
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .map { fixUrl(it) }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

        if (servers.isEmpty()) {
            Log.e(TAG, "no servers listed on $playerPage")
            return false
        }
        Log.d(TAG, "${servers.size} servers: $servers")

        for (url in servers) {
            // Every server gets a turn: one dead host must not end the attempt. The callback is
            // wrapped so an extractor that emits nothing does not consume the only try.
            val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
            val wrap: (ExtractorLink) -> Unit = { emitted.set(true); callback(it) }

            try {
                // Built-ins match on host name and win for anything they know. This must run
                // before the Byse shape test below, which would otherwise hijack playmogo (a
                // DoodStream clone serving the same /e/<code> path).
                if (loadExtractor(url, data, subtitleCallback, wrap) && emitted.get()) return true

                if (isByseShaped(url)) {
                    YstreamExtractor().getUrl(url, data, subtitleCallback, wrap)
                    if (emitted.get()) return true
                }

                Log.e(TAG, "no link from $url")
            } catch (e: Exception) {
                Log.e(TAG, "server failed $url: ${e.message}")
            }
        }
        return false
    }

    /**
     * Byse/ystream family embeds carry a short code on an /e/<code>, /d/<code> or /v/<code> path.
     * Several of javdoe's hosts use that shape without being in CloudStream's catalogue. Streambeast
     * carries its code in the fragment, which no path-based test can see.
     */
    private fun isByseShaped(url: String) =
        Regex("""https?://[^/]+/(?:e|d|v)/[A-Za-z0-9]{6,}""").containsMatchIn(url) ||
            url.contains("streambeast")

    /**
     * The embed template hands the player page to playEmbed(...) inside a <script>, so pull that one
     * argument rather than pattern-matching the whole document.
     */
    private fun extractPlayerUrl(script: String): String? =
        Regex("""playEmbed\(\s*['"]([^'"]*javdoe_play[^'"]*)['"]\s*\)""")
            .find(script)
            ?.groupValues
            ?.get(1)

    companion object {
        private const val TAG = "Javdoe"

        /**
                 * The endpoint answers with JSON whose "html" value is itself a markup fragment, escaped
                 * (`\/` for /, `\u00a0` for the non-breaking space inside titles). Pull that value out by
                 * key before jsoup ever sees it. The lookahead on `[,}]` is what stops the lazy match from
                 * ending on a quote that appears inside the fragment itself.
                 */
                private fun jsonString(json: String, key: String): String? =
                    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"(.*?)\"(?=\\s*[,}])", RegexOption.DOT_MATCHES_ALL)
                        .find(json)
                        ?.groupValues
                        ?.get(1)
                        ?.replace("\\/", "/")
                        ?.replace("\\u00a0", " ")
                        ?.replace("\\\"", "\"")
    }
}
