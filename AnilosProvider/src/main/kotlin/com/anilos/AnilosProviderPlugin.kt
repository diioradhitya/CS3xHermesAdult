package com.anilos

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnilosProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnilosProvider())
    }
}