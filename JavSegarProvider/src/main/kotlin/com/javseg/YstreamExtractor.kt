package com.javseg

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byse-family embed (ystream.id) extractor.
 *
 * Verified live flow (embed id jl23ymom7hyu):
 *   1. GET  {base}/api/videos/{code}/embed/details   -> embed_frame_url (host rotates per video)
 *   2. POST {base}/api/videos/{code}/embed/captcha   -> pow_nonce / pow_difficulty / pow_token
 *   3. solve custom PoW: hash = gr(nonce + ":" + solution), need difficulty leading-zero BITS
 *   4. POST .../embed/captcha/verify {pow_token, solution} -> { status:"ok", token }
 *   5. POST .../embed/playback {fingerprint:{token,viewer_id,device_id,confidence}}
 *      headers: X-Captcha-Token + X-Embed-Origin/Referer/Parent
 *      -> { playback:{ algorithm, iv, payload, key_parts, version } }
 *   6. AES-256-GCM decrypt: key = key_parts[v-1] || key_parts[30-v], both base64url
 *
 * The fingerprint signature is NOT verified server-side (a structurally valid
 * JWT-shaped object is accepted), so no P-256 key handling is needed here.
 */
class YstreamExtractor : ExtractorApi() {
    override var name = "Ystream"
    override var mainUrl = "https://ystream.id"
    override val requiresReferer = true

    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val mapper = ObjectMapper()
    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    companion object {
        private const val TAG = "YstreamExtractor"
    }

    /* ------------------------------------------------------------------ */
    /* PoW: port of pow.js gr() -- a custom 256-bit hash, NOT sha256      */
    /* (the server metadata says "sha256-leading-zero-bits"; that label   */
    /*  is wrong -- difficulty counts leading zero BITS of gr())          */
    /* ------------------------------------------------------------------ */

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun ye(t: IntArray) {
        var a = t[0] + t[1]; t[0] = a
        t[3] = rotl(t[3] xor a, 16)
        a = t[2] + t[3]; t[2] = a
        t[1] = rotl(t[1] xor a, 12)
        a = t[0] + t[1]; t[0] = a
        t[3] = rotl(t[3] xor a, 8)
        a = t[2] + t[3]; t[2] = a
        t[1] = rotl(t[1] xor a, 7)
    }

    private fun gr(input: ByteArray): IntArray {
        val e = intArrayOf(1779033703, 0xBB67AE85.toInt(), 1013904242, 0xA54FF53A.toInt())
        for (b in input) {
            e[0] = e[0] + (b.toInt() and 0xFF)
            e[0] = rotl(e[0], 7)
            ye(e)
        }
        for (i in 0 until 8) ye(e)

        val r = IntArray(512)
        for (i in 0 until 512) { ye(e); r[i] = e[0] xor e[2] }

        val lr: Int = 2654435761.toInt()
        val hr: Int = 2246822519.toInt()

        for (i in 0 until 2) {
            for (s in 0 until 512) {
                val a = r[s] and 511
                var c = r[s] + r[a]
                c = rotl(c, 13)
                c = c xor (r[(s + 1) and 511] * lr)
                r[s] = c
                e[0] = e[0] xor c
                ye(e)
            }
        }

        val n = IntArray(8)
        for (i in 0 until 8) {
            ye(e)
            var s = e[0]
            val base = i * 64
            for (j in 0 until 64) {
                val d = r[base + j]
                s = s + d
                s = rotl(s, 5)
                s = s xor (d * hr)
            }
            n[i] = s xor e[2]
        }
        return n
    }

    /** Total number of leading zero bits across the 8 words. */
    private fun wr(t: IntArray): Int {
        var count = 0
        for (w in t) {
            if (w == 0) { count += 32; continue }
            return count + Integer.numberOfLeadingZeros(w)
        }
        return count
    }

    private fun solvePow(nonce: String, difficulty: Int, timeoutMs: Long = 60000): String? {
        val prefix = "$nonce:"
        val start = System.currentTimeMillis()
        var s = 0L
        val limit = 40_000_000L
        while (s < limit) {
            val bytes = (prefix + s).toByteArray(Charsets.ISO_8859_1)
            if (wr(gr(bytes)) >= difficulty) return s.toString()
            s += 2
            if (s % 4096 == 0L && System.currentTimeMillis() - start > timeoutMs) return null
        }
        return null
    }

    /* ------------------------------------------------------------------ */
    /* HTTP                                                                */
    /* ------------------------------------------------------------------ */

    private fun embedHeaders(code: String, refererUrl: String?): MutableMap<String, String> {
        val ref = refererUrl ?: "https://ystream.id/e/$code/"
        return mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json; charset=utf-8",
            "Origin" to "https://ystream.id",
            "Referer" to ref,
            "X-Embed-Origin" to "https://ystream.id",
            "X-Embed-Referer" to ref,
            "X-Embed-Parent" to ref
        )
    }

    private fun exec(req: Request): Pair<Int, String> = try {
        client.newCall(req).execute().use { resp ->
            (resp.code to (resp.body?.string() ?: ""))
        }
    } catch (e: Exception) {
        Log.e(TAG, "request failed: ${e.message}")
        (-1 to "")
    }

    private fun getJson(url: String, code: String, refererUrl: String?): String? {
        val b = Request.Builder().url(url).get()
        embedHeaders(code, refererUrl).forEach { (k, v) -> b.header(k, v) }
        val (status, body) = exec(b.build())
        if (status != 200) {
            Log.e(TAG, "GET $url -> $status ${body.take(160)}")
            return null
        }
        return body
    }

    private fun postJson(url: String, payload: String, code: String, refererUrl: String?): String? {
        val b = Request.Builder().url(url).post(payload.toRequestBody(jsonType))
        embedHeaders(code, refererUrl).forEach { (k, v) -> b.header(k, v) }
        val (status, body) = exec(b.build())
        if (status != 200) {
            Log.e(TAG, "POST $url -> $status ${body.take(160)}")
            return null
        }
        return body
    }

    /* ------------------------------------------------------------------ */
    /* AES-GCM                                                             */
    /* ------------------------------------------------------------------ */

    private fun b64UrlDecode(s: String?): ByteArray = try {
        val raw = s ?: return ByteArray(0)
        val fixed = raw.replace('-', '+').replace('_', '/')
        val pad = when (fixed.length % 4) {
            2 -> "=="; 3 -> "="; else -> ""
        }
        Base64.decode(fixed + pad, Base64.DEFAULT)
    } catch (e: Exception) { ByteArray(0) }

    private fun decryptPayload(pb: Playback): String? = try {
        if (pb.keyParts.isEmpty()) return null
        val v = pb.version
        val i1 = v
        val i2 = 31 - v
        val parts = if (i1 in 1..pb.keyParts.size && i2 in 1..pb.keyParts.size)
            listOf(pb.keyParts[i1 - 1], pb.keyParts[i2 - 1])
        else listOf(pb.keyParts[0], pb.keyParts.getOrElse(1) { pb.keyParts[0] })

        val keyBytes = b64UrlDecode(parts[0]) + b64UrlDecode(parts[1])
        if (keyBytes.size != 32) {
            Log.e(TAG, "bad key size ${keyBytes.size} (version=$v, total=${pb.keyParts.size})")
            return null
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, b64UrlDecode(pb.iv))
        )
        String(cipher.doFinal(b64UrlDecode(pb.payload)), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(TAG, "decrypt failed: ${e.message}")
        null
    }

    /** Structurally valid fingerprint; the server does not verify the signature. */
    private fun fingerprint(): Map<String, Any> {
        fun rnd(n: Int): String {
            val cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder(n)
            val seed = System.nanoTime()
            for (i in 0 until n) {
                sb.append(cs[((seed + i * 2654435761L).mod(cs.length.toLong())).toInt()])
            }
            return sb.toString()
        }
        val hdr = Base64.encodeToString(
            """{"alg":"ES256","typ":"JWT"}""".toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return mapOf(
            "token" to "$hdr.${rnd(80)}.${rnd(86)}",
            "viewer_id" to rnd(22),
            "device_id" to rnd(22),
            "confidence" to 0.9
        )
    }

    private fun base(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)

    private fun codeFrom(url: String): String =
        Regex("""/(?:e|v|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: ""

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = codeFrom(url)
        if (code.isEmpty()) { Log.e(TAG, "no embed code in $url"); return }

        val siteBase = base(url)
        val ref = "https://ystream.id/e/$code/"
        val fp = fingerprint()

        // 1. details -> rotating host
        val detailsRaw = getJson("$siteBase/api/videos/$code/embed/details", code, ref) ?: return
        val details = tryParseJson<DetailsRoot>(detailsRaw)
        val host = details?.embedFrameUrl?.let { base(it) } ?: siteBase
        Log.d(TAG, "code=$code host=$host")

        // 2. captcha
        val captchaRaw = postJson("$host/api/videos/$code/embed/captcha", "{}", code, ref) ?: return
        val captcha = tryParseJson<CaptchaRoot>(captchaRaw)
        if (captcha == null || captcha.powNonce.isNullOrBlank()) {
            Log.e(TAG, "bad captcha: ${captchaRaw.take(180)}"); return
        }
        val diff = captcha.powDifficulty ?: 16
        Log.d(TAG, "pow difficulty=$diff")

        // 3. solve
        val t0 = System.currentTimeMillis()
        val solution = solvePow(captcha.powNonce, diff)
        if (solution == null) { Log.e(TAG, "PoW unsolved"); return }
        Log.d(TAG, "pow solution=$solution in ${System.currentTimeMillis() - t0}ms")

        // 4. verify
        val verifyBody = mapper.writeValueAsString(
            mapOf("pow_token" to captcha.powToken, "solution" to solution)
        )
        val verifyRaw = postJson("$host/api/videos/$code/embed/captcha/verify", verifyBody, code, ref) ?: return
        val verify = tryParseJson<VerifyRoot>(verifyRaw)
        if (verify?.status != "ok") { Log.e(TAG, "verify: ${verifyRaw.take(180)}"); return }
        val captchaToken = verify.token.orEmpty()
        Log.d(TAG, "verify ok tokenLen=${captchaToken.length}")

        // 5. playback
        val playBody = mapper.writeValueAsString(mapOf("fingerprint" to fp))
        val playBuilder = Request.Builder()
            .url("$host/api/videos/$code/embed/playback")
            .post(playBody.toRequestBody(jsonType))
        embedHeaders(code, ref).forEach { (k, v) -> playBuilder.header(k, v) }
        playBuilder.header("X-Captcha-Token", captchaToken)
        val (pStatus, pBody) = exec(playBuilder.build())
        if (pStatus != 200) { Log.e(TAG, "playback $pStatus ${pBody.take(180)}"); return }

        val playback = tryParseJson<PlaybackRoot>(pBody)?.playback
        if (playback == null) { Log.e(TAG, "bad playback: ${pBody.take(180)}"); return }
        Log.d(TAG, "playback ok version=${playback.version} parts=${playback.keyParts.size}")

        // 6. decrypt
        val plain = decryptPayload(playback)
        if (plain == null) { Log.e(TAG, "decrypt returned null"); return }

        val dec = tryParseJson<PlaybackDecrypt>(plain)
        val sources = dec?.sources ?: emptyList()
        if (sources.isEmpty()) { Log.e(TAG, "no sources in ${plain.take(180)}"); return }

        for (s in sources) {
            val link = s.url ?: continue
            if (link.isBlank()) continue
            val hls = s.mimeType?.contains("mpegurl") == true || link.contains(".m3u8")
            val linkType = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(
                    link,
                    s.label ?: "HLS",
                    ref,
                    linkType
                ) { }
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Models                                                              */
/* ------------------------------------------------------------------ */

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptchaRoot(
    @JsonProperty("pow_nonce") val powNonce: String? = null,
    @JsonProperty("pow_difficulty") val powDifficulty: Int? = null,
    @JsonProperty("pow_token") val powToken: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VerifyRoot(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Playback(
    @JsonProperty("algorithm") val algorithm: String? = null,
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("payload") val payload: String? = null,
    @JsonProperty("key_parts") val keyParts: List<String> = emptyList(),
    @JsonProperty("version") val version: Int = 1
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackDecrypt(
    @JsonProperty("sources") val sources: List<PlaybackDecryptSource> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackDecryptSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("mime_type") val mimeType: String? = null
)
