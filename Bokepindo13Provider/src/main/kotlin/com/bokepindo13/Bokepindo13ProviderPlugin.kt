package com.bokepindo13

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Bokepindo13ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Bokepindo13Provider())
        registerExtractorAPI(MumuExtractor())
        registerExtractorAPI(LuluStreamExtractor())
        registerExtractorAPI(BebasBokepExtractor())
    }
}
