package com.javseg

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * javsegar.com is a WordPress site whose media library is effectively empty:
 * `featured_media` is 0 for all 5.641 posts and the REST payload contains not a
 * single image URL. Every earlier version (v8-v11) therefore tried to repair
 * posters with oEmbed + og:image fallback, which cost ~21 s of serial requests
 * per home page and still resolved 0/30 on the archive tabs.
 *
 * The covers do exist - just not in REST. They live in the listing markup as
 * `data-main-thumb` on each `article` card, on a media host that serves them
 * with a plain OkHttp GET: no Referer, no cookies, no challenge (verified 200
 * image/jpeg with and without a Referer). That is exactly what the browser sees,
 * which is why banners show there and were grey in CloudStream.
 *
 * So v12 reads the category archive directly: one request yields title, link and
 * poster together, for all 32 cards.
 *
 * Tabs are real orderings of that same archive, each verified to hold across
 * pages (page 1 and page 2 share no post ids):
 *   Latest   -                             ids 197919 197917 197915
 *   Terlama  - ?orderby=date&order=asc     ids  32054  32058  32061
 *   A - Z    - ?orderby=title&order=asc    ids  95496  95509 104141
 *   Z - A    - ?orderby=title&order=desc   ids 144183  62033 197014
 *   Populer  - ?filter=popular              ids 195983 171034 197140
 *
 * Note the front page ignores /page/N/ entirely (page 1 == page 188), so the
 * category archive is the only paginated listing on this site.
 */
class JavSegarProvider : MainAPI() {
    override var mainUrl = "https://javsegar.com"
    override var name = "JavSegar"
    override var lang = "id"
    override var hasMainPage = true
    override val hasDownloadSupport = true
    override var supportedTypes = setOf(TvType.NSFW)

    /** The site exposes a single real category, so it is the one listing that paginates. */
    private val archive = "$mainUrl/category/bokep-jepang"

    override val mainPage = mainPageOf(
        "$archive/" to "Latest",
        "$archive/?orderby=date&order=asc" to "Terlama",
        "$archive/?orderby=title&order=asc" to "A - Z",
        "$archive/?orderby=title&order=desc" to "Z - A",
        "$archive/?filter=popular" to "Populer",
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        // Keep the ordering parameter, otherwise page 2 silently returns page 1.
        val q = base.substringAfter('?', "")
        val path = base.substringBefore('?').removeSuffix("/")
        return "$path/page/$page/" + if (q.isBlank()) "" else "?$q"
    }

    /**
     * One card out of the listing markup. Everything CloudStream needs is on the
     * same element: the title on the anchor's title attribute, the permalink on
     * its href, and the cover in data-main-thumb.
     */
    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = a.attr("title").ifBlank { selectFirst("span")?.text().orEmpty() }
            .ifBlank { return null }
        val poster = attr("data-main-thumb").ifBlank {
            selectFirst("img")?.attr("data-main-thumb").orEmpty()
        }
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            if (poster.startsWith("http")) posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = listPosts(pageUrl(request.data, page))
        Log.d(TAG, "tab '${request.name}' page $page -> ${items.size} items, " +
            "${items.count { !it.posterUrl.isNullOrBlank() }} with poster")
        return newHomePageResponse(request.name, items)
    }

    private suspend fun listPosts(url: String): List<SearchResponse> = try {
        app.get(url).document.select("article").mapNotNull { it.toSearch() }
    } catch (e: Exception) {
        Log.e(TAG, "listPosts failed for $url: ${e.message}")
        emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The listing markup carries the cover; REST search does not, so search
        // the HTML. Verified live: /?s=JUL-416 returns 1 card with 1 poster.
        return listPosts("$mainUrl/?s=${query.replace(" ", "+")}")
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
        // the page has no iframe at all - loadLinks will then route through
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
        // covered by CloudStream's built-in extractors - let them try first,
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