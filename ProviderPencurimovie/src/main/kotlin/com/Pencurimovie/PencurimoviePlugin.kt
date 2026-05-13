package com.Pencurimovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class PencurimoviePlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Pencurimovie())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
