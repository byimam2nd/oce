package com.baseprovider.extractor
import com.baseprovider.cache.AdaptiveDecryptCache
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

open class AWSStream : ExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    private val decryptCache = AdaptiveDecryptCache()

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val hash = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
        val responseText = decryptCache.get(apiUrl) ?: run {
            val resp = app.post(
                apiUrl,
                headers = mapOf("x-requested-with" to "XMLHttpRequest"),
                data = mapOf("hash" to hash, "r" to mainUrl)
            ).text
            decryptCache.put(apiUrl, resp)
            resp
        }
        val json = JSONObject(responseText)
        val m3u8 = json.optString("videoSource")
        if (m3u8.isNotBlank()) MasterLinkGenerator.createSmartLink(this
            .name, m3u8, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}
