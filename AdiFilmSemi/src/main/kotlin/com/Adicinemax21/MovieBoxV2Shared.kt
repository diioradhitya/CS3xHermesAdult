// ---------------------------------------------------------------------------
// AdiFilmSemi
//
// Diambil dari: https://github.com/bikinveo-hash/RepairPremium_Repo/tree/main/AdiFilmSemi
// Penulis asli : aldry84  (build.gradle.kts di repo asal menyatakan
//                authors = listOf("aldry84"); package com.michat88)
//
// Repo asal tidak memuat LICENSE maupun README, sehingga tidak ada ketentuan
// redistribusi yang eksplisit. File ini disalin apa adanya aside dari header
// kredit ini. Jangan dianggap karya asli repo ini.
// ---------------------------------------------------------------------------

package com.Adicinemax21

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Shared MovieBox source used by Adicinemax21, AdiDrakor, AdiFilmSemi and AdiXtream.
 *
 * Scope is intentionally MovieBox-only. Metadata, Idlix, VidSrc and each host
 * provider remain owned by their original implementation.
 *
 * Playback memakai BFF milik aplikasi MovieBox TV (`tv.aoneroom.com`). BFF TV
 * melayani stream asli untuk tamu, sedangkan BFF mobile selalu menukar
 * resourceLink dengan klip pemberitahuan 23 detik ("/other/2026/...") selama
 * akun bukan VIP. Pencarian tetap lewat BFF mobile karena indeks BFF TV tidak
 * memuat judul dewasa.
 */
object MovieBoxV2Shared {
    private const val TV_URL = "https://tv.aoneroom.com"
    private const val API_URL = "https://api3.aoneroom.com"
    private const val API_FALLBACK = "https://api4.aoneroom.com"
    private const val USER_AGENT =
        "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"

    private const val ID_PREFS = "moviebox_v2_shared_identity"
    private const val ID_KEY = "apkdeviceid"
    private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

    private const val MAX_SUBJECTS_TV = 6
    private const val MAX_SUBJECTS_MOVIE = 3

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var persisted = false

    private data class Subject(
        val id: String,
        val title: String,
        val type: Int?,
        val releaseDate: String?
    )

    private data class Stream(
        val id: String?,
        val url: String,
        val cookie: String?,
        val resolutions: String?,
        val codec: String?,
        val format: String?
    )

    private data class Resource(
        val url: String,
        val resolution: Int?,
        val codec: String?
    )

    private data class Playback(
        val streams: List<Stream>,
        val resources: List<Resource>
    ) {
        val hasContent: Boolean get() = streams.isNotEmpty() || resources.isNotEmpty()
    }

    private data class Season(val se: Int, val maxEp: Int)

    /** Jaringan putus ke host BFF TV; percobaan berikutnya pasti gagal juga. */
    private class TvUnreachable(cause: Throwable) : Exception(cause)

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        deviceId()
    }

    private val SECRET_BYTES: ByteArray by lazy {
        val step1 = String(
            Base64.decode(
                "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==",
                Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
        Base64.decode(step1, Base64.DEFAULT)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun deviceId(): String {
        val cached = cachedDeviceId
        if (cached != null && persisted) return cached

        val ctx = appContext
        if (ctx != null) {
            try {
                val sp = ctx.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
                val existing = sp.getString(ID_KEY, null)
                if (!existing.isNullOrBlank() && ID_FORMAT.matches(existing)) {
                    cachedDeviceId = existing
                    persisted = true
                    return existing
                }
                val fresh = cached ?: md5(UUID.randomUUID().toString())
                sp.edit().putString(ID_KEY, fresh).apply()
                cachedDeviceId = fresh
                persisted = true
                return fresh
            } catch (_: Exception) {
            }
        }

        return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
    }

    private fun catalogClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.oneroom")
            .put("version_name", "3.0.13.0325.03")
            .put("version_code", 50020088)
            .put("os", "android")
            .put("os_version", "13")
            .put("device_id", deviceId())
            .put("install_store", "ps")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "US")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()

    /** Identitas klien MovieBox TV, dipakai untuk seluruh panggilan BFF TV. */
    private fun tvClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.mbox.tv")
            .put("version_name", "1.1.6.0723.03")
            .put("version_code", 101060723)
            .put("os", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "11")
            .put("device_id", deviceId())
            .put("install_store", "ps")
            .put("system_language", java.util.Locale.getDefault().language)
            .put("net", "NETWORK_ETHERNET")
            .put("region", "US")
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("sp_code", "")
            .toString()

    private fun normalCanonical(
        method: String,
        pathWithQuery: String,
        ts: String,
        body: String = ""
    ): String {
        val length = if (body.isEmpty()) "" else body.length.toString()
        val digest = if (body.isEmpty()) "" else md5(body)
        return listOf(
            method.uppercase(),
            "application/json",
            "application/json",
            length,
            ts,
            digest,
            pathWithQuery
        ).joinToString("\n")
    }

    private fun sign(canonical: String, ts: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val bytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun normalSignature(
        method: String,
        pathWithQuery: String,
        ts: String,
        body: String = ""
    ): String = sign(normalCanonical(method, pathWithQuery, ts, body), ts)

    private fun guestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

    /**
     * Query string selalu diurutkan per key: gateway memakai bentuk yang sama
     * saat menghitung signature, jadi urutan di URL dan di canonical harus
     * identik.
     */
    private fun query(vararg params: Pair<String, String>): String =
        params.sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }

    private fun headers(
        ts: String,
        signature: String,
        bearer: String?,
        status: String,
        clientInfo: String
    ): Map<String, String> {
        val out = mutableMapOf(
            "user-agent" to USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to guestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to clientInfo,
            "x-client-status" to status
        )
        if (!bearer.isNullOrBlank()) out["authorization"] = "Bearer $bearer"
        return out
    }

    private suspend fun call(
        url: String,
        method: String,
        target: String,
        body: String?,
        bearer: String?,
        status: String,
        clientInfo: String,
        tag: String
    ): String? {
        val ts = System.currentTimeMillis().toString()
        val signed = headers(
            ts,
            normalSignature(method, target, ts, body.orEmpty()),
            bearer,
            status,
            clientInfo
        )
        return try {
            val response = if (method == "POST") {
                app.post(
                    "$url$target",
                    headers = signed,
                    requestBody = (body ?: "").toByteArray(Charsets.UTF_8)
                        .toRequestBody("application/json".toMediaTypeOrNull())
                )
            } else {
                app.get("$url$target", headers = signed)
            }
            if (response.code == 200) {
                response.text
            } else {
                Log.w(tag, "[MOVIEBOX-TV] http=${response.code} $target")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "[MOVIEBOX-TV] request gagal $target: ${e.message}")
            throw TvUnreachable(e)
        }
    }

    /** Panggilan BFF TV (tamu: X-Client-Status 1, tanpa Authorization). */
    private suspend fun tvGet(path: String, q: String?, tag: String): String? =
        call(
            TV_URL,
            "GET",
            if (q.isNullOrBlank()) path else "$path?$q",
            null,
            null,
            "1",
            tvClientInfo(),
            tag
        )

    /** Panggilan BFF mobile; hanya dipakai untuk pencarian (butuh bearer). */
    private suspend fun apiPost(path: String, body: String, bearer: String, tag: String): String? {
        for (host in listOf(API_URL, API_FALLBACK)) {
            val raw = try {
                call(
                    host,
                    "POST",
                    path,
                    body,
                    bearer,
                    "0",
                    catalogClientInfo(),
                    tag
                )
            } catch (_: TvUnreachable) {
                continue
            }
            if (raw != null) return raw
        }
        return null
    }

    private suspend fun getBearer(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val q = query("page" to "1", "perPage" to "1", "tabId" to "0")
        return try {
            val response = app.get(
                "$API_URL$path?$q",
                headers = headers(
                    ts,
                    normalSignature("GET", "$path?$q", ts),
                    null,
                    "1",
                    catalogClientInfo()
                )
            )
            val raw = response.headers["x-user"] ?: return null
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
                .find(raw)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeTitle(value: String): String =
        value.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

    /** Tipe katalog MovieBox: 1 = film, 2 = seri, 7 = film pendek. */
    private fun typeMatches(actual: Int?, wanted: Int): Boolean =
        actual == wanted || (wanted == 1 && actual == 7)

    private suspend fun searchSubjects(
        queryText: String,
        matchYear: Int?,
        wantedType: Int,
        bearer: String,
        tag: String
    ): List<Subject> {
        val normalized = normalizeTitle(queryText)
        if (normalized.isBlank()) return emptyList()

        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 10)
            .put("keyword", queryText)
            .put("tabId", "")
            .toString()

        val raw = apiPost("/wefeed-mobile-bff/subject-api/search/v2", body, bearer, tag)
            ?: return emptyList()

        return try {
            val results = JSONObject(raw)
                .optJSONObject("data")
                ?.optJSONArray("results")

            val all = mutableListOf<Subject>()
            val seen = mutableSetOf<String>()
            for (i in 0 until (results?.length() ?: 0)) {
                val subjects = results!!.optJSONObject(i)?.optJSONArray("subjects") ?: continue
                for (j in 0 until subjects.length()) {
                    val item = subjects.optJSONObject(j) ?: continue
                    val id = item.optString("subjectId", "")
                    val title = item.optString("title", "")
                    if (id.isBlank() || title.isBlank() || !seen.add(id)) continue
                    val type = if (item.has("subjectType")) item.optInt("subjectType") else null
                    val date = item.optString("releaseDate", "").takeIf { it.isNotBlank() }
                    all += Subject(id, title, type, date)
                }
            }

            val ranked = mutableListOf<Triple<Int, Boolean, Subject>>()
            for (subject in all) {
                if (subject.type != null && !typeMatches(subject.type, wantedType)) continue
                val cleanTitle = normalizeTitle(subject.title)
                if (cleanTitle.isBlank()) continue
                val rank = when {
                    cleanTitle == normalized -> 0
                    cleanTitle.startsWith(normalized) -> 1
                    cleanTitle.contains(normalized) -> 2
                    normalized.contains(cleanTitle) -> 3
                    else -> -1
                }
                if (rank < 0) continue
                if (subject.type == null && rank != 0) continue

                val subjectYear = subject.releaseDate
                    ?.substringBefore('-')
                    ?.toIntOrNull()
                val yearOk = when {
                    matchYear == null || subjectYear == null -> true
                    wantedType == 2 -> subjectYear >= matchYear - 1
                    else -> abs(subjectYear - matchYear) <= 1
                }
                ranked += Triple(rank, yearOk, subject)
            }

            // Tahun rilis MovieBox sering tidak sama dengan tahun TMDB
            // (contoh: An Affair: My Friend's Mom tertulis 2017 padahal TMDB
            // 2022). Tahun hanya dipakai sebagai preferensi, bukan syarat mutlak,
            // supaya judul yang tepat tidak ikut terbuang.
            val strongPool = ranked.filter { it.first <= 2 }
            val pool = strongPool.ifEmpty { ranked }
            val surePool = pool.filter { it.second }
            val chosen = surePool.ifEmpty { pool }
            chosen.sortedWith(compareBy({ it.first }, { if (it.second) 0 else 1 }))
                .map { it.third }
        } catch (e: Exception) {
            Log.e(tag, "[MOVIEBOX-TV] search parse gagal: ${e.message}")
            emptyList()
        }
    }

    private suspend fun tvSeasons(subjectId: String, tag: String): List<Season> {
        val raw = tvGet(
            "/wefeed-tv-bff/subject/season-info",
            query("subjectId" to subjectId),
            tag
        ) ?: return emptyList()
        return try {
            val array = JSONObject(raw)
                .optJSONObject("data")
                ?.optJSONArray("seasons")
            buildList {
                for (i in 0 until (array?.length() ?: 0)) {
                    val item = array!!.optJSONObject(i) ?: continue
                    if (!item.has("se")) continue
                    add(Season(item.optInt("se"), item.optInt("maxEp", 0)))
                }
            }.distinctBy { it.se }.sortedBy { it.se }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Pasangan se/ep yang patut dicoba. Film memakai se=0&ep=0; film pendek
     * (tipe 7) dan seri selalu punya daftar season walau hanya satu episode.
     */
    private fun probePairs(seasons: List<Season>, season: Int?, episode: Int?): List<Pair<Int, Int>> {
        val wantedEp = (episode ?: 1).coerceAtLeast(1)
        if (season == null) {
            // Film memakai se=0&ep=0. Sebagian entri film tetap melaporkan
            // daftar season (se=0 maxEp=0) atau satu episode (film pendek,
            // tipe 7: se=1 maxEp=1), jadi (0,0) dicoba lebih dulu.
            val fromSeason = seasons.firstOrNull()?.let { item ->
                item.se to (if (item.maxEp > 0) wantedEp.coerceAtMost(item.maxEp) else 0)
            }
            return listOfNotNull(0 to 0, fromSeason).distinct()
        }

        val exact = seasons.firstOrNull { it.se == season }
        if (exact != null) {
            val ep = if (exact.maxEp > 0) wantedEp.coerceAtMost(exact.maxEp) else wantedEp
            return listOf(exact.se to ep, exact.se to 1, exact.se to 0).distinct()
        }

        return buildList {
            add(season to wantedEp)
            seasons.firstOrNull()?.let { add(it.se to wantedEp) }
            add(0 to 0)
        }.distinct()
    }

    private fun noticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("b164fbfb4347792950bdfbfb563d39d9") ||
            lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/other/2026/") ||
            lower.contains("/other/") ||
            lower.contains("/notice.mp4") ||
            lower.contains("upgrade-notice")
    }

    private suspend fun tvPlayInfo(
        subjectId: String,
        se: Int,
        ep: Int,
        tag: String
    ): Playback? {
        val raw = tvGet(
            "/wefeed-tv-bff/subject/play-info",
            query(
                "subjectId" to subjectId,
                "se" to se.toString(),
                "ep" to ep.toString()
            ),
            tag
        ) ?: return null

        return try {
            val root = JSONObject(raw)
            if (root.has("code") && root.optInt("code", 0) != 0) return null
            val data = root.optJSONObject("data") ?: return null

            val streams = buildList {
                val array = data.optJSONArray("streams")
                for (i in 0 until (array?.length() ?: 0)) {
                    val item = array!!.optJSONObject(i) ?: continue
                    val url = item.optString("url", "")
                    if (url.isBlank() || noticeUrl(url)) continue
                    add(
                        Stream(
                            id = item.optString("id", "").takeIf { it.isNotBlank() },
                            url = url,
                            cookie = item.optString("signCookie", "").takeIf { it.isNotBlank() },
                            resolutions = item.optString("resolutions", "").takeIf { it.isNotBlank() },
                            codec = item.optString("codecName", "").takeIf { it.isNotBlank() },
                            format = item.optString("format", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            val resources = buildList {
                val array = data.optJSONArray("resources")
                for (i in 0 until (array?.length() ?: 0)) {
                    val item = array!!.optJSONObject(i) ?: continue
                    val url = item.optString("url", "")
                    if (url.isBlank() || noticeUrl(url)) continue
                    add(
                        Resource(
                            url = url,
                            resolution = Regex("""\d{3,4}""")
                                .find(item.optString("resolution", ""))
                                ?.value?.toIntOrNull(),
                            codec = item.optString("codec", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            Playback(streams.distinctBy { it.url }, resources.distinctBy { it.url })
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun subtitles(
        subjectId: String,
        streamId: String?,
        tag: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (streamId.isNullOrBlank()) return
        val raw = tvGet(
            "/wefeed-tv-bff/subject/stream-captions",
            query("subjectId" to subjectId, "streamId" to streamId),
            tag
        ) ?: return
        try {
            val data = JSONObject(raw).optJSONObject("data") ?: return
            val array = data.optJSONArray("captions") ?: data.optJSONArray("extCaptions")
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.isBlank()) continue
                val label = item.optString("lanName", "").ifBlank {
                    item.optString("lan", "").ifBlank {
                        item.optString("language", "").ifBlank { "Unknown" }
                    }
                }
                subtitleCallback(newSubtitleFile(label, url))
            }
        } catch (_: Exception) {
        }
    }

    private fun linkType(stream: Stream): ExtractorLinkType {
        val url = stream.url.lowercase()
        return when {
            url.contains(".mpd") || stream.format.equals("dash", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8") || stream.format.equals("hls", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun maxQuality(resolutions: String?, fallback: Int = 1080): Int =
        Regex("""\d{3,4}""")
            .findAll(resolutions.orEmpty())
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 144..4320 }
            .maxOrNull() ?: fallback

    private suspend fun emit(playback: Playback, tag: String, callback: (ExtractorLink) -> Unit) {
        val stream = playback.streams.firstOrNull()
        if (stream != null) {
            // Satu link saja: resolusi DASH muncul sebagai video track ExoPlayer,
            // jadi link terpisah per resolusi hanya akan menduplikasi.
            val q = maxQuality(stream.resolutions)
            val headers = mutableMapOf(
                "Referer" to "$TV_URL/",
                "User-Agent" to USER_AGENT
            )
            stream.cookie?.let { headers["Cookie"] = it }
            Log.d(
                tag,
                "[MOVIEBOX-TV] DASH quality=${q}p codec=${stream.codec} " +
                    "cookie=${!stream.cookie.isNullOrBlank()}"
            )
            callback(
                newExtractorLink(
                    source = "MovieBox",
                    name = "MovieBox ${q}p",
                    url = stream.url,
                    type = linkType(stream)
                ) {
                    quality = q
                    this.headers = headers
                }
            )
            return
        }

        // Fallback: hanya bila DASH tidak tersedia. Satu link per resolusi,
        // h264 diutamakan saat resolusi sama.
        val mp4 = playback.resources
            .sortedWith(
                compareByDescending<Resource> { it.resolution ?: 0 }
                    .thenBy { if (it.codec.equals("h264", true)) 0 else 1 }
            )
            .distinctBy { it.resolution }
            .take(3)
        Log.d(tag, "[MOVIEBOX-TV] fallback MP4 sebanyak ${mp4.size} resolusi")
        for (item in mp4) {
            val q = item.resolution ?: 1080
            callback(
                newExtractorLink(
                    source = "MovieBox",
                    name = "MovieBox ${q}p",
                    url = item.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    quality = q
                    this.headers = mapOf(
                        "Referer" to "$TV_URL/",
                        "User-Agent" to USER_AGENT
                    )
                }
            )
        }
    }

    suspend fun invokeMoviebox(
        sourceTag: String,
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?,
        airedYear: Int? = null,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val matchYear = if (season != null) (airedYear ?: year) else year
        val wantedType = if (season != null) 2 else 1
        val bearer = getBearer() ?: run {
            Log.e(sourceTag, "[MOVIEBOX-TV] bearer token null (ranking-list gagal)")
            return
        }

        val queries = listOfNotNull(
            title,
            title.substringBefore(':'),
            orgTitle,
            orgTitle?.substringBefore(':'),
            altTitle,
            altTitle?.substringBefore(':')
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        var candidates = emptyList<Subject>()
        for (queryText in queries) {
            candidates = searchSubjects(queryText, matchYear, wantedType, bearer, sourceTag)
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) {
            Log.w(sourceTag, "[MOVIEBOX-TV] tidak ada subject untuk '$title'")
            return
        }

        val pool = candidates.take(if (season == null) MAX_SUBJECTS_MOVIE else MAX_SUBJECTS_TV)
        var playback: Playback? = null
        var chosenId: String? = null

        try {
            loop@ for (subject in pool) {
                val seasons = tvSeasons(subject.id, sourceTag)
                for ((se, ep) in probePairs(seasons, season, episode)) {
                    val current = tvPlayInfo(subject.id, se, ep, sourceTag)
                    if (current != null && current.hasContent) {
                        chosenId = subject.id
                        playback = current
                        Log.d(
                            sourceTag,
                            "[MOVIEBOX-TV] subject=${subject.id} se=$se ep=$ep " +
                                "streams=${current.streams.size} mp4=${current.resources.size}"
                        )
                        break@loop
                    }
                }
            }
        } catch (_: TvUnreachable) {
            Log.e(
                sourceTag,
                "[MOVIEBOX-TV] STOP: $TV_URL tidak bisa dihubungi dari jaringan ini; " +
                    "sumber MovieBox dilewati."
            )
            return
        }

        val current = playback ?: run {
            Log.w(
                sourceTag,
                "[MOVIEBOX-TV] tidak ada stream untuk '$title' " +
                    "(katalog MovieBox ada, tapi tanpa resource)"
            )
            return
        }

        val subjectId = chosenId ?: return
        try {
            subtitles(subjectId, current.streams.firstOrNull()?.id, sourceTag, subtitleCallback)
        } catch (_: TvUnreachable) {
            // Subtitle opsional: kegagalan jaringan di sini tidak boleh
            // membatalkan link yang sudah didapat.
        }
        emit(current, sourceTag, callback)
    }
}
