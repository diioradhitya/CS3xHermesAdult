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

package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

/** MovieBox-only compatibility shim for the CloudStream API used by this repo. */
fun newSubtitleFile(name: String, url: String): SubtitleFile = SubtitleFile(name, url)
