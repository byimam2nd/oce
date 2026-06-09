package com.Samehadaku

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class SamehadakuPlugin: BasePlugin() {
    override fun load() {
        val api = Samehadaku()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
    }
}
