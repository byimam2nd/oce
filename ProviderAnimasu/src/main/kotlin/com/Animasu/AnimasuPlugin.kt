package com.Animasu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class AnimasuPlugin: BasePlugin() {
    override fun load() {
        val api = Animasu()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
    }
}
