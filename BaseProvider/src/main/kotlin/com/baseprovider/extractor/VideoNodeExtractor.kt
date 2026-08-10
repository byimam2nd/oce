package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class VideoNodePage : ExtractorApi() {
    override var name = "VideoNodePage"
    override var mainUrl = "https://videonode.de"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractorWithFallbackCustom(
                    src, url, subtitleCallback,
                    callback = callback,
                    providerTag = "VideoNodePage",
                    callChain = "VideoNodePage"
                )
            }
        }
    }
}