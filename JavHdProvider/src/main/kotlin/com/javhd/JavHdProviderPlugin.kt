package com.javhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHdProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavHdProvider())
    }
}
