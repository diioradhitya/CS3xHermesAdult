package com.bokepindoh

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BokepIndohProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BokepIndohProvider())
        registerExtractorAPI(MumuExtractor())
    }
}
