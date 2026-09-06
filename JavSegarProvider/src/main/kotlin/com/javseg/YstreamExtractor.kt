package com.javseg

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ystream.id uses a Byse-family embed with Proof-of-Work (PoW) anti-bot protection.
 * Implements the custom XXH-like hash, PoW solver, and AES-GCM decryption.
 */
class YstreamExtractor : ExtractorApi() {
    override var name = "Ystream"
    override var mainUrl = "https://ystream.id"
    override val requiresReferer = true

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    /* ------------------------------------------------------------------
     * PoW solver: port of pow.js custom hash (XXH-family), NOT sha256
     * ------------------------------------------------------------------ */

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun ye(t: IntArray) {
        var a = t[0] + t[1]
        t[0] = a
        t[3] = rotl(t[3] xor a, 16)
        a = t[2] + t[3]
        t[2] = a
        t[1] = rotl(t[1] xor a, 12)
        a = t[0] + t[1]
        t[0] = a
        t[3] = rotl(t[3] xor a, 8)
        a = t[2] + t[3]
        t[2] = a
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
        for (i in 0 until 512) {
            ye(e)
            r[i] = e[0] xor e[2]
        }

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

    private fun wr(t: IntArray): Int {
        var count = 0
        for (w in t) {
            if (w == 0) {
                count += 32
                continue
            }
            return count + Integer.numberOfLeadingZeros(w)
        }
        return count
    }

    private fun yr(string: String): ByteArray = string.toByteArray(Charsets.ISO_8859_1)

    private fun solvePow(nonce: String, difficulty: Int, timeoutMs: Long = 30000): String? {
        val prefix = "$nonce:"
        val start = System.currentTimeMillis()
        var s = 0L
        while (true) {
            val bytes = yr(prefix + s.toString())
            val hash = gr(bytes)
            if (wr(hash) >= difficulty) return s.toString()
            s++
            if (System.currentTimeMillis() - start > timeoutMs) return null
        }
    }

    /* ------------------------------------------------------------------
     * HTTP helpers via OkHttp
     * ------------------------------------------------------------------ */

    private fun jsonPost(url: String, body: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(jsonMediaType, body))
            .header("User-Agent", ua)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", referer)
            .header("X-Embed-Origin", "ystream.id")
            .build()
        return client.newCall(request).execute().use { it.body?.string() }
    }

    private fun getJson(url: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", referer)
            .build()
        return client.newCall(request).execute().use { it.body?.string() }
    }

    /* ------------------------------------------------------------------
     * AES-GCM helpers
     * ------------------------------------------------------------------ */

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun decryptPayload(iv: String, payload: String, keyParts: List<String>): String? {
        return try {
            if (keyParts.size < 2) return null
            val keyBytes = b64UrlDecode(keyParts[0]) + b64UrlDecode(keyParts[1])
            val ivBytes = b64UrlDecode(iv)
            val cipherBytes = b64UrlDecode(payload)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, ivBytes)
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (e: Exception) {
            null
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url)
    }

    private fun getCodeFromUrl(url: String): String {
        return Regex("""/(?:e|v|d)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: ""
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        if (code.isEmpty()) return

        // Step 1: get embed frame URL from details endpoint
        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val detailsRaw = getJson(detailsUrl, refererUrl) ?: return
        val details = tryParseJson<DetailsRoot>(detailsRaw) ?: return

        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val embedCode = getCodeFromUrl(embedFrameUrl).ifEmpty { code }

        // Step 2: get PoW challenge
        val captchaRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/captcha",
            "{}", embedFrameUrl
        ) ?: return
        val captcha = tryParseJson<CaptchaRoot>(captchaRaw) ?: return

        // Step 3: solve PoW
        val solution = solvePow(captcha.powNonce, captcha.powDifficulty) ?: return

        // Step 4: verify PoW → get fingerprint
        val verifyRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/captcha/verify",
            """{"pow_token":"${captcha.powToken}","solution":"$solution"}""",
            embedFrameUrl
        ) ?: return
        val verify = tryParseJson<VerifyRoot>(verifyRaw) ?: return
        if (verify.status != "ok") return
        val fingerprint = verify.token ?: return

        // Step 5: get encrypted playback payload
        val playbackRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/playback",
            """{"fingerprint":"$fingerprint"}""",
            embedFrameUrl
        ) ?: return
        val playback = tryParseJson<PlaybackRoot>(playbackRaw) ?: return

        // Step 6: AES-GCM decrypt → extract m3u8 URL
        val jsonStr = decryptPayload(
            playback.playback.iv,
            playback.playback.payload,
            playback.playback.keyParts
        ) ?: return

        val streamData = tryParseJson<PlaybackDecrypt>(jsonStr) ?: return
        val streamUrl = streamData.sources.firstOrNull()?.url ?: return

        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            refererUrl,
            headers = mapOf("Referer" to embedFrameUrl, "User-Agent" to ua)
        ).forEach(callback)
    }
}

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class CaptchaRoot(
    @JsonProperty("pow_nonce") val powNonce: String,
    @JsonProperty("pow_difficulty") val powDifficulty: Int,
    @JsonProperty("pow_token") val powToken: String
)
data class VerifyRoot(
    @JsonProperty("status") val status: String,
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("fingerprint") val fingerprint: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = null
)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(
    @JsonProperty("iv") val iv: String,
    @JsonProperty("payload") val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
