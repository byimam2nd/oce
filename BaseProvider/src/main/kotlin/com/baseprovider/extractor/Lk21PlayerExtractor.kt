package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

class Lk21PlayerPage : ExtractorApi() {
    override var name = "Lk21Player"
    override var mainUrl = "https://playeriframe.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractorWithFallbackCustom(src, url, subtitleCallback, callback = callback, providerTag = "Lk21Player", callChain = "Lk21Player")
            }
        }
    }
}
