package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second, packed.third)
            CompiledRegexPatterns.extractAllVideoUrls(unpacked).let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
            }
        } else {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("(m3u8|master\\.txt)"),
                    additionalUrls = listOf(Regex("(m3u8|master\\.txt)")),
                    useOkhttp = false,
                    timeout = 15_000L
                )
                val interceptedUrl = app.get(url, referer = referer, interceptor = resolver).url
                if (interceptedUrl.isNotBlank()) {
                    MasterLinkGenerator.createSmartLink(this.name, interceptedUrl, url, callback = callback)
                }
            } catch (_: Exception) {}
        }
    }
}
