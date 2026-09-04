package com.podjav

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PodJavProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PodJavProvider())
    }
}
