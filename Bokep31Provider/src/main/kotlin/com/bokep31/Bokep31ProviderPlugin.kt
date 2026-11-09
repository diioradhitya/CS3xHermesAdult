package com.bokep31

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Bokep31ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Bokep31Provider())
        registerExtractorAPI(MumuExtractor())
        registerExtractorAPI(LuluStreamExtractor())
    }
}
