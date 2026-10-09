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
     * Six page-name guesses are now measured dead on the device. Every one of them answers with
     * byte-identical length and the same six post ids, so none of them shifts the feed:
     *
     *   ?ajax=fp_section&s=mature&paged=2  -> 7334 chars, 6 cards, ids 287112,287102,...
     *   ?ajax=fp_section&s=mature&page=2   -> 7334 chars, 6 cards, ids 287112,287102,...
     *   ?ajax=fp_section&s=mature&pg=2     -> 7334 chars, 6 cards, ids 287112,287102,...
     *   ?ajax=fp_section&s=mature&offset=2 -> 7334 chars, 6 cards, ids 287112,287102,...
     *   ?ajax=fp_section&s=mature&start=2  -> 7334 chars, 6 cards, ids 287112,287102,...
     *
     * The category pager scan is dead too. It reads server-rendered /category/mature/, which really
     * is rendered (66937 chars, title "Video categories at Javdoe.sh"), and still reports no
     * pager-shaped href: pager hrefs = []. A page that renders fully and offers no next link means
     * there is no link to follow - not that a selector missed it.
     *
     * But one number does not fit a "no pagination" story: watched returns 18 cards while every
     * other section returns exactly 6. That asymmetry is a LIMIT rather than a page - the other
     * five sections are clipped at six with more behind them. So stop hunting for a pager and ask
     * for more per request: raise the page size under every name the theme might use, then try
     * offsets past the first six, and log what each actually returns so the real cap is measured
     * instead of assumed.
     */
    private suspend fun archivePosts(page: Int): List<SearchResponse> {
        val section = "mature"
        val probes = buildList {
            listOf("limit", "per_page", "posts_per_page", "count", "pp", "num", "size", "n")
                .forEach { pname ->
                    listOf(100, 48, 24, 18, 12).forEach { n ->
                        add("$base/?ajax=fp_section&s=$section&$pname=$n")
                    }
                }
            // Offset-style paging under the other names the theme might use, past the first six.
            listOf("offset", "start", "skip").forEach { pname ->
                add("$base/?ajax=fp_section&s=$section&$pname=6")
                add("$base/?ajax=fp_section&s=$section&$pname=12")
            }
            // WordPress routes, which the category itself answers.
            add("$base/category/$section/page/$page/")
            add("$base/category/$section/?paged=$page")
        }

        for (url in probes.distinct()) {
            val text = getText(url) ?: continue
            val doc = listingFragment(text)
            val cards = doc.select("li[id^=video-]")
            if (cards.isEmpty()) {
                Log.d(TAG, "archive p$page: $url -> 0 cards")
                continue
            }
            val fresh = cards.mapNotNull { it.toSearchFromList() }.filter { seen.add(it.url) }
            Log.d(TAG, "archive p$page: $url -> ${cards.size} cards, ${fresh.size} new, ids=" +
                cards.map { it.id().removePrefix("video-") }.take(6).joinToString(","))
            if (fresh.isNotEmpty()) return fresh
        }
        Log.d(TAG, "archive p$page: no probe widened or shifted the feed; sections are the whole feed")
        return emptyList()
    }

    /** The <html> fragment carried in the "html" field of the ajax listing response. */
    private fun listingFragment(text: String): org.jsoup.nodes.Document {
        val raw = Regex("\"html\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
            ?.groupValues?.get(1).orEmpty()
        return org.jsoup.Jsoup.parseBodyFragment(
            raw.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n")
        )
    }

    /** Card from an <li id="video-..."> row of the ajax listing fragment. */
    private fun org.jsoup.nodes.Element.toSearchFromList(): SearchResponse? {
        val link = selectFirst("a.thumbnail[href], a[href]") ?: return null
        val href = link.attr("href").trim().ifBlank { attr("href").trim() }
        if (href.isBlank()) return null
        val url = absolute(unwrap(href))
        if (!url.startsWith("http")) return null
        val title = selectFirst("h4, .video-title, .title, h2, h3")?.text()?.trim().orEmpty()
            .ifBlank { link.attr("title").trim() }
            .ifBlank { return null }
        val img = selectFirst("img")
        val poster = listOf("data-src", "data-lazy-src", "src", "data-original")
            .firstNotNullOfOrNull { img?.attr(it)?.ifBlank { null } }.orEmpty().trim()
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            if (poster.startsWith("http")) posterUrl = absolute(poster)
        }
    }

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
// PROBE v20 - v19 established the shape of the page and killed the Byse theory:
        //
        //   PLAYERS n=3   ids: player-container, player, player-poster
        //   IFRAME[0]     id=main-player  <-- and NO src attribute at all
        //
        // So the player is hydrated by a script on THIS page, not by an iframe src. And the
        // decoded "embed" (mycloudz.cc/v/<code>) turns out not to be a player at all: its own
        // ev-p1.js decodes to popunder/ad domains only - //gigg, //cack, //effe, //inso, //bvtp,
        // epicstream-style //atti - with zero m3u8/mp4/api literals. So the Byse extractor in
        // this repo targets the wrong platform, and no Origin header will ever fix it.
        //
        // The address has to come from this page. Dump every script that mentions a player or
        // an embed, whole, plus every data-* attribute on the player area - the answer is in
        // one of these and guessing another selector would not find it.
        postDoc.select("script").forEachIndexed { i, sc ->
            val body = sc.data().trim()
            if (body.isEmpty() && sc.attr("src").isNullOrBlank()) return@forEachIndexed
            val interesting = body.contains("player", true) || body.contains("embed", true) ||
                body.contains("stream", true) || body.contains("m3u8", true) ||
                body.contains("file_id", true) || body.contains("v/", true) ||
                !sc.attr("src").isNullOrBlank()
            if (!interesting) return@forEachIndexed
            val src = sc.attr("src")?.let { " src=$it" }.orEmpty()
            if (body.isEmpty()) {
                Log.d(TAG, "SCRIPT[$i] external$src")
            } else {
                Log.d(TAG, "SCRIPT[$i] inline chars=${body.length}$src :: " + body.replace("\n", " ").take(900))
            }
        }

        // Every data-*/on* attribute anywhere near the player, which is where the frame
        // address is usually parked before the script moves it.
        postDoc.select("[data-url], [data-src], [data-file], [data-id], [data-video], [data-player], [onclick], [onplay], #main-player, #player-container").forEachIndexed { i, el ->
            val attrs = el.attributes().asList().joinToString(",") { "${it.key}=${it.value.take(90)}" }
            Log.d(TAG, "ATTR[$i] <${el.tagName()}> $attrs")
        }

        // Any base64 blob on the page, and any absolute host that is not javdoe itself.
        Regex("[A-Za-z0-9+/]{40,}={0,2}").findAll(postDoc.html()).map { it.value }
            .distinct().take(6).forEachIndexed { i, b ->
            val dec = runCatching {
                String(android.util.Base64.decode(b, android.util.Base64.DEFAULT))
            }.getOrNull()
            Log.d(TAG, "B64[$i] $b -> ${dec?.take(120)}")
        }
        postDoc.select("a[href], iframe[src], script[src]").mapNotNull {
            val h = it.attr("href").ifBlank { it.attr("src") }.trim()
            h.takeIf { it.startsWith("http") && !it.contains("javdoe.sh") }
        }.distinct().take(12).forEachIndexed { i, h -> Log.d(TAG, "EXT[$i] $h") }

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
// v20 device log found the multi-source structure, and it is not an iframe at all:

        //   ATTR[0] <div>    id=player-container
        //   ATTR[1] <iframe> id=main-player  ...no src, hydrated by script
        //   ATTR[2] <button> class=button_choice_server,data-embeds=WyJodHRwczovL3N0cmVhbTIu...,data-name=Xcloud,data-player=1
        //   ATTR[3] <button> ...data-name=Wserver,data-player=1
        //   ATTR[4] <button> ...data-name=Zserver,data-player=1
        //   ATTR[5] <button> ...data-name=Yserver,data-player=1
        //   ATTR[6] <button> data-embed=aHR0cHM6Ly9teWNsb3Vkei5jYy92L3E4eHhlNmVpNHhtcQ==,data-name=Earnvid
        //   ATTR[7] <button> data-embed=aHR0cHM6Ly9jbG91ZHdpc2gueHl6L2UvNzJnMTR1N3NqdGl5,data-name=Streamhg
        //   ATTR[8] <button> data-embed=aHR0cHM6Ly9zdHJlYW1iZWFzdC51cG4ub25lLyM2OW95b2E=,data-name=StreamBeast
        //   ATTR[9] <button> data-embed=aHR0cHM6Ly9zdHJlYW10YXBlLm5ldC9lLzc5bEJQdlpQd01JQUx2bQ==,data-name=StreamTape
        //   ATTR[10] <button> data-embed=aHR0cHM6Ly9wbGF5bW9nby5jb20vZS9xYW1zYXlneWQyNXA=,data-name=Playmogo
        //
        // Nine servers per post, each a DIFFERENT host, each base64-encoded - and mycloudz.cc
        // (Earnvid) is only the first of them. Every earlier build resolved that single host, hit a
        // popunder network behind it, and reported "no link found"; the other eight were never
        // looked at. Two of the nine are already in CloudStream's own catalogue
        // (StreamTapeNet for streamtape.net, Playmogo - a DoodLa clone - for playmogo.com), so
        // loadExtractor is enough for those and no custom extractor is needed at all.
        //
        // Two encodings are in play:
        //   data-embed    base64 of a single url
        //   data-embeds   base64 of a JSON ARRAY of urls - the four "*-server" buttons carry a
        //                 whole mirror list each, e.g. ["https://stream2.javhdz.today/embed.php?p=3Jv2Dl-bd6UkphFPBQbrqWrR",
        //                 "https://stream10.javhdz.today/embed.php?p=3Jv2Dl-bd6UkphFPBQbrqWrR", ...]
        // Both are unwrapped to every url they contain, so one dead mirror cannot end the try.
        val serverButtons = postDoc.select("button[data-embed], button[data-embeds], .button_choice_server")
        val servers = linkedSetOf<String>()
        serverButtons.forEach { btn ->
            val label = btn.attr("data-name").ifBlank { btn.attr("id") }.trim()
            val raw = listOf(btn.attr("data-embed"), btn.attr("data-embeds"))
                .filter { it.isNotBlank() }
            val decoded = raw.flatMap { decodeServerUrls(it) }
            decoded.forEach { servers.add(it) }
            Log.d(TAG, "SERVER $label -> ${decoded.size} url(s)${if (decoded.isEmpty()) " (raw ${raw.firstOrNull()?.take(60)})" else ""}")
        }

        // Any address the page itself parks on an iframe, still worth trying last.
        postDoc.select("iframe[src], [data-embed-url], [data-src]")
            .mapNotNull { it.attr("src").ifBlank { it.attr("data-embed-url") }.ifBlank { it.attr("data-src") }.trim() }
            .map { absolute(unwrap(it)) }
            .filter { it.startsWith("http") }
            .forEach { servers.add(it) }

        val list = servers.filter { !it.contains("javdoe.sh") && !it.contains("/templates/") }
        if (list.isEmpty()) {
            Log.e(TAG, "no server address on the post page at all")
            return false
        }
        Log.d(TAG, "${list.size} server url(s) from ${serverButtons.size} buttons")
        list.forEachIndexed { i, u -> Log.d(TAG, "SRV[$i] $u") }

        for (url in list) {
            // Every server gets a turn: one dead host must not end the attempt. The callback is
            // wrapped so an extractor that emits nothing does not consume the only try, and so the
            // urls it produced can be inspected before they are handed to the player - a wrapper
            // that only sets a flag cannot tell a good url from a broken one.
            emittedThisServer.clear()
            val wrap: (ExtractorLink) -> Unit = { link ->
                emittedThisServer.add(link.url)
                Log.d(TAG, "EMIT ${link.name} ${link.type} ${link.url}")
                callback(link)
            }

            try {
                // Built-ins match on host name and win for anything they know - StreamTapeNet and
                // Playmogo among them. This must run before the Byse shape test below, which would
                // otherwise hijack playmogo (a DoodStream clone serving the same /e/<code> path).
                //
                // loadExtractor() only returns true when a host matched, NOT when a link came out
                // - and it ALWAYS returns true on the fuzzy-mirror fallback. That fallback compares
                // hosts by similarity above 80, so "streambeast.upn.one/#69oyoa" was matched by
                // Streamcash's "https://streamcash.to" (rate ~82) and its id was taken as
                // substringAfterLast("/") = "#69oyoa", giving the junk url
                //   https://cdn.streamcash.to/videos/#69oyoa/index.m3u8
                // whose '#' makes the rest a URI fragment, so ExoPlayer requested
                // ".../videos/" and got 403 -> ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
                //
                // So a matching extractor is not enough: the address it produced has to be judged
                // on its own terms, and the fuzzy fallback specifically has to be refused, because
                // it is exactly the thing that invented that url. Check emitted FIRST and keep the
                // whole try only if the extractor actually handed over a real, well-formed url.
                if (loadExtractor(url, data, subtitleCallback, wrap) && emittedThisServer.isNotEmpty()) {
                    if (allEmittedUsable()) return true
                    Log.e(TAG, "builtin gave unusable urls for $url -> falling through")
                }

                if (isByseShaped(url)) {
                    YstreamExtractor().getUrl(url, data, subtitleCallback, wrap)
                    if (emittedThisServer.isNotEmpty() && allEmittedUsable()) return true
                }

                Log.e(TAG, "no link from $url")
            } catch (e: Exception) {
                Log.e(TAG, "server failed $url: ${e.message}")
            }
        }
        return false
    }

    /**
     * Judges what the current server's extractor(s) handed to the callback.
     *
     * The callback is wrapped by loadLinks so every link an extractor produces is recorded here
     * instead of going straight to the player. A link is usable only if it is well formed - a
     * fragment left in the path (cdn.streamcash.to/videos/#69oyoa/index.m3u8) resolves to a
     * directory, never to the media, and is rejected by the CDN with 403.
     *
     * Kept as a per-server list so one server's junk cannot make the next one look fine, and so
     * the log says which server produced what.
     */
    private val emittedThisServer = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** True when at least one link was emitted this server and every one of them is usable. */
    private fun allEmittedUsable(): Boolean =
        emittedThisServer.isNotEmpty() && emittedThisServer.all { usableStreamUrl(it) }

    /**
     * A stream url ExoPlayer can actually fetch: absolute http(s), no fragment or query left in
     * the path, and a media-looking tail (m3u8 / mp4 / ts / mpd, or at least a file name after the
     * last slash). Anything else is a directory, a tracking wrapper or a build artefact.
     */
    private fun usableStreamUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val noFragment = url.substringBefore('#').substringBefore('?')
        if (noFragment.isBlank()) return false
        val path = runCatching { java.net.URI(noFragment).path }.getOrNull() ?: return false
        if (path.isBlank() || path.endsWith('/')) return false
        val tail = path.substringAfterLast('/')
        if (tail.isBlank()) return false
        // m3u8 variants and progressive files are unambiguous.
        if (Regex("\\.(m3u8|mpd|mp4|m4v|webm|ts)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(noFragment)) return true
        // Otherwise require a real file name (an extension) rather than a bare id directory.
        return tail.contains('.')
    }

    /**
     * One server button's payload, which arrives in either of two shapes:
     *
     *   data-embed    base64 of a single url, e.g. aHR0cHM6Ly9teWNsb3Vkei5jYy92L3E4eHhlNmVpNHhtcQ==
     *                 -> https://mycloudz.cc/v/q8xxe6ei4xmq
     *   data-embeds   base64 of a JSON array of urls, which is what the four mirror buttons carry:
     *                 WyJodHRwczovL3N0cmVhbTIuamF2aGR6LnRvZGF5L2VtYmVkLnBocD9wPTNKdjJEbC1iZDZVa3BoRlBCQWJxeVdyST", ...]
     *
     * An array whose member strings are themselves base64 blobs (which is what the button really
     * holds: [b64url, b64url, ...]) is unwrapped twice, so a plain double-decode does not lose the
     * mirrors. Anything that does not decode to something http-shaped is dropped rather than
     * handed to loadExtractor as a path.
     */
    private fun decodeServerUrls(raw: String): List<String> {
        fun b64(text: String): String? = runCatching {
            String(android.util.Base64.decode(text.trim(), android.util.Base64.DEFAULT))
        }.getOrNull()

        val first = b64(raw) ?: return emptyList()
        val out = linkedSetOf<String>()

        // JSON array form.
        val trimmed = first.trim()
        if (trimmed.startsWith("[")) {
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(trimmed).forEach { m ->
                val member = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                // Members are base64 themselves in the real payload; take them as-is when they
                // already look like a url, else unwrap once.
                val url = if (member.startsWith("http")) member else (b64(member) ?: member)
                if (url.startsWith("http")) out.add(url)
            }
        } else if (trimmed.startsWith("http")) {
            out.add(trimmed)
        } else {
            val inner = b64(trimmed)
            if (inner?.startsWith("http") == true) out.add(inner)
        }
        return out.toList()
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
