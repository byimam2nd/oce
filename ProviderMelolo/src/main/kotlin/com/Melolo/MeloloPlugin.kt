package com.Melolo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MeloloPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Melolo())

        MeloloEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
