package com.IndoDrama21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class IndoDrama21Plugin: BasePlugin() {
    override fun load() {
        val api = IndoDrama21()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
    }
}
