package com.Donghuastream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DonghuastreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Donghuastream())

        DonghuastreamEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
