package com.IndoDrama21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(IndoDrama21())
        
        // Register specific extractors for TemplatesProvider
        IndoDrama21Ekstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
