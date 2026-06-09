package com.Anichin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class AnichinPlugin: BasePlugin() {
    override fun load() {
        val api = Anichin()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
    }
}
