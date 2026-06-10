package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class PlayPutarIn : ExtractorApi() {
    override var name = "PlayPutarIn"
    override var mainUrl = "https://play.putar.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val targetUrl = url.substringAfter("?url=").let { java.net
            .URLDecoder.decode(it, "UTF-8") }
        if (targetUrl.isNotBlank() && targetUrl.startsWith("http")) {
            loadExtractorWithFallbackCustom(
                targetUrl,
                url,
                subtitleCallback,
                callback = callback,
                providerTag = this.name,
                callChain = "PlayPutarIn"
            )
        }
    }
}
