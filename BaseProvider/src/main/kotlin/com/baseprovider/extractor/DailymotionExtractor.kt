package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Dailymotion : ExtractorApi() {
    override var name = "Dailymotion"; override var mainUrl = "https://dailymotion.com"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url).text
        val urls = CompiledRegexPatterns.extractAllVideoUrls(res)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, null, callback = callback) }
        CompiledRegexPatterns.DAILYMOTION_SUBTITLE.findAll(res).forEach { subtitleCallback.invoke(SubtitleFile(it.groupValues[1], it.groupValues[2].replace("\\/", "/"))) }
    }
}
