package com.Idlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class IdlixPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Idlix())
        
        IdlixEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
