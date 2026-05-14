package com.Dutamovie21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class Dutamovie21Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Dutamovie21())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
