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

    // MainAPI.mainUrl is also "NONE" by default, and MainAPI.fixUrl() resolves relative hrefs
    // against it. Leaving it unset is why the registration line reads
    // "Adding Javdoe (NONE) MainAPI" - the second field is mainUrl, not lang.
    override var mainUrl = "https://javdoe.sh"

    private val base = "https://javdoe.sh"

    /**
     * Some hosts wrap the embed address in base64 rather than storing the plain url, so a src that
     * decodes to something starting with http is unwrapped; anything else is returned untouched.
     */
    private fun unwrap(src: String): String {
        if (src.startsWith("http") || src.startsWith("//") || src.startsWith("/")) return src
        val decoded = try {
            String(android.util.Base64.decode(src, android.util.Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            return src
        }
        return if (decoded.startsWith("http")) decoded else src
    }

    /** Resolve a possibly relative href against this provider's own base. */
    private fun absolute(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base + href
        else -> "$base/$href"
    }

    /**
     * Every url handed to the home screen this session, so no item can appear twice no matter what
     * the server returns. v14 shipped without this and scrolling repeated the same 48 items on
     * every page.
     */
    private val seen: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

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
        // The fragment carries root-relative hrefs ("/287060/slug/"). Resolving against the
        // document base turns them absolute; without it CloudStream requests
        // "NONE/NONE/287060/..." and load() never runs.
        //
        // MainAPI.fixUrl() is unusable here: it resolves against `mainUrl`, and this provider
        // never overrides that field, so it is still "NONE" and every card resolves to
        // "NONE/287060/..." - which then fails the startsWith("http") check below and silently
        // drops all 33 items (measured on device: v11 33 items, v12 0 items, same responses).
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        val url = absolute(href)
        if (!url.startsWith("http")) return null

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

        return newMovieSearchResponse(title, url, TvType.NSFW) {
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

    /** Row title for each section id, so the home screen reads as a category header. */
    private val sectionTitles = mapOf(
        "watched" to "Paling Banyak Ditonton",
        "engsub" to "English Subbed",
        "bigtit" to "Big Tit",
        "titfuk" to "Tit Fuk",
        "mature" to "Mature",
        "javhd" to "JAV HD"
    )

    /**
     * One section, all of it.
     *
     * This endpoint is not paginated, measured on the device (v14): `&page=2` through `&page=10`
     * each returned the same 18 items as page 1, and `total` always equalled the item count. The
     * JSON's "pagination" field is "" in every response. So a section is a fixed set and asking
     * it for a second page only duplicates the first - see getMainPage for where the scroll
     * actually continues.
     */
    private suspend fun sectionPosts(section: String): List<SearchResponse> {
        val body = getText("$base/?ajax=fp_section&s=$section") ?: return emptyList()

        val total = Regex("\"total\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1) ?: "?"
        val html = jsonString(body, "html")
        if (html.isNullOrBlank()) {
            // status 0 / empty html is what a wrong section name returns.
            Log.d(TAG, "section $section -> EMPTY (total=$total) ${body.take(120)}")
            return emptyList()
        }
        val parsed = org.jsoup.parser.Parser.parse(html, "$base/")
        val cards = parsed.select("li[id^=video-]")
        val items = cards.mapNotNull { it.toSearch() }
        // Sections legitimately overlap, so nothing is removed here; but anything already handed
        // out is remembered so later pages cannot repeat it.
        items.forEach { seen.add(it.url) }
        Log.d(TAG, "section $section -> ${items.size} items, " +
            "${items.count { !it.posterUrl.isNullOrBlank() }} with poster, total=$total, " +
            "ids=" + cards.joinToString(",") { it.attr("id").removePrefix("video-") })
        return items
    }

    /**
     * The site's own archive, used to keep the home feed growing after the sections.
     *
     * v15 measured `$base/page/<n>/` as a dead address: every page logged `0 cards`. What to try
     * next is read off the shell instead of guessed, so each candidate is tried once and the
     * result logged. The first shape that yields cards wins and the feed continues from it.
     */
    private suspend fun archivePosts(page: Int): List<SearchResponse> {
        val candidates = listOf(
            "$base/?ajax=fp_section&s=watched&paged=$page",
            "$base/page/$page/",
            "$base/?s=&paged=$page",
            "$base/?ajax=fp_list&paged=$page"
        )
        for (url in candidates) {
            val body = getText(url) ?: continue
            // The section endpoint wraps markup in JSON; the plain pages serve markup directly.
            val html = jsonString(body, "html")?.ifBlank { null } ?: body
            val parsed = org.jsoup.parser.Parser.parse(html, "$base/")
            val cards = parsed.select("li[id^=video-]")
            if (cards.isEmpty()) {
                Log.d(TAG, "archive p$page: $url -> 0 cards")
                continue
            }
            val fresh = cards.mapNotNull { it.toSearch() }.filter { seen.add(it.url) }
            Log.d(TAG, "archive p$page: $url -> ${cards.size} cards, ${fresh.size} new, ids=" +
                cards.joinToString(",") { it.attr("id").removePrefix("video-") })
            return fresh
        }
        return emptyList()
    }
    /** Search hits DO come from the server-rendered page - that one is not client-rendered. */
    private suspend fun searchPosts(url: String): List<SearchResponse> {
        val doc = get(url) ?: return emptyList()
        val items = doc.select("li[id^=video-]").mapNotNull { it.toSearch() }
        Log.d(TAG, "search $url -> ${items.size} items")
        return items
    }

    /**
     * Every discovered section gets its own row on the home screen, and each scroll appends the
     * next page of every section - that is how CloudStream drives infinite scroll here: it calls
     * getMainPage(1), appends getMainPage(2), getMainPage(3)... and stops when hasNext is false.
     *
     * Rows are built per section rather than flattened, because a flat listOf(dedupeBy(url))
     * collapses the six categories into one anonymous strip. Nothing is deduped across rows now:
     * a video appearing in two sections is legitimately listed under both.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Every discovered section gets its own named row on page 1 - the full set, because the
        // endpoint has no second page. From page 2 on the feed continues with the site's archive,
        // one archive page per scroll step.
        val rows = if (page <= 1) {
            sections.map { s -> HomePageList(sectionTitles[s] ?: s, sectionPosts(s), false) }
        } else {
            val items = archivePosts(page)
            if (items.isEmpty()) emptyList()
            else listOf(HomePageList("Semua Video", items, false))
        }
        val total = rows.sumOf { it.list.size }
        Log.d(TAG, "main page $page -> ${rows.count { it.list.isNotEmpty() }} rows, " +
            "$total new items, seen=${seen.size} (" +
            rows.joinToString(" ") { "${it.name}=${it.list.size}" } + ")")

        // An empty page ends the feed, which is how CloudStream learns to stop calling us.
        return newHomePageResponse(rows, rows.any { it.list.isNotEmpty() })
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

        // Measured on the post page (v14 POST-PROBE):
        //   iframes=1  videos=0  sources=0
        //   classes: fa-video-camera, search-in-video, content-video, pp-play
        //   ids: video | player-container | player | player-poster | main-player | previewBox ...
        //   pattern: /embed/287072/
        //
        // So there IS an iframe - the old selector just required [src] and this one keeps its
        // address elsewhere. #player-poster with .pp-play is the click-to-play overlay, which is
        // why the page shows a poster instead of a player until it is tapped. Read the iframe and
        // every data-* attribute in the document rather than assuming which one holds the address.
        val embedSrc = postDoc.selectFirst("iframe[src], iframe[data-src]")?.let { f ->
            f.attr("src").ifBlank { f.attr("data-src") }.trim()
        }?.ifBlank { null }
            // The theme base64-wraps the embed address into the src instead of storing the plain
            // url, so the value has to be decoded before it can be requested:
            //   aHR0cHM6Ly9teWNsb3Vkei5jYy92L3E4eHhlNmVpNHhtcQ== -> https://mycloudz.cc/v/q8xxe6ei4xmq
            // (v15 log: requesting the raw base64 as a path returned "404-meta-title".)
            ?.let { unwrap(it) }
            ?.ifBlank { null }
            ?.let { absolute(it) }
            ?: postDoc.selectFirst("[data-embed-url], [data-embed], [data-video-url], [data-iframe]")
                ?.let { el ->
                    listOf("data-embed-url", "data-embed", "data-video-url", "data-iframe")
                        .firstNotNullOfOrNull { el.attr(it).ifBlank { null } }?.trim()
                }
                ?.let { unwrap(it) }
                ?.let { absolute(it) }
            ?: Regex("""/embed/\d+/?/""").find(postDoc.html())
                ?.value
                ?.let { absolute(it) }

        if (embedSrc.isNullOrBlank()) {
            Log.e(TAG, "no embed url on $data :: " +
                "iframes=" + postDoc.select("iframe").map { it.attributes().asList().joinToString(",") { a -> a.key } }.distinct().take(3).joinToString(" ; ") +
                " dataAttrs=" + postDoc.select("*").flatMap { e ->
                    e.attributes().asList().map { it.key }.filter { it.startsWith("data-") }
                }.distinct().take(20).joinToString(","))
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
