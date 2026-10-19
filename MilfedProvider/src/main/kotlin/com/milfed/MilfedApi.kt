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

    const val SITE = "https://www.milfed.com"
    const val API = "https://site-api.project1service.com"

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
        instanceToken?.let { return it }

        val request = Request.Builder()
            .url("$SITE/scenes")
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
        val url = if (query.isEmpty()) "$API$path" else "$API$path?$query"

        var token = ensureToken()
        if (token == null) return null

        repeat(2) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Referer", "$SITE/scenes")
                .header("Instance", token!!)
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
    @JsonProperty("length")
    var length: Int = 0

    @JsonProperty("files")
    var files: Map<String, MilfedFile> = emptyMap()
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal class MilfedFile {
    @JsonProperty("format")
    var format: String = ""

    @JsonProperty("sizeBytes")
    var sizeBytes: Long = 0

    @JsonProperty("urls")
    var urls: Map<String, String> = emptyMap()
}
