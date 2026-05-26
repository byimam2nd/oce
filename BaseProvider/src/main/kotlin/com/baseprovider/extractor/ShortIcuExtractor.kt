package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

class ShortIcu : ExtractorApi() {
    override var name = "ShortIcu"
    override var mainUrl = "https://short.icu"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val finalUrl = response.url
        if (finalUrl != url) {
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }

        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { videoUrl ->
            MasterLinkGenerator.createSmartLink(this.name, videoUrl, finalUrl, callback = callback)
        }
    }
}
