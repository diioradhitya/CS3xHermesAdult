package com.nekopoi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NekoPoiProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NekoPoiProvider())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Streampoi())
    }
}