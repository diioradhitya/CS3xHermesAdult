package com.javstory

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavStoryProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavStoryProvider())
    }
}