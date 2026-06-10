package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class AnichinStream : ExtractorApi() {
    override var name = "AnichinStream"
    override var mainUrl = "https://anichin.stream"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val id = Regex("[?&]id=([^&]+)").find(url)?.groupValues
            ?.get(1) ?: return
        val videoUrl = "$mainUrl/hls/$id.m3u8"
        MasterLinkGenerator.createSmartLink(this.name, videoUrl,
            referer ?: mainUrl, callback = callback)
    }
}
