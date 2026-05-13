package com.Samehadaku

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Samehadaku())
        
        // Register specific extractors for TemplatesProvider
        SamehadakuEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
