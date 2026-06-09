package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Movearnpre : ExtractorApi() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val text = app.get(url, referer = referer).text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second, packed.third)
            var found = false
            CompiledRegexPatterns.extractAllVideoUrls(unpacked).let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                    found = true
                    MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback)
                }
            }
            if (found) return
        }
        CompiledRegexPatterns.extractAllVideoUrls(text).let { urls ->
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback)
            }
        }
    }
}
