package com.avtube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AVTubeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AVTubeProvider())
    }
}