package com.Anichin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        
        // Register specific extractors for Anichin
        AnichinEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
