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

package com.michat88

import android.content.Context
import com.Adicinemax21.Adicinemax21VidSrc
import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        AdiFilmSemiExtractor.attachContext(context)
        MovieBoxV2Shared.attachContext(context)
        Adicinemax21VidSrc.attachContext(context)

        // Tiga sumber berdiri sendiri di plugin ini: MovieBox, Idlix, VidSrc.
        registerMainAPI(AdiFilmSemiPlaybackFixedProvider())
    }
}
