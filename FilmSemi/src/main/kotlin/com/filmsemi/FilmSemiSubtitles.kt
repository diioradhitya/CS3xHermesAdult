// ---------------------------------------------------------------------------
// FilmSemi
//
// Diambil dari: https://github.com/bikinveo-hash/RepairPremium_Repo/tree/main/AdiFilmSemi
// Penulis asli : aldry84  (build.gradle.kts di repo asal menyatakan
//                authors = listOf("aldry84"); package com.michat88)
//
// Repo asal tidak memuat LICENSE maupun README, sehingga tidak ada ketentuan
// redistribusi yang eksplisit. File ini disalin apa adanya aside dari header
// kredit ini. Jangan dianggap karya asli repo ini.
// ---------------------------------------------------------------------------

package com.filmsemi

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.subtitleProviders
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newSubtitleFile
import kotlin.coroutines.cancellation.CancellationException

object FilmSemiSubtitles {
    private const val TAG = "FilmSemiSUB"
    private const val MAX_PER_PROVIDER = 2
    private const val MAX_TOTAL = 4

    private val PREFERRED = listOf("id", "en")

    private fun ietfTag(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return SubtitleHelper.fromCodeToLangTagIETF(raw)
            ?: SubtitleHelper.fromLanguageToTagIETF(raw, halfMatch = true)
    }

    private fun languageRank(raw: String?): Int {
        val tag = ietfTag(raw)?.lowercase() ?: return PREFERRED.size
        val index = PREFERRED.indexOfFirst { tag == it || tag.startsWith("$it-") }
        return if (index >= 0) index else PREFERRED.size
    }

    private fun label(raw: String?, provider: String): String {
        val name = ietfTag(raw)?.let { SubtitleHelper.fromTagToEnglishLanguageName(it) }
        return if (name.isNullOrBlank()) provider else "$name ($provider)"
    }

    suspend fun emit(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        tmdbId: Int?,
        imdbId: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        if (title.isBlank()) return 0

        val providers = try {
            subtitleProviders
        } catch (error: Throwable) {
            Log.e(TAG, "[SUB] mesin subtitle CloudStream tidak tersedia: ${error.message}")
            return 0
        }

        val query = SubtitleSearch(
            query = title,
            lang = null,
            imdbId = imdbId,
            tmdbId = tmdbId,
            epNumber = episode,
            seasonNumber = season,
            year = year
        )

        val sent = HashSet<String>()
        var total = 0

        for (provider in providers) {
            if (total >= MAX_TOTAL) break
            val found = try {
                provider.search(query).getOrNull()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.d(TAG, "[SUB] ${provider.idPrefix} pencarian gagal: ${error.message}")
                null
            } ?: continue
            if (found.isEmpty()) continue

            var perProvider = 0
            for (entity in found.sortedBy { languageRank(it.lang) }) {
                if (perProvider >= MAX_PER_PROVIDER || total >= MAX_TOTAL) break
                val resource = try {
                    provider.resource(entity).getOrNull()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.d(TAG, "[SUB] ${provider.idPrefix} unduhan gagal: ${error.message}")
                    null
                } ?: continue

                for (sub in resource.getSubtitles()) {
                    if (perProvider >= MAX_PER_PROVIDER || total >= MAX_TOTAL) break
                    if (!sent.add(sub.url)) continue
                    subtitleCallback(newSubtitleFile(label(entity.lang, provider.name), sub.url))
                    perProvider++
                    total++
                }
            }
            Log.i(TAG, "[SUB] ${provider.idPrefix}: ${found.size} hasil, $perProvider dikirim")
        }

        if (total > 0) Log.i(TAG, "[SUB] '$title' -> $total subtitle")
        else Log.d(TAG, "[SUB] '$title' tidak ada subtitle (provider aktif: ${providers.size})")
        return total
    }
}
