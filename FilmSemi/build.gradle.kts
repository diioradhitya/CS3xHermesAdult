// ---------------------------------------------------------------------------
// FilmSemi  (sebelumnya bernama AdiFilmSemi)
//
// Diambil dari: https://github.com/bikinveo-hash/RepairPremium_Repo/tree/main/AdiFilmSemi
// Penulis asli : aldry84  (build.gradle.kts di repo asal menyatakan
//                authors = listOf("aldry84"); package com.michat88)
//
// Repo asal tidak memuat LICENSE maupun README, sehingga tidak ada ketentuan
// redistribusi yang eksplisit. File ini disalin apa adanya aside dari header
// kredit ini. Jangan dianggap karya asli repo ini.
// ---------------------------------------------------------------------------

// use an integer for version numbers
version = 28


cloudstream {
    authors     = listOf("Dio R")
    language    = "id"
    description = "FilmSemi — MovieBox, Idlix, VidSrc (film & seri NSFW)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")

    iconUrl = "https://klikxxi.me/wp-content/uploads/2024/02/cropped-site-icon.png"
}
