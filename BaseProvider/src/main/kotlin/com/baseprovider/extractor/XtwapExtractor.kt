package com.baseprovider.extractor
import com.baseprovider.model.fixUrlSmart
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Xtwap : ExtractorApi() {
    override var name = "Xtwap"
    override var mainUrl = "https://xtwap.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val filePath = Regex(""""file":"([^"]+)"""").find(scripts)?.groupValues?.getOrNull(1) ?: return
        val m3u8 = fixUrlSmart(filePath, url)
        MasterLinkGenerator.createSmartLink(this.name, m3u8, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}
