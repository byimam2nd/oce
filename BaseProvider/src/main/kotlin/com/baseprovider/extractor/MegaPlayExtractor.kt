package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.json.JSONObject

open class MegaPlay : ExtractorApi() {
    override var name = "MegaPlay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url).document
        val id = doc.selectFirst("#megaplay-player")?.attr("data-id") ?: return
        val apiUrl = "$mainUrl/stream/getSources?id=$id"
        val json = JSONObject(app.get(apiUrl).text)
        val m3u8 = json.optJSONObject("sources")?.optString("file") ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, mainUrl, callback = callback)
    }
}
