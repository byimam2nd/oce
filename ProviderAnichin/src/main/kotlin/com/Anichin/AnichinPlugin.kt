package com.Anichin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class AnichinPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
