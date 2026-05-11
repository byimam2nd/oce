package com.LayarKaca21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(LayarKaca21())
        
        // Register specific extractors for LayarKaca21
        LayarKaca21Ekstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
