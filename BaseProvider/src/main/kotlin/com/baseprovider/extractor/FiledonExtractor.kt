package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

class Filedon : ExtractorApi() {
    override var name = "Filedon"
    override var mainUrl = "https://filedon.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        if (urls.isNotEmpty()) {
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
        }
    }
}
