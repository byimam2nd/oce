package com.Dramabox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class DramaboxPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Dramabox())
        
        DramaboxEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
