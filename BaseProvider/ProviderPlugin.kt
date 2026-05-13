package com.baseprovider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TemplatesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(ProviderCloudstream())
        
        // Register specific extractors for TemplatesProvider
        ProviderExtractors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
