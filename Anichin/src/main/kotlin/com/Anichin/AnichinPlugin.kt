package com.Anichin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())

        // Register specific extractors for Anichin
        AnichinEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
