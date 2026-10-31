package com.javtiful

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavtifulProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavtifulProvider())
    }
}