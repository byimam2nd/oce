package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


open class LuluStream : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val filecode = url.substringAfterLast("/")
        val doc = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to filecode,
                "auto" to "1",
                "referer" to (referer ?: "")
            )
        ).document
        val script = doc.selectFirst("script:containsData(vplayer)")
            ?.data() ?: return
        val urls = CompiledRegexPatterns.extractAllVideoUrls(script)
        CompiledRegexPatterns.prioritizeAdaptiveUrls(urls).forEach {
            MasterLinkGenerator.createSmartLink(this.name, it, mainUrl,
                callback = callback)
        }
    }
}
