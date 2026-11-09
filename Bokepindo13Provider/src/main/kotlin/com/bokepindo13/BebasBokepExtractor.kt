package com.bokepindo13

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * BebasBokep (bebasbokep.online/video/<id>) — Codeside JWPlayer embed,
 * another server family on the Bokepindo13 tube.
 *
 * Verified live 2026-11-10 against
 *   /bokep-indo/katanya-keluar-beli-cimol-malah-kena-ewe-di-mobil-bokep-indo/
 *   -> iframe https://bebasbokep.online/video/TxShBOPUKVkuvHD
 *
 * The embed page is a single Dean Edwards packed setup with base 62:
 *   eval(function(p,a,c,k,e,d){...return p}('<packed>', 62, 158, '<k>'.split('|'), 0, {}))
 * Unpacked it is a JWPlayer playlist with one MP4 source:
 *   'label':'360p','type':'video/mp4',
 *   'file':'https://nxs2stream.live/<title>.mp4'   (63 MB, Cloudflare, no Referer needed)
 * So no HLS expansion is needed — the mp4 is emitted as VIDEO directly.
 * A master.m3u8 shape, if ever served here, is handled the same way as Lulu.
 *
 * The unpacker takes the base digit count from the packer (36 for Lulu's
 * variant, 62 here) and uses the 62-char alphabet prefix, which decodes both.
 */
open class BebasBokepExtractor : ExtractorApi() {
    override val name = "BebasBokep"
    override val mainUrl = "https://bebasbokep.online"
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
                "Referer" to (referer ?: "https://bokepindo13.motorcycles/"),
                "Origin" to "https://bebasbokep.online",
                "Accept" to "*/*"
            )
            val script = app.get(embedUrl, headers = headers).text

            val unpacked = unpack(script) ?: script

            // 1) MP4 first, since this host serves it: 'file':'https://...mp4'
            val mp4 = Regex("""file['"]?\s*[:=]\s*['"](https?://[^'"]+?\.mp4[^'"]*)['"]""")
                .find(unpacked)?.groupValues?.get(1)

            if (!mp4.isNullOrBlank()) {
                val label = Regex("""label['"]?\s*[:=]\s*['"](\d+)p['"]""")
                    .find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                Log.d(TAG, "mp4=$mp4")
                callback(
                    newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) {
                        this.referer = embedUrl
                        this.headers = mapOf("Referer" to embedUrl, "Origin" to "https://bebasbokep.online")
                        this.quality = label
                    }
                )
                return
            }

            // 2) Fallback HLS, same handling as LuluStreamExtractor.
            val master = Regex("""file['"]?\s*[:=]\s*['"](https?://[^'"]+?master\.m3u8[^'"]*)['"]""")
                .find(unpacked)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"'\\]+/hls[^"'\\\s]*master\.m3u8[^\s"'\\]*""")
                    .find(unpacked)?.value

            if (master.isNullOrBlank()) {
                Log.e(TAG, "no mp4/m3u8 for $embedUrl")
                return
            }

            Log.d(TAG, "master=$master")
            try {
                M3u8Helper.generateM3u8(
                    name,
                    master,
                    referer = embedUrl,
                    headers = mapOf("Referer" to embedUrl, "Origin" to "https://bebasbokep.online")
                ).forEach(callback)
            } catch (e: Exception) {
                Log.e(TAG, "generateM3u8 failed: ${e.message}")
                callback(
                    newExtractorLink(name, name, master) {
                        this.referer = embedUrl
                        this.headers = mapOf("Referer" to embedUrl, "Origin" to "https://bebasbokep.online")
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Generic Dean Edwards unpacker. Handles both packer call shapes:
     *   ('<packed>', <base>, <count>, '<dict>'.split('|'), ...)   // Lulu & Bebas
     *   ('<packed>', <base>, <count>, '<dict>', ...)               // VidHide style
     * Digit alphabet is the 62-char prefix (0-9, a-z, A-Z); for base 36 the
     * A-Z tail is never reached, so one routine decodes both.
     */
    private fun unpack(script: String): String? {
        // Outer call: first arg is the packed JS string, then base, count.
        // `.*?` (not [^}]*) because the Bebas body nests `}` inside
        // e=function(c){...} / if(){...} — [^}]* stops at the first inner brace.
        val head = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p.*?\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,?""",
            RegexOption.DOT_MATCHES_ALL
        ).find(script) ?: return null
        val p = head.groupValues[1]
        val base = head.groupValues[2].toIntOrNull() ?: return null
        val count = head.groupValues[3].toIntOrNull() ?: return null

        // Dictionary: '<dict>'.split('|') preferred, else the bare '<dict>'.
        val dictMatch = Regex(
            """'(?:[^'\\]|\\.)*'\s*,\s*\d+\s*,\s*\d+\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)"""
        ).find(script)
            ?: Regex(
                """'(?:[^'\\]|\\.)*'\s*,\s*\d+\s*,\s*\d+\s*,\s*'((?:[^'\\]|\\.)*)'"""
            ).find(script)
            ?: return null
        val kRaw = dictMatch.groupValues[1]

        val k = kRaw.replace("\\'", "'").replace("\\\\", "\\").split('|')
        if (k.size < count && k.size < 2) return null

        val decoded = p.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
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

        var out = decoded
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
        private const val TAG = "BebasBokep"
    }
}
