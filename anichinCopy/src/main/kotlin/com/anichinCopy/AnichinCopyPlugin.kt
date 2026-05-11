package com.anichinCopy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinCopyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnichinCopy())
    }
}
