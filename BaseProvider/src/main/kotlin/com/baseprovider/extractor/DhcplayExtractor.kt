package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.api.Log


class Dhcplay : ExtractorApi() {
    override var name = "Dhcplay"
    override var mainUrl = "https://dhcplay.com"
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
        val text = app.get(url, referer = referer).text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second,
                packed.third)
            var found = false
            CompiledRegexPatterns.extractAllVideoUrls(unpacked)
                .let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                    found = true
                    MasterLinkGenerator.createSmartLink(this.name, it, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            }
            if (found) return
        }
        val urls = CompiledRegexPatterns.extractAllVideoUrls(text)
        CompiledRegexPatterns.filterMasterM3u8(urls)
            .forEach { MasterLinkGenerator.createSmartLink(this.name, it,
                null, headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback) }
        try {
            val interceptedUrl = app.get(url, referer = referer,
                interceptor = resolver).url
            if (interceptedUrl.isNotBlank()) {
                MasterLinkGenerator.createSmartLink(this.name,
                    interceptedUrl, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true, callback = callback)
            }
        } catch (e: Exception) { Log.d("Dhcplay", "WebViewResolver failed: ${e.message}") }
    }
}
