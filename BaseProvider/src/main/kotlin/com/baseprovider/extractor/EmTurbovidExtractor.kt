package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class EmTurbovid : ExtractorApi() {
    override var name = "EmTurbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val playerScript = response.document
            .select("script")
            .first { it.data().contains("var urlPlay") }
            ?.data()
        if (playerScript.isNullOrBlank()) return
        val m3u8Url = playerScript.substringAfter("var urlPlay = '")
            .substringBefore("'")
        if (m3u8Url.isBlank()) return
        MasterLinkGenerator.createSmartLink(this.name, m3u8Url,
            "$mainUrl/", headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}