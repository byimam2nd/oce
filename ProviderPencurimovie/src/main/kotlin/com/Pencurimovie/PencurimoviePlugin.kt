package com.Pencurimovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class PencurimoviePlugin: BasePlugin() {
    override fun load() {
        val api = Pencurimovie()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
    }
}
