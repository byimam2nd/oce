package com.LayarKaca21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class LayarKaca21Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(LayarKaca21())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
