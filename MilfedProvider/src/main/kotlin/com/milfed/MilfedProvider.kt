package com.milfed

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * milfed.com - "Top Mature Milf Porn Videos".
 *
 * The site is a project1content white-label (the same platform behind a lot of
 * adult tube sites). It is a React SPA: the server-rendered HTML carries only
 * a teaser of the catalogue, so the plugin talks to the same JSON API the SPA
 * uses:
 *
 *   GET https://site-api.project1service.com/v2/releases
 *       ?adaptiveStreamingOnly=false&orderBy=-dateReleased
 *       &type=scene&limit=20&offset=<n>
 *   header: Instance: <jwt from the site's instance_token cookie>
 *
 * Everything CloudStream needs is in that one response: title, description,
 * actors, tags, poster and the playable preview. Pagination is a plain
 * offset/limit pair and was verified to return disjoint id sets for
 * offset 0 / 20 / 40.
 *
 * The catalogue holds 1331 scenes and 1184 actors.
 *
 * Playback: the full video is behind a subscription (isMemberUnlocked=false
 * and no file list for it), so what the API hands out is the public preview
 * under videos.med iabook.files.*.urls.view - a 20 second 720p/320p MP4.
 * That is what the site itself shows to a visitor who is not a member, and it
 * is the only stream that can be resolved without an account.
 *
 * The site also has 256 "movies", but their API entry carries an empty
 * videos array and a cover instead of a poster, so there is nothing to play
 * and they are not listed here.
 */
class MilfedProvider(context: Context? = null) : MainAPI() {
    init {
        // The stored member session decides whether the API hands over the
        // full video or the 20 second preview.
        context?.let { ctx ->
            val stored = ctx.getSharedPreferences("milfed", Context.MODE_PRIVATE)
                .getString("session", null)
            if (!stored.isNullOrBlank()) {
                MilfedApi.memberSession = stored
                Log.d(TAG, "member session loaded (${stored.length} chars)")
            }
        }
    }

    override var mainUrl = MilfedApi.site
    override var name = "Milfed"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = true

    private companion object {
        private const val TAG = "MilfedProvider"

        /** Page size of one API page. 20 keeps a page well inside the time cap. */
        private const val PAGE_SIZE = 20
    }

    /**
     * Home tabs. The pair is (data, name): the first element is the section
     * id that selects the API query, the second is the label CloudStream
     * shows on the tab.
     *
     * Each section is a real API ordering or a real filter, and each was
     * checked to return a different, non-empty id set:
     *   Latest   orderBy=-dateReleased          1331 items
     *   Populer  orderBy=-stats.views            1331 items
     *   MILF     tagId=79 (the MILF tag)         1301 items
     *   Model    type=actor /v1/actors            1184 items
     */
    override val mainPage = mainPageOf(
        "latest" to "Latest",
        "popular" to "Populer",
        "milf" to "MILF",
        "actors" to "Model",
    )

    /**
     * The API query for a section.
     *
     * The filters use different parameter names per dimension and they are
     * not interchangeable: tagId=79 narrows to 1301 items, actorId=58630 to
     * 50, while a bare "tags=79" is ignored and still returns 1331. That is
     * why every section carries its own parameter map instead of a shared
     * filter helper.
     */
    private fun queryFor(section: String): Map<String, String> = when (section) {
        "popular" -> mapOf("orderBy" to "-stats.views")
        "milf" -> mapOf("orderBy" to "-dateReleased", "tagId" to "79")
        else -> mapOf("orderBy" to "-dateReleased")
    }

    /**
     * The image of one release.
     *
     * Every image group is keyed by image index first ("0".."5") and by size
     * second ("xs".."xx"), with "alternateText" and "imageVersion" mixed into
     * the same object as plain strings. "md" is the smallest bucket that
     * still looks sharp on a TV, and every scene carries it. The map
     * iteration order is not stable, so the index is picked by name and the
     * size bucket by name, never by "first entry".
     */
    private fun imageOf(group: Map<String, Any>?): String? {
        if (group == null) return null
        // The index buckets are the entries whose key is a number; the other
        // two ("alternateText", "imageVersion") are plain strings.
        val buckets = group.entries
            .filter { it.key.toIntOrNull() != null }
            .sortedBy { it.key.toInt() }
        for ((_, value) in buckets) {
            val bucket = value as? Map<*, *> ?: continue
            for (size in listOf("md", "lg", "sm", "xs")) {
                val entry = bucket[size] as? Map<*, *> ?: continue
                val url = entry["url"]?.toString().orEmpty()
                if (url.startsWith("http")) return url
            }
        }
        return null
    }

    private fun toSearch(release: MilfedRelease): SearchResponse? {
        val title = release.title.trim()
        if (title.isEmpty()) return null
        val url = "$mainUrl/scene/${release.id}"
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = imageOf(release.images?.poster)
        }
    }

    private fun toActorSearch(actor: MilfedActor): SearchResponse? {
        val name = actor.name.trim()
        if (name.isEmpty()) return null
        val url = "$mainUrl/model/${actor.id}"
        return newMovieSearchResponse(name, url, TvType.NSFW) {
            posterUrl = imageOf(actor.profileImage())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data carries the section id, request.name the tab label.
        val section = request.data
        val offset = (page - 1) * PAGE_SIZE

        if (section == "actors") {
            val pageData = MilfedApi.actors(limit = PAGE_SIZE, offset = offset)
            val items = pageData.result.mapNotNull { toActorSearch(it) }
            Log.d(
                TAG,
                "section=actors page=$page offset=$offset -> ${items.size} items " +
                    "of ${pageData.meta?.total ?: 0}, " +
                    "${items.count { !it.posterUrl.isNullOrBlank() }} with poster"
            )
            return newHomePageResponse(request.name, items)
        }

        val query = queryFor(section)
        val pageData = MilfedApi.releases(
            type = "scene",
            limit = PAGE_SIZE,
            offset = offset,
            orderBy = query["orderBy"] ?: "-dateReleased",
            extra = query.filterKeys { it != "orderBy" }
        )

        val items = pageData.result.mapNotNull { toSearch(it) }
        Log.d(
            TAG,
            "section=$section page=$page offset=$offset -> ${items.size} items " +
                "of ${pageData.meta?.total ?: 0}, " +
                "${items.count { !it.posterUrl.isNullOrBlank() }} with poster"
        )
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val pageData = MilfedApi.releases(
            type = "scene",
            limit = 30,
            offset = 0,
            orderBy = "-dateReleased",
            extra = mapOf("search" to query)
        )
        val items = pageData.result.mapNotNull { toSearch(it) }
        Log.d(TAG, "search '$query' -> ${items.size} items")
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        // The permalink is /scene/<id>/<slug> for a scene and
        // /model/<id>/<slug> for an actor; the slug is decorative, so only
        // the id matters.
        val id = Regex("""/(?:scene|movie|model)/(\d+)""").find(url)
            ?.groupValues?.get(1)?.toLongOrNull()
        if (id == null) {
            Log.e(TAG, "load: no release id in $url")
            return null
        }

        // An actor page lists that actor's scenes; the site serves them under
        // /model/<id>, so the release list is filtered by actorId.
        if (Regex("""/model/(\d+)""").containsMatchIn(url)) {
            val pageData = MilfedApi.releases(
                type = "scene",
                limit = 40,
                offset = 0,
                orderBy = "-dateReleased",
                extra = mapOf("actorId" to id.toString())
            )
            val items = pageData.result.mapNotNull { toSearch(it) }
            Log.d(TAG, "load: actor $id -> ${items.size} scenes")
            if (items.isEmpty()) return null
            val actorName = items.first().name
            return newMovieLoadResponse(actorName, url, TvType.NSFW, url) {
                this.posterUrl = items.first().posterUrl
                this.plot = "Model: $actorName"
                this.tags = listOf("Model")
            }
        }

        val release = MilfedApi.release(id)
        if (release == null) {
            Log.e(TAG, "load: release $id not found")
            return null
        }

        val title = release.title.trim()
        val plot = release.description.trim()
        val actorNames = release.actors.map { it.name }.filter { it.isNotBlank() }
        val tagNames = release.tags.map { it.name }.filter { it.isNotBlank() }
        val poster = imageOf(release.images?.poster).orEmpty()

        // The data handed to loadLinks is the release id; the stream itself
        // is resolved there from the API, so the player never has to parse
        // the site markup.
        return newMovieLoadResponse(title, url, TvType.NSFW, id.toString()) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = (actorNames + tagNames).distinct()
            this.year = release.dateReleased.take(4).toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.toLongOrNull()
        if (id == null) {
            Log.e(TAG, "loadLinks: bad data '$data'")
            return false
        }

        val release = MilfedApi.release(id)
        if (release == null) {
            Log.e(TAG, "loadLinks: release $id not found")
            return false
        }

        val book = release.preview()
        val files = book?.files ?: emptyMap()
        if (files.isEmpty()) {
            Log.e(TAG, "loadLinks: release $id has no playable file")
            return false
        }

        // Highest quality first: 720p, then 320p.
        val ordered: List<Pair<String, MilfedFile>> =
            listOf("720p", "320p").mapNotNull { key ->
                files[key]?.let { key to it }
            }.ifEmpty {
                files.entries.sortedByDescending { it.value.sizeBytes }.map { it.key to it.value }
            }

        val referer = "${MilfedApi.site}/scene/$id"
        var emitted = 0
        for ((key, file) in ordered) {
            // A member session gets the whole file: the site puts it in
            // "downloadUrl" and leaves the 20 second trailer in "urls.view".
            // An anonymous session has no downloadUrl at all, so the trailer
            // is the only thing there is.
            val full = file.downloadUrl?.takeIf { it.startsWith("http") }
            val preview = file.urls["view"]?.takeIf { it.startsWith("http") }
                ?: file.urls["download"]?.takeIf { it.startsWith("http") }
                ?: file.urls.values.firstOrNull { it.startsWith("http") }
            val url = full ?: preview ?: continue
            val isFull = full != null
            val label = if (isFull) "Milfed $key" else "Milfed $key (preview)"
            Log.d(
                TAG,
                "loadLinks: $label -> ${file.sizeBytes / 1024} KB" +
                    " cdn=${file.multiCdnId ?: book?.multiCdnId}" +
                    " length=${book?.length}s" +
                    (if (isFull) " (full file)" else " (trailer only - a member session is needed for the full video)")
            )
            callback(
                newExtractorLink(
                    label,
                    label,
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                        "Referer" to referer
                    )
                }
            )
            emitted++
        }

        Log.d(TAG, "loadLinks: release $id emitted $emitted link(s)")
        return emitted > 0
    }
}
