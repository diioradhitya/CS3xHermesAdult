package com.milfed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MilfedProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MilfedProvider())
    }
}
