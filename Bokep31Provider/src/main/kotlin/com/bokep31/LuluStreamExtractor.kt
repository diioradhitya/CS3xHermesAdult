package com.bokep31

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * LuluStream (luluvdoo.com/e/<code>) — StreamWish-family embed served with a
 * Dean Edwards packed jwplayer setup.
 *
 * Verified live 2026-11-08 against
 *   /bokep-indo/bokep-indo-viral-bunda-hijab-main-sama-laki-orang/
 *   -> iframe https://luluvdoo.com/e/od8674wd0mj2
 *
 * The embed page is one eval-packed script:
 *   eval(function(p,a,c,k,e,d){while(c--)if(k[c])...return p}
 *     ('<packed setup>', 36, 224, '<k dict>'.split('|')))
 * Unpacked it is a single jwplayer setup with one source:
 *   https://<sub>.tnmr.org/hls2/02/02200/<code>_h/master.m3u8?t=...&s=...&e=28800&...
 * (884 kbps, 392x720 vertical, ~8h token). Fetched with Referer=<embed url>
 * the CDN answers the master directly (plain GET); generateM3u8 expands it.
 *
 * CloudStream's built-in StreamWish extractor does not claim luluvdoo.com
 * (host list is streamwish.* / lulustream.com), so this file unpacks the packer
 * itself instead of host-replacing to streamwish.to like the JavHey hack.
 */
open class LuluStreamExtractor : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvdoo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = url.trim()
            val headers = mapOf(
                "Referer" to (referer ?: "https://bokep31.study/"),
                "Origin" to "https://luluvdoo.com",
                "Accept" to "*/*"
            )
            val script = app.get(embedUrl, headers = headers).text

            val unpacked = unpack(script) ?: script
            val master = Regex("""file\s*:\s*"(https?://[^"]+?master\.m3u8[^"]*)"""")
                .find(unpacked)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"'\\]+/hls[^"'\\\s]*master\.m3u8[^\s"'\\]*""")
                    .find(unpacked)?.value

            if (master.isNullOrBlank()) {
                Log.e(TAG, "no master.m3u8 for $embedUrl")
                return
            }

            Log.d(TAG, "master=$master")
            val generated = try {
                M3u8Helper.generateM3u8(
                    name,
                    master,
                    referer = embedUrl,
                    headers = mapOf("Referer" to embedUrl, "Origin" to "https://luluvdoo.com")
                )
            } catch (e: Exception) {
                Log.e(TAG, "generateM3u8 failed: ${e.message}")
                emptyList()
            }

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                // Let ExoPlayer fetch the master itself if expanding it failed.
                callback(
                    newExtractorLink(name, name, master) {
                        this.referer = embedUrl
                        this.headers = mapOf("Referer" to embedUrl, "Origin" to "https://luluvdoo.com")
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Dean Edwards unpacker for the LuluStream packer shape
     *   eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(...);return p}
     *     ('<packed>', <base>, <count>, '<k dict>'.split('|')))
     * The Morencius unpacker in the AVTube repo only handles the comma-dict
     * form ('<packed>', <base>, <count>, '<k1|k2|...>', ...), so both are
     * accepted here and the empty leading dict slots are skipped the same way.
     */
    private fun unpack(script: String): String? {
        val splitDict = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}?\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(script)

        // Form A (LuluStream): 4th arg is '<dict>'.split('|') — handled by the
        // receiver string capture above after the match consumes the split part.
        // The regex above captures to the closing quote before .split, so re-scan
        // for the dict explicitly when present.
        val dictMatch = Regex(
            """'(?:[^'\\]|\\.)*'\s*,\s*\d+\s*,\s*\d+\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)"""
        ).find(script)

        var p: String
        var base: Int
        var count: Int
        var kRaw: String

        if (splitDict != null && dictMatch != null) {
            p = splitDict.groupValues[1]
            base = splitDict.groupValues[2].toIntOrNull() ?: return null
            count = splitDict.groupValues[3].toIntOrNull() ?: return null
            kRaw = dictMatch.groupValues[1]
        } else {
            // Form B (Morencius/VidHide): '...', base, count, 'k1|k2|...'
            val m = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?return p[^}]*\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'""",
                RegexOption.DOT_MATCHES_ALL
            ).find(script) ?: return null
            p = m.groupValues[1]
            base = m.groupValues[2].toIntOrNull() ?: return null
            count = m.groupValues[3].toIntOrNull() ?: return null
            kRaw = m.groupValues[4]
        }

        val k = kRaw.replace("\\'", "'").replace("\\\\", "\\").split('|')
        if (k.size < count && k.size < 2) return null
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

    companion object {
        private const val TAG = "LuluStream"
    }
}
