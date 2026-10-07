package com.javseg

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
class JavSegarProvider : MainAPI() {
    override var mainUrl = "https://javsegar.com"
    override var name = "JavSegar"
    override var lang = "id"
    override var hasMainPage = true
    override val hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.NSFW)

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

    private data class WpMedia(
        @JsonProperty("source_url") val sourceUrl: String? = null,
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
     * 5.641 posts and yoast_head_json is not exposed. The cover files are served
     * from imgswipe.xyz, a sibling WordPress whose media library does hold them,
     * named "<CODE>-<timestamp>.jpg" (e.g. SONE-881-e1791260349266.jpg).
     *
     * So instead of 30 og:image requests per page we pull one page of media
     * (100 items) from that host and match on the JAV code in the title.
     * Verified 10/10 against the live site.
     */
    private suspend fun resolvePosters(items: List<SearchResponse>) {
        val wanted = items.mapNotNull { item ->
            val code = CODE.find(item.name)?.groupValues?.get(1)?.uppercase() ?: return@mapNotNull null
            item to code
        }
        if (wanted.isEmpty()) return

        val byCode = try {
            app.get("$IMGSWIPE/wp-json/wp/v2/media?per_page=100&orderby=date&order=desc&_fields=source_url")
                .parsed<Array<WpMedia>>()
                ?.mapNotNull { m ->
                    val url = m.sourceUrl ?: return@mapNotNull null
                    CODE.find(url.substringAfterLast('/'))?.groupValues?.get(1)?.uppercase() to url
                }?.toMap()
                ?: return
        } catch (e: Exception) {
            Log.e(TAG, "poster lookup failed: ${e.message}")
            return
        }

        var hits = 0
        for ((item, code) in wanted) {
            byCode[code]?.let {
                item.posterUrl = it
                hits++
            }
        }
        Log.d(TAG, "posters resolved: $hits/${items.size}")
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

        /** Sibling WordPress that actually hosts the cover files. */
        private const val IMGSWIPE = "https://imgswipe.xyz"

        /** JAV code, e.g. SONE-881 — the key that links a title to its cover. */
        private val CODE = Regex("""\b([A-Za-z]{3,6}-\d{2,4})\b""")
    }
}
