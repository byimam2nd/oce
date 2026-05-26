package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.json.JSONObject

open class AWSStream : ExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val hash = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
        val response = app.post(apiUrl, headers = mapOf("x-requested-with" to "XMLHttpRequest"), data = mapOf("hash" to hash, "r" to mainUrl)).text
        val json = JSONObject(response)
        val m3u8 = json.optString("videoSource")
        if (m3u8.isNotBlank()) MasterLinkGenerator.createSmartLink(this.name, m3u8, "", callback = callback)
    }
}
