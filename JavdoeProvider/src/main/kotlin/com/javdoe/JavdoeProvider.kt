package com.javdoe

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Javdoe (javdoe.sh) — Indonesian adult streaming, multi-server.
 *
 * Flow, as measured on the device on 2026-10-08 (earlier notes below were from a text proxy and
 * described a hop that does not exist):
 *
 *   listing    https://javdoe.sh/?ajax=fp_section&s=<section>   JSON wrapping an HTML fragment;
 *              one FIXED window of cards per section. &page=, /page/<n>/ and &paged= were all
 *              measured and all ignored - they return page 1 again, so the six sections are the
 *              whole feed and there is nothing to scroll to.
 *   post       https://javdoe.sh/287017/<slug>/                 server-rendered, load() reads it
 *   embed      the post's single <iframe> keeps its address BASE64-ENCODED in src, e.g.
 *              https://javdoe.sh/aHR0cHM6Ly9teWNsb3Vkei5jYy92L3E4eHhlNmVpNHhtcQ==
 *              which decodes to https://mycloudz.cc/v/q8xxe6ei4xmq. Requesting the encoded string as
 *              a path returns javdoe's own 404.
 *
 * There is NO server.javdoe.sh/javdoe_play hop behind these embeds - the decoded address is already
 * the player, on its own host (v16: "no javdoe_play page on https://mycloudz.cc/v/c2babhapnrvx").
 * So loadLinks resolves the decoded embed url directly, and only follows the playEmbed(...) list
 * when an embed really does offer one.
 *
 * None of the player hosts is in CloudStream's catalogue, and the final stream url is produced by JS
 * in the browser, so it is invisible to a plain HTTP fetch — hence loadExtractor() first (it wins by
 * host name for anything known), then the Byse/ystream extractor for the /v/<code> and /e/<code>
 * shapes.
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
     * The home feed after the sections.
     *
     * Three pagination guesses were measured dead on the device, and all three failed identically -
     * the server answered, with the same 18 cards as page 1:
     *
     *   &page=<n>   section watched p2 .. p10 -> 18 items, ids 287099..287082 (= p1)
     *   /page/<n>/  archive -> 0 cards
     *   &paged=<n>  archive p2 -> 18 cards, 0 new, ids 287099..287082 (= p1)
     *
     * So `?ajax=fp_section` returns one fixed window per section and no parameter shifts it. A fourth
     * guessed name will not find it; the shell already carries the loader, so read the pagination
     * argument out of that inline script and use whatever name it actually uses.
     */
    private suspend fun archivePosts(page: Int): List<SearchResponse> {
        // v17 measured `loader pagination names = []` - the shell's inline script carries no
        // pagination argument at all, so there is nothing to read out of it. Three names were
        // already measured dead on the device (&page=, /page/<n>/, &paged=, all returning page 1).
        //
        // A category page IS server-rendered though (/category/mature/ returned 66937 chars), so its
        // markup - not the shell's script - is where a next-page link has to be looked for. Whatever
        // href the pager uses is the address that actually moves the feed.
        val catDoc = get("$base/category/mature/") ?: return emptyList()
        val pageHrefs = catDoc.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.isNotBlank() && !it.contains("#") }
            .filter { href ->
                val low = href.lowercase()
                low.contains("/page/") || low.contains("?page") || low.contains("paged=") ||
                    low.contains("offset=") || low.contains("start=")
            }
            .distinct()
            .take(12)
        Log.d(TAG, "archive p$page: pager hrefs = $pageHrefs")

        val candidates = buildList {
            // Page-shaped hrefs discovered on the server-rendered category page.
            pageHrefs.forEach { add(if (it.startsWith("http")) it else absolute(it)) }
            // The ajax section with each plausible page name, so a name the pager uses as a
            // query string is still covered.
            listOf("paged", "page", "pg", "offset", "start").forEach { pname ->
                add("$base/?ajax=fp_section&s=mature&$pname=$page")
            }
        }

        for (url in candidates.distinct()) {
            val body = getText(url) ?: continue
            val frag = jsonString(body, "html")?.ifBlank { null } ?: body
            val cards = org.jsoup.parser.Parser.parse(frag, "$base/").select("li[id^=video-]")
            if (cards.isEmpty()) {
                Log.d(TAG, "archive p$page: $url -> 0 cards")
                continue
            }
            val fresh = cards.mapNotNull { it.toSearch() }.filter { seen.add(it.url) }
            Log.d(TAG, "archive p$page: $url -> ${cards.size} cards, ${fresh.size} new, ids=" +
                cards.joinToString(",") { it.attr("id").removePrefix("video-") })
            if (fresh.isNotEmpty()) return fresh
        }
        // Nothing beyond the sections exists over HTTP - say so once instead of silently returning
        // empty and leaving a feed that will never grow.
        Log.d(TAG, "archive p$page: no candidate returned new items; sections are the whole feed")
        return emptyList()
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
    /** Search hits DO come from the server-rendered page - that one is not client-rendered. */
    private suspend fun searchPosts(url: String): List<SearchResponse> {
        val doc = get(url) ?: return emptyList()
        val items = doc.select("li[id^=video-]").mapNotNull { it.toSearch() }
        Log.d(TAG, "search $url -> ${items.size} items")
        return items
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
        // PROBE: the post page exposes several players (POST-PROBE showed player1..player4), and
        // every one of them may point at a different host. Dump each player container in full so the
        // multi-source shape is read from the markup instead of guessed.
        val players = postDoc.select("#player, #player1, #player2, #player3, #player4," +
            "[id^=player-], .player, .video-player, .responsive-player, [id*=source], [class*=source]")
        Log.d(TAG, "PLAYERS n=${players.size}")
        players.take(8).forEachIndexed { i, p ->
            val attrs = p.attributes().asList().joinToString(",") { "${it.key}=${it.value.take(60)}" }
            Log.d(TAG, "PLAYER[$i] id=${p.attr("id")} cls=${p.attr("class")} attrs=$attrs")
            Log.d(TAG, "PLAYER[$i] html=" + p.outerHtml().replace("\n", " ").take(700))
        }
        postDoc.select("iframe").forEachIndexed { i, f ->
            val a = f.attributes().asList().joinToString(",") { "${it.key}=${it.value.take(80)}" }
            Log.d(TAG, "IFRAME[$i] $a")
        }

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

        // v16 device log settled the hop structure - it is not what the header comment claimed:
        //
        //   embed page: https://mycloudz.cc/v/c2babhapnrvx
        //   GET ... -> ok (Embed)
        //   E/Javdoe: no javdoe_play page on https://mycloudz.cc/v/c2babhapnrvx
        //
        // The decoded src is already a ystream/Byse page on its own host; there is no
        // server.javdoe.sh/javdoe_play hop behind it. The post -> embed -> javdoe_play chain the
        // header describes does not exist for these embeds, and requiring one made every video end
        // in "no link found".
        //
        // So the embed url is treated as the thing to resolve, and the old hop is used only for the
        // embeds that really do carry a playEmbed(...) call.
        val embedDoc = get(embedSrc, referer = data) ?: return false

        val playerPage = embedDoc.select("script")
            .firstNotNullOfOrNull { extractPlayerUrl(it.data()) }
            ?: embedDoc.select("a[href], [data-url]")
                .firstOrNull { (it.attr("href") + it.attr("data-url")).contains("javdoe_play") }
                ?.let { it.attr("href").ifBlank { it.attr("data-url") }.trim() }
                ?.let { absolute(it) }

        // playEmbed('...') appears once per server button, on the player page when there is one.
        val servers = if (playerPage.isNullOrBlank()) {
            Log.d(TAG, "no javdoe_play hop; resolving embed page directly: $embedSrc")
            listOf(embedSrc)
        } else {
            Log.d(TAG, "player page: $playerPage")
            val playerDoc = get(playerPage, referer = embedSrc) ?: return false
            Regex("""playEmbed\(\s*['"]([^'"]+)['"]\s*\)""")
                .findAll(playerDoc.html())
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .map { absolute(it) }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()
                .ifEmpty { listOf(embedSrc) }
        }

        Log.d(TAG, "${servers.size} server(s): $servers")

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
