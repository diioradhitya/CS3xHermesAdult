package com.awtub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AWTubProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AWTubProvider())
    }
}