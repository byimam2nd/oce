package com.Donghuastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class DonghuastreamPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Donghuastream())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
