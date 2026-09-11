package com.avtube

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ystream.id uses a Byse-family embed with Proof-of-Work (PoW) anti-bot protection
 * plus ECDSA P-256 attestation.
 * Implements: ECDSA attestation, custom XXH-like hash, PoW solver, AES-GCM decryption.
 */
class YstreamExtractor : ExtractorApi() {
    override var name = "Ystream"
    override var mainUrl = "https://ystream.id"
    override val requiresReferer = true

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jsonMapper = ObjectMapper()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    companion object {
        private const val TAG = "YstreamExtractor"
    }

    /* ------------------------------------------------------------------ */
    /* PoW solver: port of pow.js custom hash (XXH-family), NOT sha256   */
    /* ------------------------------------------------------------------ */

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

    /* ------------------------------------------------------------------ */
    /* HTTP helpers via OkHttp                                           */
    /* ------------------------------------------------------------------ */

    private fun httpPost(url: String, body: String, headers: Map<String, String>): String? {
        val reqBody = RequestBody.create(jsonMediaType, body)
        val builder = Request.Builder().url(url).post(reqBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            client.newCall(builder.build()).execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.e(TAG, "POST $url failed: ${e.message}")
            null
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            client.newCall(builder.build()).execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    private fun baseHeaders(referer: String?): MutableMap<String, String> {
        return mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://f7hyg4q.org"
        ).also { if (referer != null) it["Referer"] = referer }
    }

    /* ------------------------------------------------------------------ */
    /* ECDSA P-256 attestation                                          */
    /* ------------------------------------------------------------------ */

    private fun b64urlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun bigIntToBytes32(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun doAttestation(embedBase: String): Map<String, String>? {
        // 1. Generate ECDSA P-256 key pair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("prime256v1"))
        val keyPair = kpg.generateKeyPair()
        val pubKey = keyPair.public as ECPublicKey

        val pubJwk = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to b64urlEncode(bigIntToBytes32(pubKey.w.affineX)),
            "y" to b64urlEncode(bigIntToBytes32(pubKey.w.affineY))
        )
        Log.d(TAG, "ECDSA keypair generated")

        // 2. Get challenge
        val hdrs = baseHeaders(null)
        val chRaw = httpPost("$embedBase/api/videos/access/challenge", "{}", hdrs) ?: return null
        val ch = tryParseJson<ChallengeResp>(chRaw) ?: return null
        Log.d(TAG, "challenge_id=${ch.challenge_id}")

        // 3. Sign nonce
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(ch.nonce.toByteArray(Charsets.US_ASCII))
        val signatureBytes = sig.sign()

        // Convert DER-encoded signature to raw r||s (64 bytes)
        val rawSig = derToRawSig(signatureBytes)
        val sigB64 = b64urlEncode(rawSig)

        // 4. Attest
        val attestBody = mapOf(
            "viewer_id" to "",
            "device_id" to "",
            "challenge_id" to ch.challenge_id,
            "nonce" to ch.nonce,
            "signature" to sigB64,
            "public_key" to pubJwk,
            "client" to mapOf(
                "user_agent" to ua,
                "hardware_concurrency" to 4,
                "device_memory" to 4,
                "timezone" to "Asia/Jakarta",
                "languages" to listOf("en-US", "id"),
                "pointer_type" to "coarse"
            ),
            "storage" to emptyMap<String, Any>(),
            "attributes" to mapOf("entropy" to "low")
        )
        val attRaw = httpPost("$embedBase/api/videos/access/attest", jsonMapper.writeValueAsString(attestBody), hdrs) ?: return null
        val att = tryParseJson<AttestResp>(attRaw)
        if (att?.token == null) {
            Log.e(TAG, "attest failed: $attRaw")
            return null
        }
        Log.d(TAG, "attest OK, token=${att.token.take(30)}...")
        return mapOf(
            "token" to att.token,
            "viewer_id" to (att.viewer_id ?: ""),
            "device_id" to (att.device_id ?: ""),
            "confidence" to (att.confidence ?: "")
        )
    }

    private fun derToRawSig(derSig: ByteArray): ByteArray {
        // DER: 0x30 [len] 0x02 [len] [r] 0x02 [len] [s]
        var idx = 0
        if (derSig[idx++] != 0x30.toByte()) return derSig
        idx++ // total length
        if (derSig[idx++] != 0x02.toByte()) return derSig
        var rLen = derSig[idx++].toInt() and 0xFF
        val r = derSig.copyOfRange(idx, idx + rLen)
        idx += rLen
        if (derSig[idx++] != 0x02.toByte()) return derSig
        var sLen = derSig[idx++].toInt() and 0xFF
        val s = derSig.copyOfRange(idx, idx + sLen)

        // Pad/trim to 32 bytes
        val r32 = when { r.size == 32 -> r; r.size > 32 -> r.copyOfRange(r.size - 32, r.size); else -> ByteArray(32 - r.size) + r }
        val s32 = when { s.size == 32 -> s; s.size > 32 -> s.copyOfRange(s.size - 32, s.size); else -> ByteArray(32 - s.size) + s }
        return r32 + s32
    }

    /* ------------------------------------------------------------------ */
    /* AES-GCM helpers                                                   */
    /* ------------------------------------------------------------------ */

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

    private fun decryptPayload(iv: String, payload: String, keyParts: List<String>, version: Int): String? {
        return try {
            if (keyParts.isEmpty()) return null
            // Version-based key selection: Qa table maps version n -> [n, 31-n]
            val idx1 = version
            val idx2 = 31 - version
            val selectedParts = if (idx1 in 1..keyParts.size && idx2 in 1..keyParts.size) {
                listOf(keyParts[idx1 - 1], keyParts[idx2 - 1])
            } else {
                listOf(keyParts[0], keyParts.getOrElse(1) { keyParts[0] })
            }
            Log.d(TAG, "decrypt: version=$version, total=${keyParts.size}, selected=[$idx1,$idx2]")

            val keyBytes = b64UrlDecode(selectedParts[0]) + b64UrlDecode(selectedParts[1])
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
            Log.e(TAG, "decrypt failed: ${e.message}")
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
        Log.d(TAG, "=== START extract code=$code referer=$referer ===")

        // Step 0: ECDSA attestation (gets fingerprint object)
        val embedBase = "https://f7hyg4q.org"
        val fpObj = doAttestation(embedBase)
        if (fpObj == null) {
            Log.e(TAG, "attestation failed, aborting")
            return
        }

        // Step 1: get embed frame URL from details endpoint
        val detailsHeaders = baseHeaders(null)
        val detailsRaw = httpGet("$embedBase/api/videos/$code/embed/details", detailsHeaders) ?: return
        val details = tryParseJson<DetailsRoot>(detailsRaw) ?: return
        val embedFrameUrl = details.embedFrameUrl
        Log.d(TAG, "details: embed_frame_url=$embedFrameUrl")

        // Step 2: get PoW challenge — POST with fingerprint
        val captchaBody = jsonMapper.writeValueAsString(mapOf("fingerprint" to fpObj))
        val captchaHeaders = baseHeaders(embedFrameUrl)
        val captchaRaw = httpPost("$embedBase/api/videos/$code/embed/captcha", captchaBody, captchaHeaders) ?: return
        val captcha = tryParseJson<CaptchaRoot>(captchaRaw) ?: return
        Log.d(TAG, "captcha: difficulty=${captcha.powDifficulty}")

        // Step 3: solve PoW
        val solution = solvePow(captcha.powNonce, captcha.powDifficulty) ?: run {
            Log.e(TAG, "PoW solve timed out")
            return
        }
        Log.d(TAG, "PoW solution=$solution")

        // Step 4: verify PoW → get status — POST with pow_token, solution, AND fingerprint
        val verifyBody = jsonMapper.writeValueAsString(mapOf(
            "pow_token" to captcha.powToken,
            "solution" to solution,
            "fingerprint" to fpObj
        ))
        val verifyHeaders = baseHeaders(embedFrameUrl)
        val verifyRaw = httpPost("$embedBase/api/videos/$code/embed/captcha/verify", verifyBody, verifyHeaders) ?: return
        val verify = tryParseJson<VerifyRoot>(verifyRaw)
        if (verify?.status != "ok") {
            Log.e(TAG, "verify failed: $verifyRaw")
            return
        }
        Log.d(TAG, "verify OK")

        // Step 5: get encrypted playback payload — POST with full fingerprint + embed headers
        val playBody = jsonMapper.writeValueAsString(mapOf("fingerprint" to fpObj))
        val playHeaders = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Embed-Origin" to "ystream.id",
            "X-Embed-Referer" to (referer ?: embedFrameUrl),
            "X-Embed-Parent" to "https://ystream.id/e/$code/",
            "X-Captcha-Token" to captcha.powToken
        )
        val playbackRaw = httpPost("$embedBase/api/videos/$code/embed/playback", playBody, playHeaders) ?: return
        val playback = tryParseJson<PlaybackRoot>(playbackRaw)
        if (playback?.playback == null) {
            Log.e(TAG, "playback failed: ${playbackRaw.take(200)}")
            return
        }
        Log.d(TAG, "playback OK: key_parts=${playback.playback.keyParts.size}, version=${playback.playback.version}")

        // Step 6: AES-GCM decrypt → extract m3u8 URL
        val jsonStr = decryptPayload(
            playback.playback.iv,
            playback.playback.payload,
            playback.playback.keyParts,
            playback.playback.version
        )
        if (jsonStr == null) {
            Log.e(TAG, "decrypt returned null")
            return
        }
        Log.d(TAG, "decrypted: ${jsonStr.take(100)}...")

        val streamData = tryParseJson<PlaybackDecrypt>(jsonStr) ?: run {
            Log.e(TAG, "failed to parse decrypted JSON: ${jsonStr.take(200)}")
            return
        }
        val streamUrl = streamData.sources.firstOrNull()?.url ?: run {
            Log.e(TAG, "no sources in decrypted payload")
            return
        }
        Log.d(TAG, "stream URL: ${streamUrl.take(80)}...")

        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            refererUrl,
            headers = mapOf("Referer" to "https://ystream.id/e/$code/", "User-Agent" to ua)
        ).forEach(callback)
    }
}

/* ------------------------------------------------------------------ */
/* Data classes                                                        */
/* ------------------------------------------------------------------ */

data class ChallengeResp(
    @JsonProperty("challenge_id") val challenge_id: String,
    @JsonProperty("nonce") val nonce: String
)

data class AttestResp(
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

data class PlaybackRoot(@JsonProperty("playback") val playback: Playback?)

data class Playback(
    @JsonProperty("iv") val iv: String,
    @JsonProperty("payload") val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>,
    @JsonProperty("version") val version: Int = 1
)

data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
