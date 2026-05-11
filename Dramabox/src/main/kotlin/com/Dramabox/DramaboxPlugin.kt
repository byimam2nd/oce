package com.Dramabox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramaboxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dramabox())

        DramaboxEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
