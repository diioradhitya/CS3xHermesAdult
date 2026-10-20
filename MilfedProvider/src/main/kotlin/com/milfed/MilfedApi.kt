package com.milfed

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Client for the project1content white-label API that milfed.com runs on.
 *
 * The site is a React SPA: the visible HTML carries only a teaser, while the
 * real catalogue comes from site-api.project1service.com. That API refuses
 * every request that does not carry the instance token, and the token is
 * handed out by the site itself:
 *
 *   GET https://www.milfed.com/scenes
 *     -> Set-Cookie: instance_token=<jwt>; Max-Age=86400; Path=/
 *
 * The SPA then copies that cookie value into an "Instance" request header -
 * not "x-instance-token", and not a query parameter. Verified live:
 *
 *   Instance: <jwt>            -> 200 {"meta":{...},"result":[...]}
 *   x-instance-token: <jwt>    -> 400 "Instance token is missing or not valid"
 *   Authorization: <jwt>       -> 400 "Instance token is missing or not valid"
 *
 * The JWT payload carries {"iss":"mindgeek","brand":"milfed",...} and lives
 * about 30 hours, so it is cached and only refreshed when the API starts
 * answering 400.
 */
internal object MilfedApi {
    private const val TAG = "MilfedApi"

    /**
     * The site and the catalogue API. They are `var` so the live test can
     * point them at a local mock server - the real hosts are the defaults.
     */
    @Volatile
    var site: String = "https://www.milfed.com"

    @Volatile
    var api: String = "https://site-api.project1service.com"

    /**
     * The member session, as the cookie string the browser shows, e.g.
     *
     *   instance_token=eyJ...; app_session_id=6d6eda9c-...; __s=Ab3...
     *
     * The site hands out the full file only to a member session, so this is
     * what turns the 20 second preview into the whole video. It is the
     * user's own session - the plugin never creates or guesses one.
     */
    @Volatile
    var memberSession: String? = null

    /** The instance token of the member session, if the session carries one. */
    private fun sessionValue(name: String): String? {
        val raw = memberSession ?: return null
        raw.split(';').forEach { part ->
            val kv = part.trim().split('=')
            if (kv.size >= 2 && kv[0].trim() == name) {
                val value = kv.drop(1).joinToString("=").trim()
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    internal val mapper = ObjectMapper()

    /** Cached instance token; null until the first successful handshake. */
    @Volatile
    private var instanceToken: String? = null

    /**
     * Fetch the instance token from the site.
     *
     * It is a Set-Cookie on the site response, and the same value is what the
     * SPA hands to the API, so grab it straight out of the response headers.
     */
    @Synchronized
    private fun ensureToken(): String? {
        // A member session carries its own instance token, and that is the
        // one the API has to see for the session to count.
        sessionValue("instance_token")?.let {
            Log.d(TAG, "using the instance token from the member session")
            return it
        }
        instanceToken?.let { return it }

        val request = Request.Builder()
            .url("$site/scenes")
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val token = response.headers.values("set-cookie")
                    .mapNotNull { cookie ->
                        // "instance_token=<jwt>; Max-Age=86400; Path=/"
                        val value = cookie.split(';').firstOrNull()?.trim() ?: return@mapNotNull null
                        if (!value.startsWith("instance_token=")) return@mapNotNull null
                        value.removePrefix("instance_token=").trim()
                    }
                    .firstOrNull { it.isNotBlank() }

                if (token.isNullOrBlank()) {
                    Log.e(TAG, "no instance_token cookie in the site response")
                    null
                } else {
                    Log.d(TAG, "got instance token (${token.length} chars)")
                    instanceToken = token
                    token
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "token fetch failed: ${e.message}")
            null
        }
    }

    /**
     * GET a JSON document from the catalogue API and hand back the raw tree.
     *
     * The response envelope is not stable: /v2/releases?… answers with
     * "result" as an array, while /v2/releases/<id> answers with "result" as a
     * single object. Rather than model both shapes, the tree is returned raw
     * and the caller picks the part it needs.
     */
    private fun getTree(path: String, params: Map<String, String> = emptyMap()): JsonNode? {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val url = if (query.isEmpty()) "$api$path" else "$api$path?$query"

        var token = ensureToken()
        if (token == null) return null

        repeat(2) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Referer", "$site/scenes")
                .header("Instance", token!!)
                .apply {
                    // The member session identifies the account. The SPA sends
                    // the app session id as its own header and the cookies
                    // alongside it.
                    sessionValue("app_session_id")?.let { header("X-APP-SESSION-ID", it) }
                    memberSession?.let { header("Cookie", it) }
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return try {
                            mapper.readTree(body)
                        } catch (e: Exception) {
                            Log.e(TAG, "parse failed for $url: ${e.message}")
                            null
                        }
                    }
                    // 400 with the instance error means the token went stale.
                    Log.w(TAG, "HTTP ${response.code} for $url: ${body.take(160)}")
                    if (response.code == 400 && body.contains("Instance", ignoreCase = true)) {
                        instanceToken = null
                        token = ensureToken()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GET $url failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * The size of a media url in bytes, or null when it cannot be measured.
     *
     * The site does not say which of "urls.view" and "downloadUrl" is the
     * whole file - the two are different CDN paths - so the answer has to be
     * measured, and it has to be measured without downloading anything.
     *
     * A range request for a single byte does that: the server answers 206
     * with `Content-Range: bytes 0-0/<total>`, which carries the size of the
     * whole file. If the server ignores the range and answers 200, the
     * Content-Length is the total and the body is never read, so nothing is
     * downloaded either way.
     */
    fun measure(url: String): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", UA)
                .header("Referer", "$site/scenes")
                .build()
            client.newCall(request).execute().use { response ->
                val range = response.header("Content-Range")
                val total = range?.substringAfter('/', "")?.substringBefore('*')?.trim()?.toLongOrNull()
                val len = total ?: response.header("Content-Length")?.toLongOrNull()
                Log.d(TAG, "measure $url -> ${response.code} len=$len")
                len
            }
        } catch (e: Exception) {
            Log.e(TAG, "measure $url failed: ${e.message}")
            null
        }
    }

    /**
     * The biggest of the candidate urls, by measured size.
     *
     * Falls back to the first candidate when none of them can be measured, so
     * a CDN that refuses HEAD does not break playback.
     */
    fun pickLargest(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        var best: String? = null
        var bestSize = -1L
        for (candidate in candidates) {
            val size = measure(candidate) ?: continue
            if (size > bestSize) {
                bestSize = size
                best = candidate
            }
        }
        return best ?: candidates.first()
    }

    /** The "meta" block of an envelope, or null. */
    private fun metaOf(tree: JsonNode?): MilfedMeta? {
        val node = tree?.get("meta") ?: return null
        return try {
            mapper.treeToValue(node, MilfedMeta::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** The "result" array of an envelope. */
    private fun resultOf(tree: JsonNode?): List<MilfedRelease> {
        val node = tree?.get("result") ?: return emptyList()
        if (!node.isArray) return emptyList()
        return try {
            mapper.readerForListOf(MilfedRelease::class.java).readValue<List<MilfedRelease>>(node)
        } catch (e: Exception) {
            Log.e(TAG, "result parse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * The list endpoint the SPA itself calls:
     * /v2/releases?type=scene&limit=..&offset=..
     */
    fun releases(
        type: String = "scene",
        limit: Int = 20,
        offset: Int = 0,
        orderBy: String = "-dateReleased",
        extra: Map<String, String> = emptyMap()
    ): MilfedPage {
        val params = linkedMapOf(
            "adaptiveStreamingOnly" to "false",
            "orderBy" to orderBy,
            "type" to type,
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )
        params.putAll(extra)
        val tree = getTree("/v2/releases", params)
        return MilfedPage(resultOf(tree), metaOf(tree))
    }

    /**
     * Release detail for one scene / movie.
     *
     * /v2/releases/<id> answers with "result" as a single object, not as an
     * array like the list endpoint does.
     */
    fun release(id: Long): MilfedRelease? {
        val tree = getTree("/v2/releases/$id") ?: return null
        val node = tree.get("result") ?: return null
        if (node.isArray) {
            return resultOf(tree).firstOrNull()
        }
        return try {
            mapper.treeToValue(node, MilfedRelease::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "release $id parse failed: ${e.message}")
            null
        }
    }

    /** Actor listing: /v1/actors?limit=..&offset=.. */
    fun actors(limit: Int = 20, offset: Int = 0): MilfedActorPage {
        val tree = getTree("/v1/actors", mapOf("limit" to limit.toString(), "offset" to offset.toString()))
        val node = tree?.get("result")
        val list = if (node != null && node.isArray) {
            try {
                mapper.readerForListOf(MilfedActor::class.java).readValue<List<MilfedActor>>(node)
            } catch (e: Exception) {
                Log.e(TAG, "actor parse failed: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        return MilfedActorPage(list, metaOf(tree))
    }
}

/* ------------------------------------------------------------------ */
/* Response model                                                      */
/* ------------------------------------------------------------------ */

internal class MilfedPage(
    val result: List<MilfedRelease>,
    val meta: MilfedMeta?
)

internal class MilfedActorPage(
    val result: List<MilfedActor>,
    val meta: MilfedMeta?
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedMeta {
    @JsonProperty("count")
    var count: Int = 0

    @JsonProperty("total")
    var total: Int = 0
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedRelease {
    @JsonProperty("id")
    var id: Long = 0

    @JsonProperty("type")
    var type: String = "scene"

    @JsonProperty("title")
    var title: String = ""

    @JsonProperty("description")
    var description: String = ""

    @JsonProperty("dateReleased")
    var dateReleased: String = ""

    @JsonProperty("brand")
    var brand: String = ""

    @JsonProperty("privacy")
    var privacy: String = "public"

    @JsonProperty("isMemberUnlocked")
    var isMemberUnlocked: Boolean = false

    @JsonProperty("canPlay")
    var canPlay: Boolean = false

    @JsonProperty("actors")
    var actors: List<MilfedActor> = emptyList()

    @JsonProperty("tags")
    var tags: List<MilfedTag> = emptyList()

    @JsonProperty("images")
    var images: MilfedImages? = null

    /**
     * "videos" is an object for scenes ({"mediabook": {...}}) but an array
     * for movies, and the movies' array is empty because a movie has no
     * public preview at all. It is therefore read as a raw node and the
     * mediabook is picked out of it by hand.
     */
    @JsonProperty("videos")
    var videos: JsonNode? = null

    /** The public preview of this release, or null when there is none. */
    fun preview(): MilfedMediabook? {
        val node = videos ?: return null
        if (node.isArray) return null
        val book = node.get("mediabook") ?: return null
        return try {
            MilfedApi.mapper.treeToValue(book, MilfedMediabook::class.java)
        } catch (e: Exception) {
            null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedActor {
    @JsonProperty("id")
    var id: Long = 0

    @JsonProperty("name")
    var name: String = ""

    @JsonProperty("gender")
    var gender: String = ""

    /**
     * "images" is an object in the /v1/actors listing ({"profile": {...}})
     * but an empty array inside a release, so it is read as a raw node and
     * only used when it really is an object.
     */
    @JsonProperty("images")
    var images: JsonNode? = null

    /** The profile picture, when this actor came from /v1/actors. */
    fun profileImage(): Map<String, Any>? {
        val node = images ?: return null
        if (!node.isObject) return null
        val group = node.get("profile") ?: return null
        if (!group.isObject) return null
        return try {
            MilfedApi.mapper.readValue<Map<String, Any>>(
                group.toString(),
                object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {}
            )
        } catch (e: Exception) {
            null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedTag {
    @JsonProperty("id")
    var id: Long = 0

    @JsonProperty("name")
    var name: String = ""
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedImages {
    /**
     * Every image group is keyed by image index first ("0".."5") and by size
     * second ("xs".."xx"), but the same object also carries plain string
     * entries ("alternateText", "imageVersion"), so the map is heterogeneous
     * and the reader has to skip anything that is not a nested object.
     */
    @JsonProperty("poster")
    var poster: Map<String, Any>? = null

    /** Movies carry "cover" instead of "poster". */
    @JsonProperty("cover")
    var cover: Map<String, Any>? = null

    /** Actors carry "profile". */
    @JsonProperty("profile")
    var profile: Map<String, Any>? = null
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedMediabook {
    /**
     * The runtime of the whole video in seconds. A preview file is a couple
     * of megabytes for a 40 minute scene, so this is what says how much is
     * actually there.
     */
    @JsonProperty("length")
    var length: Int = 0

    @JsonProperty("files")
    var files: Map<String, MilfedFile> = emptyMap()

    @JsonProperty("multiCdnId")
    var multiCdnId: String? = null
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedFile {
    @JsonProperty("format")
    var format: String = ""

    @JsonProperty("sizeBytes")
    var sizeBytes: Long = 0

    @JsonProperty("urls")
    var urls: Map<String, String> = emptyMap()

    /**
     * The full file. The site's own download menu filters on exactly this
     * field - `files.filter(e => e.downloadUrl && ...)` - and an anonymous
     * response has no `downloadUrl` at all, only `urls.view` pointing at the
     * 20 second trailer.
     */
    @JsonProperty("downloadUrl")
    var downloadUrl: String? = null

    /** "standardTrailersCdn" for a preview; a different id for the full file. */
    @JsonProperty("multiCdnId")
    var multiCdnId: String? = null

    @JsonProperty("type")
    var type: String? = null
}
