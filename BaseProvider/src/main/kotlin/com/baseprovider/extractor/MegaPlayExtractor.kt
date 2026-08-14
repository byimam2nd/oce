package com.baseprovider.extractor
import com.baseprovider.cache.AdaptiveDecryptCache
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

open class MegaPlay : ExtractorApi() {
    override var name = "MegaPlay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    private val decryptCache = AdaptiveDecryptCache()

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url).document
        val id = doc.selectFirst("#megaplay-player")
            ?.attr("data-id") ?: return
        val apiUrl = "$mainUrl/stream/getSources?id=$id"
        val responseText = decryptCache.get(apiUrl) ?: run {
            val resp = app.get(apiUrl).text
            decryptCache.put(apiUrl, resp)
            resp
        }
        val json = JSONObject(responseText)
        val m3u8 = json.optJSONObject("sources")
            ?.optString("file") ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}
