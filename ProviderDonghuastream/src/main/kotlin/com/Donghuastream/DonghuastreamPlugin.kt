package com.Donghuastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Donghuastream())
        
        // Register specific extractors for Donghuastream
        DonghuastreamEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
