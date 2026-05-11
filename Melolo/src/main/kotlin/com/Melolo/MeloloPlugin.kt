package com.Melolo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MeloloPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Melolo())
        
        MeloloEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
