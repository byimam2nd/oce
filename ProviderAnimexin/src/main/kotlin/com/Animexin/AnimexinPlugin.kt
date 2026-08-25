package com.Animexin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.baseprovider.extractor.ProviderExtractors
import com.baseprovider.settings.OceSettings

@CloudstreamPlugin
class AnimexinPlugin : Plugin() {
    override fun load(context: Context) {
        OceSettings.attach(context)
        val api = Animexin()
        registerMainAPI(api)
        ProviderExtractors.filtered(api.config.allowedExtractors).forEach { registerExtractorAPI(it) }
        OceSettings.install(this, api.providerId, api.config)
    }
}
