package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

open class Gdplayer : ExtractorApi() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val script = doc.selectFirst("script:containsData(player = \"\")")
            ?.data() ?: return
        val kaken = script.substringAfter("kaken = \"").substringBefore("\"")
        val apiUrl = "$mainUrl/api/?${kaken}=&_=${System.currentTimeMillis()}"
        val json = JSONObject(
            app.get(apiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
        )
        val sources = json.optJSONArray("sources") ?: return
        val fileUrls = mutableListOf<String>()
        for (i in 0 until sources.length()) {
            val file = sources.optJSONObject(i)?.optString("file") ?: ""
            if (file.isNotBlank()) fileUrls.add(file)
        }
        CompiledRegexPatterns.prioritizeAdaptiveUrls(fileUrls).forEach {
            MasterLinkGenerator.createSmartLink(this.name, it, null,
                headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback)
        }
    }
}
