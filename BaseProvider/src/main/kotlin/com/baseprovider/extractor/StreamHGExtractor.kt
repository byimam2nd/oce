package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.api.Log


class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    private val resolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("(m3u8|master\\.txt)"),
            additionalUrls = listOf(Regex("(m3u8|master\\.txt)")),
            useOkhttp = false,
            timeout = 15_000L
        )
    }

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second,
                packed.third)
            CompiledRegexPatterns.extractAllVideoUrls(unpacked)
                .let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                    MasterLinkGenerator.createSmartLink(this.name, it, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            }
        } else {
            try {
                val interceptedUrl = app.get(url, referer = referer,
                    interceptor = resolver).url
                if (interceptedUrl.isNotBlank()) {
                    MasterLinkGenerator.createSmartLink(this.name,
                        interceptedUrl, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            } catch (e: Exception) { Log.d("StreamHG", "WebViewResolver failed: ${e.message}") }
        }
    }
}
