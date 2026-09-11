package com.javseg

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ystream.id / f7hyg4q.org embed — Byse-family with PoW + ECDSA attestation.
 * Flow: details → captcha (PoW) → verify → playback (AES-GCM encrypted m3u8).
 * Requires ECDSA P-256 device attestation for playback body fingerprint.
 */
class YstreamExtractor : ExtractorApi() {
    override var name = "Ystream"
    override var mainUrl = "https://ystream.id"
    override val requiresReferer = true

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    /* ------------------------------------------------------------------ */
    /* PoW solver — custom XXH3-like hash (NOT SHA-256)                   */
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
        val e = intArrayOf(1779033703, -1150833019, 1013904242, -1521407534)
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

        val lr = -1640531527  // 2654435761 as signed
        val hr = -2048144777  // 2246822519 as signed (0x85EBCA77)

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
            if (w == 0) { count += 32; continue }
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

    /* ------------------------------------------------------------------ */
    /* ECDSA P-256 Device Attestation                                     */
    /* ------------------------------------------------------------------ */

    private data class AttestationResult(
        val token: String,
        val viewer_id: String,
        val device_id: String,
        val confidence: String
    )

    private fun bigIntToBytes32(bi: java.math.BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return if (raw.size == 32) raw
        else if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33)
        else ByteArray(32 - raw.size) + raw
    }

    private fun b64UrlEncode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING)

    private fun generateAttestation(): AttestationResult? {
        return try {
            // 1. Generate ECDSA P-256 key pair
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = kpg.generateKeyPair()
            val pub = keyPair.public as ECPublicKey

            val x = b64UrlEncode(bigIntToBytes32(pub.w.affineX))
            val y = b64UrlEncode(bigIntToBytes32(pub.w.affineY))

            // 2. Get challenge
            val chBody = jsonPost("https://f7hyg4q.org/api/videos/access/challenge", "{}", "")
                ?: return null
            val ch = tryParseJson<ChallengeRoot>(chBody) ?: return null

            // 3. Sign nonce
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(keyPair.private)
            sig.update(ch.nonce.toByteArray(Charsets.US_ASCII))
            val derSig = sig.sign()
            val rawSig = derToRawSig(derSig)
            val sigB64 = b64UrlEncode(rawSig)

            // 4. Attest
            val attBody = """{
                "viewer_id": "",
                "device_id": "",
                "challenge_id": "${ch.challenge_id}",
                "nonce": "${ch.nonce}",
                "signature": "$sigB64",
                "public_key": {"kty": "EC", "crv": "P-256", "x": "$x", "y": "$y"},
                "client": {"user_agent": "$ua", "hardware_concurrency": 4, "device_memory": 4,
                           "timezone": "Asia/Jakarta", "languages": ["en-US", "id"], "pointer_type": "coarse"},
                "storage": {},
                "attributes": {"entropy": "low"}
            }"""
            val attResp = jsonPost("https://f7hyg4q.org/api/videos/access/attest", attBody, "")
                ?: return null
            val att = tryParseJson<AttestationRoot>(attResp) ?: return null
            AttestationResult(
                token = att.token ?: return null,
                viewer_id = att.viewer_id ?: "",
                device_id = att.device_id ?: "",
                confidence = att.confidence ?: "low"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Convert DER-encoded ECDSA signature to raw r||s (32+32 bytes) */
    private fun derToRawSig(der: ByteArray): ByteArray {
        var offset = 0
        fun readTag(): Int { return der[offset++].toInt() and 0xFF }
        fun readLength(): Int {
            var len = der[offset++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val numBytes = len and 0x7F
                len = 0
                for (i in 0 until numBytes) {
                    len = (len shl 8) or (der[offset++].toInt() and 0xFF)
                }
            }
            return len
        }
        fun readInteger(): ByteArray {
            readTag() // INTEGER tag
            val len = readLength()
            var start = offset
            offset += len
            // Remove leading zeros
            while (start < offset - 1 && der[start].toInt() == 0) start++
            val raw = der.copyOfRange(start, offset)
            return ByteArray(32 - raw.size) + raw // pad to 32 bytes
        }

        readTag() // SEQUENCE tag
        readLength() // SEQUENCE length
        val r = readInteger()
        val s = readInteger()
        return r + s
    }

    /* ------------------------------------------------------------------ */
    /* HTTP helpers                                                        */
    /* ------------------------------------------------------------------ */

    private fun jsonPost(url: String, body: String, referer: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        val rb = Request.Builder().url(url)
            .post(RequestBody.create(jsonMediaType, body))
            .header("User-Agent", ua)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
        if (referer.isNotEmpty()) rb.header("Referer", referer)
        rb.header("Origin", "https://f7hyg4q.org")
        for ((k, v) in extraHeaders) rb.header(k, v)
        return client.newCall(rb.build()).execute().use { it.body?.string() }
    }

    private fun getJson(url: String, referer: String): String? {
        val rb = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
        if (referer.isNotEmpty()) rb.header("Referer", referer)
        return client.newCall(rb.build()).execute().use { it.body?.string() }
    }

    /* ------------------------------------------------------------------ */
    /* AES-GCM helpers                                                     */
    /* ------------------------------------------------------------------ */

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            Base64.decode(fixed + pad, Base64.DEFAULT)
        } catch (e: Exception) { ByteArray(0) }
    }

    /** Select 2 key parts by version (Qa table: version n → [n, 31-n]) */
    private fun selectKeyParts(version: String?, keyParts: List<String>): List<String> {
        val vn = version?.toIntOrNull() ?: return keyParts.take(2)
        val idx1 = vn
        val idx2 = 31 - vn
        if (idx1 in 1..keyParts.size && idx2 in 1..keyParts.size) {
            return listOf(keyParts[idx1 - 1], keyParts[idx2 - 1])
        }
        return keyParts.take(2)
    }

    private fun decryptPayload(iv: String, payload: String, keyParts: List<String>, version: String?): String? {
        return try {
            val selected = selectKeyParts(version, keyParts)
            if (selected.size < 2) return null
            val keyBytes = b64UrlDecode(selected[0]) + b64UrlDecode(selected[1])
            val ivBytes = b64UrlDecode(iv)
            val cipherBytes = b64UrlDecode(payload)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
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

    /* ------------------------------------------------------------------ */
    /* Main extraction                                                     */
    /* ------------------------------------------------------------------ */

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        if (code.isEmpty()) return

        // Step 1: Device attestation
        val att = generateAttestation() ?: return
        val fpObj = """{"token":"${att.token}","viewer_id":"${att.viewer_id}","device_id":"${att.device_id}","confidence":"${att.confidence}"}"""

        // Step 2: Details
        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val detailsRaw = getJson(detailsUrl, refererUrl) ?: return
        val details = tryParseJson<DetailsRoot>(detailsRaw) ?: return
        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val embedCode = getCodeFromUrl(embedFrameUrl).ifEmpty { code }

        // Step 3: Captcha (with fingerprint)
        val captchaRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/captcha",
            """{"fingerprint":$fpObj}""",
            embedFrameUrl
        ) ?: return
        val captcha = tryParseJson<CaptchaRoot>(captchaRaw) ?: return

        // Step 4: Solve PoW
        val solution = solvePow(captcha.powNonce, captcha.powDifficulty) ?: return

        // Step 5: Verify PoW
        val verifyRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/captcha/verify",
            """{"pow_token":"${captcha.powToken}","solution":"$solution","fingerprint":$fpObj}""",
            embedFrameUrl
        ) ?: return
        val verify = tryParseJson<VerifyRoot>(verifyRaw) ?: return
        if (verify.status != "ok") return

        // Step 6: Playback with embed headers + fingerprint + captcha token
        val eph = "ystream.id"
        val epReferer = referer ?: ""
        val epParent = "https://ystream.id/e/$code/"
        val playbackHeaders = mapOf(
            "X-Embed-Origin" to eph,
            "X-Embed-Referer" to epReferer,
            "X-Embed-Parent" to epParent,
            "X-Captcha-Token" to captcha.powToken
        )
        val playbackRaw = jsonPost(
            "$embedBase/api/videos/$embedCode/embed/playback",
            """{"fingerprint":$fpObj}""",
            embedFrameUrl,
            playbackHeaders
        ) ?: return
        val playback = tryParseJson<PlaybackRoot>(playbackRaw) ?: return

        // Step 7: AES-GCM decrypt
        val jsonStr = decryptPayload(
            playback.playback.iv,
            playback.playback.payload,
            playback.playback.keyParts,
            playback.playback.version
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

/* ------------------------------------------------------------------ */
/* Data classes                                                         */
/* ------------------------------------------------------------------ */

data class ChallengeRoot(
    @JsonProperty("challenge_id") val challenge_id: String,
    @JsonProperty("nonce") val nonce: String
)

data class AttestationRoot(
    @JsonProperty("token") val token: String?,
    @JsonProperty("viewer_id") val viewer_id: String?,
    @JsonProperty("device_id") val device_id: String?,
    @JsonProperty("confidence") val confidence: String?
)

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)

data class CaptchaRoot(
    @JsonProperty("pow_nonce") val powNonce: String,
    @JsonProperty("pow_difficulty") val powDifficulty: Int,
    @JsonProperty("pow_token") val powToken: String
)

data class VerifyRoot(
    @JsonProperty("status") val status: String,
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("fingerprint") val fingerprint: String? = null
)

data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)

data class Playback(
    @JsonProperty("iv") val iv: String,
    @JsonProperty("payload") val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>,
    @JsonProperty("version") val version: String? = null
)

data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)

data class PlaybackDecryptSource(
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("mime_type") val mimeType: String? = null
)
