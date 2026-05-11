package com.Samehadaku

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class SamehadakuPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Samehadaku())
        
        SamehadakuEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
