package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Wishfast : ExtractorApi() {
    override var name = "wishfast";
    override var mainUrl = "https://wishfast.to";
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val doc = response.document

        val script: String? = findPackedJsInPage(text)?.let {
            decodePackedJs(it.first, it.second, it.third)
        } ?: doc.selectFirst("script:containsData(sources:)")?.data()

        if (script != null) {
            val fileUrl = Regex("""file:\s*"(.*?m3u8.*?)"""").find(script)
                ?.groupValues?.getOrNull(1)
            if (fileUrl != null) {
                MasterLinkGenerator.createSmartLink(this.name, fileUrl, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true, callback = callback)
                return
            }
        }

        val urls = CompiledRegexPatterns.extractAllVideoUrls(text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
            MasterLinkGenerator.createSmartLink(this.name, it, null,
                headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback)
        }
    }
}
