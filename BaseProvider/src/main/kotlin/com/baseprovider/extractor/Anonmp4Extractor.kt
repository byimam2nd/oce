package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * Anonmp4.art — embed page berisi token hardcoded
 * `cryoapi.shadowapi.skin/load/{token}` → GET API → `hls` (m3u8).
 */
class Anonmp4 : ExtractorApi() {
    override var name = "Anonmp4"
    override var mainUrl = "https://anonmp4.art"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text
        val token = Regex("""cryoapi\.shadowapi\.skin/load/([a-zA-Z0-9]+)""")
            .find(html)?.groupValues?.get(1) ?: return

        val apiText = app.get("https://cryoapi.shadowapi.skin/load/$token").text
        val json = runCatching { JSONObject(apiText) }.getOrNull() ?: return
        if (json.optString("status") != "ok") return
        val hls = json.optString("hls")
        if (hls.isBlank()) return

        MasterLinkGenerator.createSmartLink(
            this.name, hls, referer,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true,
            callback = callback
        )
    }
}