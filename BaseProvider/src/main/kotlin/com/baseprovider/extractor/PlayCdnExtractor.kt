package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

private val PLAYCDN_VIDEO_ID_REGEX = Regex("""video\.php\?id=([a-zA-Z0-9]+)""")

class PlayCdn : CachedExtractorApi() {
    override var name = "PlayCdn"
    override var mainUrl = "https://playcdn.de"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val id = PLAYCDN_VIDEO_ID_REGEX.find(url)?.groupValues
            ?.getOrNull(1) ?: return
        val response = cachedPostText(
            "$mainUrl/api2.php?id=$id",
            data = mapOf("r" to "", "d" to "playcdn.de"),
            referer = url
        )
        val file = JSONObject(response).optString("file")
        if (file.isBlank()) return
        val videoUrl = if (file.startsWith("http")) file else
            "$mainUrl$file"
        MasterLinkGenerator.createSmartLink(this.name, videoUrl,
            "$mainUrl/", headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}