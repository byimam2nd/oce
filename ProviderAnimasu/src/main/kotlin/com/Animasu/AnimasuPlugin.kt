package com.Animasu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Animasu())
        
        // Register specific extractors for Animasu
        AnimasuEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
