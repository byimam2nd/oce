package com.Samehadaku

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.ProviderExtractors

@CloudstreamPlugin
class SamehadakuPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Samehadaku())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
