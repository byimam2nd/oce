package com.Animasu

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimasuPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Animasu())

        AnimasuEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
