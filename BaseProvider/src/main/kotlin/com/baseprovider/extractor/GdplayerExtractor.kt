package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.json.JSONObject

open class Gdplayer : ExtractorApi() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val script = doc.selectFirst("script:containsData(player = \"\")")?.data() ?: return
        val kaken = script.substringAfter("kaken = \"").substringBefore("\"")
        val json = JSONObject(app.get("$mainUrl/api/?${kaken}=&_=${System.currentTimeMillis()}", headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text)
        val sources = json.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val file = sources.getJSONObject(i).optString("file")
            if (file.isNotBlank()) MasterLinkGenerator.createSmartLink(this.name, file, mainUrl, callback = callback)
        }
    }
}
