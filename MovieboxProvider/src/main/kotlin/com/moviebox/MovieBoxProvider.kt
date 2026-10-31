package com.moviebox

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

private const val BEST_QUALITY = 1080
private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
private const val REFERER = "https://movieboxfilm.com/"
private const val API_BASE = "https://api.movieboxfilm.com/api"
private const val TAG = "MovieBox"

public class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://movieboxfilm.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true
    override var lang = "id"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override val mainPage = listOf(
        MainPageData("all", "Semua", false),
        MainPageData("popular", "Populer", false),
        MainPageData("trending", "Trending", false),
        MainPageData("new-releases", "Rilis Terbaru", false)
    )

    private fun playRetry(path: String, body: String, retries: Int = 3): JSONObject? = try {
        val req = Request.Builder()
            .url(API_BASE + path)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val json = r.body?.string()?.let { JSONObject(it) }
            if (json != null && !json.optBoolean("error", false)) json else null
        }
    } catch (e: Exception) {
        if (retries > 1) playRetry(path, body, retries - 1) else null
    }

    private suspend fun getPlayUrl(detailPath: String, param: Map<String, String>): String? {
        val ep = param["ep"]?.toIntOrNull() ?: 1
        val q = param["q"]?.takeIf { it.isNotBlank() } ?: "1080p"
        val body = JSONObject().put("id", detailPath).put("episode", ep).put("quality", q)
        val bodyStr = body.toString()
        val data = playRetry("/movie/play", bodyStr) ?: return null
        return data.optString("url").ifBlank { null }
    }

    private fun getPlayerInfo(pageData: String): Pair<String?, String?>? {
        val playerId = strAfter(pageData, "playerId")?.substringBefore("\"") ?: return null
        val playData = strAfter(pageData, "playData")?.substringBefore("\"") ?: return null
        var clean = playData.replace("&quot;", "\"").replace("&#39;", "'").trim()
        if (clean.startsWith("\"") && clean.endsWith("\"")) clean = clean.substring(1, clean.length - 1)
        clean = clean.replace("\\\"", "\"")
        return Pair(playerId, clean)
    }

    private fun getText(url: String, referer: String? = null): String? = try {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    } catch (e: Exception) { null }

    private fun strAfter(data: String?, name: String): String? {
        if (data == null) return null
        val idx = data.indexOf("\"" + name + "\":\"")
        if (idx == -1) return null
        var rest = data.substring(idx + name.length + 3)
        if (rest.startsWith("\"")) rest = rest.substring(1)
        val endIdx = rest.indexOf("\"")
        return if (endIdx == -1) rest else rest.substring(0, endIdx)
    }

    private fun parseItemUrl(data: String): Triple<String, String, Map<String, String>> {
        val parts = data.split("|")
        val platform = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1) ?: ""
        val params = mutableMapOf<String, String>()
        if (parts.size > 2) {
            parts[2].split("&").forEach { pair ->
                val split = pair.split("=", limit = 2)
                if (split.size == 2) params[split[0]] = split[1]
            }
        }
        return Triple(platform, id, params)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (platform, id, params) = parseItemUrl(data)

        // Fixed: use /detail/ path for MovieBoxFilm website
        val pageUrl = "https://movieboxfilm.com/detail/$id"

        // Try API for movie/anime first
        when (platform) {
            "movie" -> return tryGetLink(id, params, pageUrl, callback)
            "anime" -> return tryGetLink(id, params, pageUrl, callback)
            else -> return tryPageExtraction(pageUrl, id, callback)
        }
    }

    private suspend fun tryGetLink(
        id: String,
        params: Map<String, String>,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = getPlayUrl(id, params)
        if (url != null) {
            val link = newExtractorLink("MovieBox", id, url, ExtractorLinkType.VIDEO) {
                this.referer = REFERER
                this.quality = BEST_QUALITY
            }
            callback(link)
            return true
        }
        return tryPageExtraction(pageUrl, id, callback)
    }

    private suspend fun tryPageExtraction(pageUrl: String, id: String, callback: (ExtractorLink) -> Unit): Boolean {
        val pageData = getText(pageUrl, "https://movieboxfilm.com/") ?: return false
        val playerId = strAfter(pageData, "playerId")?.substringBefore("\"") ?: return false
        val playData = strAfter(pageData, "playData")?.substringBefore("\"") ?: return false
        var clean = playData.replace("&quot;", "\"").replace("&#39;", "'").trim()
        if (clean.startsWith("\"") && clean.endsWith("\"")) clean = clean.substring(1, clean.length - 1)
        clean = clean.replace("\\\"", "\"")
        callback(
            newExtractorLink("MovieBox", id, "https://movieboxfilm.com/embed/$playerId", ExtractorLinkType.VIDEO) {
                this.referer = REFERER
                this.quality = BEST_QUALITY
            }
        )
        return true
    }

    public companion object {
        fun attachContext(context: Context) {
            // context stored for potential use; not modifying SDK's app singleton
        }
    }
}
