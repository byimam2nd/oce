package com.LayarKaca21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LayarKaca21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LayarKaca21())

        LayarKaca21Ekstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
