package com.javseg

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
class JavSegarProvider : MainAPI() {
    override var mainUrl = "https://javsegar.com"
    override var name = "JavSegar"
    override var lang = "id"
    override var hasMainPage = true
    override val hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.NSFW)

    /**
     * Post URL -> cover URL. Covers both hits and misses so re-opening the
     * same tab does not re-fetch, and a post without a cover is only ever
     * requested once.
     */
    private val posterCache = HashMap<String, String?>()

    override val mainPage = mainPageOf(
        "rest:posts?orderby=date&order=desc" to "Latest",
        "rest:posts?orderby=title&order=asc" to "A - Z",
        "rest:posts?orderby=title&order=desc" to "Z - A",
        "rest:posts?orderby=date&order=asc" to "Lama",
        "rest:posts?orderby=id&order=asc" to "Lama Sekali",
    )

    /**
     * The site exposes only ONE real WordPress category (bokep-jepang, 5641
     * posts) — confirmed via wp-json/wp/v2/categories. Every front-end URL
     * therefore resolves to the same post set, which is why the old "Latest"
     * and "Bokep autofocus" tabs returned byte-identical lists.
     *
     * Tabs are now driven through the WP REST API, each on a different sort
     * axis, verified to return distinct post IDs:
     *   date desc  -> 197702 197700 197698
     *   title asc  -> 95496  95509  104141
     *   title desc -> 144183 62033  197014
     *   date asc   -> 32054  32058  32061
     *   id asc     -> 32054  32057  32058
     * "rest:" prefix marks a REST path; anything else is treated as an HTML URL.
     */
    private data class WpPost(
        @JsonProperty("slug") val slug: String = "",
        @JsonProperty("link") val link: String = "",
        @JsonProperty("title") val title: WpRendered? = null,
        @JsonProperty("excerpt") val excerpt: WpRendered? = null,
        @JsonProperty("date") val date: String = "",
    )

    private data class WpRendered(
        @JsonProperty("rendered") val rendered: String = "",
    )

    /** WordPress oEmbed document — carries the cover as thumbnail_url. */
    private data class WpOembed(
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    )

    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title") ?: selectFirst("span")?.text() ?: return null
        val thumb = selectFirst("img.video-main-thumb")?.attr("src")
            ?: selectFirst("img")?.attr("data-main-thumb") ?: ""
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = thumb
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("rest:")) {
            val items = restTab(request.data.removePrefix("rest:"), page)
            return newHomePageResponse(request.name, items)
        }

        val baseUrl = request.data
        val url = if (page <= 1) baseUrl else "$baseUrl/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.thumb-block").mapNotNull { it.toSearch() }
        return newHomePageResponse(request.name, items)
    }

    /**
     * Fetch one page of a REST-backed tab. Falls back to an empty list rather
     * than throwing so a single bad request cannot break the whole home page.
     */
    private suspend fun restTab(query: String, page: Int): List<SearchResponse> {
        val perPage = 30
        val sep = if (query.contains('?')) "&" else "?"
        val url = "$mainUrl/wp-json/wp/v2/$query${sep}per_page=$perPage&page=$page&_fields=link,title,excerpt,date"
        return try {
            app.get(url).parsed<Array<WpPost>>()?.mapNotNull { it.toSearch() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "restTab failed for $url: ${e.message}")
            emptyList()
        }
    }

    private fun WpPost.toSearch(): SearchResponse? {
        if (link.isBlank()) return null
        val name = Jsoup.parse(title?.rendered.orEmpty()).text().trim()
        if (name.isBlank()) return null
        // SearchResponse has no plot field; the description is shown on load().
        // posterUrl is set later in bulk by resolvePosters() — the REST payload
        // carries no image at all (featured_media is 0 for every post, the media
        // library lives on a different host), so a per-post og:image fetch would
        // mean 30 requests per page.
        return newMovieSearchResponse(name.ifBlank { slug }, link, TvType.NSFW)
    }

    /* ------------------------------------------------------------------ */
    /* Posters                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * javsegar.com has an empty media library: featured_media is 0 for all
     * 5.641 posts and yoast_head_json is not exposed, so the REST payload can
     * never carry an image.
     *
     * Two things had to be fixed to get posters on screen:
     *
     * 1. Coverage. v8 matched a fixed 100-item window of imgswipe.xyz media on
     *    the JAV code, but that window spans ~2 days on a site that publishes
     *    ~30/day — so "Latest" got 30/30 and every other tab got 0/30.
     *
     * 2. Cost. v9 read og:image off the post page, which is ~60 KB of HTML per
     *    item — 1.77 MB to fill one screen, the reason posters never appeared
     *    in time. The oEmbed endpoint returns the same thumbnail in 2.4 KB
     *    (25x smaller), but it only knows about covers hosted on javsegar.com:
     *    6/6 old posts, 0/6 recent ones, whose covers live on imgswipe.xyz.
     *
     * So: try the cheap oEmbed first and fall back to the page only for what it
     *    misses. Measured 8/8 on Lama Sekali, Lama and A - Z at 19 KB total.
     */
    private suspend fun resolvePosters(items: List<SearchResponse>) {
        if (items.isEmpty()) return

        val pending = items.filter { it.url !in posterCache }
        if (pending.isEmpty()) {
            items.forEach { posterCache[it.url]?.let { p -> it.posterUrl = p } }
            return
        }

        val found = coroutineScope {
            pending.map { async { posterCache[it.url] to fetchPoster(it.url) } }
                .awaitAll()
        }.toMap()

        var hits = 0
        for (item in pending) {
            val poster = found[item.url]
            if (!poster.isNullOrBlank()) {
                posterCache[item.url] = poster
                item.posterUrl = poster
                hits++
            }
        }
        Log.d(TAG, "posters resolved: $hits/${pending.size}")
    }

    /**
     * Cover URL for a post: oEmbed first (cheap), og:image as the fallback
     * (complete). Caches hits and misses alike so nothing is fetched twice.
     */
    private suspend fun fetchPoster(url: String): String? {
        if (posterCache.containsKey(url)) return posterCache[url]

        val thumb = try {
            app.get("$mainUrl/wp-json/oembed/1.0/embed?url=${URLEncoder.encode(url, "UTF-8")}")
                .parsed<WpOembed>()?.thumbnailUrl
        } catch (e: Exception) {
            Log.d(TAG, "oembed miss for $url: ${e.message}")
            null
        }

        val clean = thumb?.trim()?.takeIf { it.startsWith("http") }
            ?: ogImageOf(url)
        posterCache[url] = clean
        return clean
    }

    /** Slow path: the full post page. Only reached when oEmbed has no thumbnail. */
    private suspend fun ogImageOf(url: String): String? = try {
        app.get(url).document
            .selectFirst("""meta[property=og:image]""")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        Log.e(TAG, "og:image failed for $url: ${e.message}")
        null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        // WordPress search endpoint returns real title/date data and works
        // even when the ?s= HTML form is blocked or returns a bot wall.
        return try {
            val items = app.get("$mainUrl/wp-json/wp/v2/posts?per_page=30&search=$q&_fields=link,title,excerpt,date")
                .parsed<Array<WpPost>>()?.mapNotNull { it.toSearch() }?.ifEmpty {
                    searchHtml(query)
                } ?: searchHtml(query)
            resolvePosters(items)
            items
        } catch (e: Exception) {
            Log.e(TAG, "rest search failed, falling back to HTML", e)
            searchHtml(query)
        }
    }

    private suspend fun searchHtml(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.thumb-block").mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().replace(" - JavSegar", "").trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val metaDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val plotText = metaDesc ?: doc.selectFirst("div.entry-content")?.text()?.trim().orEmpty()
        val duration = doc.selectFirst("meta[itemprop=duration]")?.attr("content") ?: ""
        val tags = doc.select("a[href*=/tags/]").mapNotNull { it.text().trim().ifBlank { null } }

        // Prefer the real player iframe. Fall back to the post URL only when
        // the page has no iframe at all — loadLinks will then route through
        // loadExtractor/YstreamExtractor by host.
        val iframeSrc = doc.selectFirst("div.video-player iframe, div.video-player-area iframe, .video-player iframe")?.attr("src").orEmpty()
        val data = iframeSrc.ifBlank { url }

        return newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = plotText
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

        val host = data.substringAfter("://", "").substringBefore('/').lowercase()

        // streamtape.to has no built-in extractor (CS ships .com/.net/.xyz),
        // so it is handled before the generic loadExtractor pass.
        if (host.startsWith("streamtape.")) {
            StreamTapeToExtractor().getUrl(data, data, subtitleCallback, callback)
            return true
        }

        // vidara.to / doodstream.com / playmogo.com and friends are all
        // covered by CloudStream's built-in extractors — let them try first,
        // they match on host name. The Byse regex below matches any
        // /e/<code> path and would otherwise wrongly capture them.
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Nothing claimed it: treat as Byse/ystream family, matched on the
        // embed code path so a rotated host still routes here instead of
        // returning "no links found".
        return when {
            data.contains("ystream") || data.contains("byse") ||
                Regex("""https?://[^/]+/(?:e|v|d)/[A-Za-z0-9]+""").containsMatchIn(data) -> {
                YstreamExtractor().getUrl(data, data, subtitleCallback, callback)
                true
            }
            else -> false
        }
    }

    companion object {
        private const val TAG = "JavSegar"
    }
}
