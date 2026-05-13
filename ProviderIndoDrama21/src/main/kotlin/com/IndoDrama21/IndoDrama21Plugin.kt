package com.IndoDrama21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class IndoDrama21Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(IndoDrama21())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
