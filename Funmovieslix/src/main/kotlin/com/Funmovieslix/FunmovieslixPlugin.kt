package com.Funmovieslix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FunmovieslixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Funmovieslix())

        FunmovieslixEkstraktors.list.forEach { extractor ->
            registerExtractorAPI(extractor)
        }
    }
}
