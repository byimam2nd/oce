package com.Animasu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class AnimasuPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Animasu())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
