package com.Pencurimovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Pencurimovie())
        
        // Register specific extractors for TemplatesProvider
        PencurimovieEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
