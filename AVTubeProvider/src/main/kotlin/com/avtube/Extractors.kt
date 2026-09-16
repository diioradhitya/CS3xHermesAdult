package com.avtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import android.util.Log

/**
 * VidHide / EarnVids family extractor (morencius.com, dingtezuni.com, ...).
 * Fully self-contained Dean Edwards unpacker — does NOT rely on CloudStream's
 * getAndUnpack() which fails on the pipe-separated k dictionary used by these embeds.
 *
 * Flow:
 *  1. Fetch embed page
 *  2. Locate `eval(function(p,a,c,k,e,d){...}('...', base, count, 'k1|k2|...', 0, {...}))`
 *  3. Unpack: replace \b<base36(i)>\b tokens with dictionary entry k[i]
 *  4. Extract `links.hlsN` URLs from the unpacked JS (full + relative)
 *  5. Emit m3u8 links
 */
open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val embedUrl = getEmbedUrl(url)
        val response = app.get(embedUrl, referer = referer)
        val script = response.text

        // DEBUG LOG
        Log.d("AVTubeDebug", "embedUrl=$embedUrl, scriptLen=${script.length}")

        // try self-contained unpack first
        val unpacked = unpackMorencius(script)
        Log.d("AVTubeDebug", "unpacked=${unpacked != null}, len=${unpacked?.length ?: 0}")
        var emitted = false
        if (unpacked != null) {
            // prefer hls links in order: hls4(highest) .. hls1, then any m3u8 in links object
            val linkRegex = Regex("\\\"hls(\\\\d)\\\":\\\\s*\\\"([^\\\"]+)\\\"")
            val found = linkRegex.findAll(unpacked).map { it.groupValues[2] }.toList()
            Log.d("AVTubeDebug", "found hls entries: ${found.size} -> $found")
            for (rawUrl in found) {
                val fullUrl = toAbs(rawUrl, embedUrl)
                Log.d("AVTubeDebug", "try generateM3u8 for: $fullUrl")
                val m3u8 = generateM3u8(name, fullUrl, referer = "$mainUrl/", headers = headers)
                if (m3u8.isNotEmpty()) {
                    m3u8.forEach(callback)
                    emitted = true
                }
            }
            if (!emitted) {
                // fallback: any "url" containing m3u8
                Regex("\\\"((?:https?:)?//[^\\\"]*?m3u8[^\\\"]*)\\\"").findAll(unpacked).forEach { match ->
                    val fullUrl = toAbs(match.groupValues[1], embedUrl)
                    Log.d("AVTubeDebug", "fallback try generateM3u8 for: $fullUrl")
                    generateM3u8(name, fullUrl, referer = "$mainUrl/", headers = headers).forEach(callback)
                    emitted = true
                }
            }
        }

        // legacy fallback: inline script with sources:
        if (!emitted) {
            val legacy = response.document.selectFirst("script:containsData(sources:)")?.data()
            if (!legacy.isNullOrEmpty()) {
                Regex(":\\\\s*\\\"(.*?m3u8.*?)\\\"").findAll(legacy).forEach { match ->
                    generateM3u8(
                        name,
                        fixUrl(match.groupValues[1]),
                        referer = "$mainUrl/",
                        headers = headers
                    ).forEach(callback)
                    emitted = true
                }
            }
        }
        Log.d("AVTubeDebug", "emitted=$emitted")
    }

    private fun toAbs(u: String, embedUrl: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> {
            val base = Regex("https?://[^/]+").find(embedUrl)?.value ?: mainUrl
            "$base$u"
        }
        else -> fixUrl(u)
    }

    /**
     * Dean Edwards unpacker that handles the pipe-separated k dictionary
     * (`'entry1|entry2|...'`) used by VidHide/EarnVids family embeds.
     */
    private fun unpackMorencius(script: String): String? {
        val packMatcher = Regex(
            "eval\\\\(function\\\\(p,a,c,k,e,d\\\\)\\\\{.*?\\\\}\\\\('((?:[^'\\\\\\\\]|\\\\\\\\.)*)',\\\\s*(\\\\d+)\\\\s*,\\\\s*(\\\\d+)\\\\s*,\\\\s*'((?:[^'\\\\\\\\]|\\\\\\\\.)*)'",
            RegexOption.DOT_MATCHES_ALL
        ).find(script) ?: return null

        var p = packMatcher.groupValues[1]
        val base = packMatcher.groupValues[2].toIntOrNull() ?: return null
        val count = packMatcher.groupValues[3].toIntOrNull() ?: return null
        val kRaw = packMatcher.groupValues[4]
        val k = kRaw.split('|')
        if (k.size < count) return null

        // unescape JS string quotes/backslashes
        p = p.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        fun toBase(n: Int): String {
            if (n == 0) return "0"
            var v = n
            var out = ""
            while (v > 0) {
                out = digits[v % base] + out
                v /= base
            }
            return out
        }

        var out = p
        for (i in count - 1 downTo 0) {
            val token = toBase(i)
            val repl = k.getOrElse(i) { "" }
            if (repl.isNotEmpty()) {
                out = out.replace(Regex("\\b" + Regex.escape(token) + "\\b"), repl)
            }
        }
        return out
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

class Morencius : Dingtezuni() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}