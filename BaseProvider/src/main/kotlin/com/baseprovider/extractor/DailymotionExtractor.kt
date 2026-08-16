package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class Dailymotion : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Format geo.dailymotion.com/player/xid0t.html?video=ID -> ambil ID
        // dari query param, bukan dari path (xid0t.html tidak match regex).
        val id = Regex("""[?&]video=([^&]+)""").find(url)?.groupValues
            ?.get(1) ?: url.substringAfterLast('/')
            .substringBefore('?').takeIf { videoIdRegex.matches(it) } ?: return
        val embedUrl = if (url.contains("/embed/") || url.contains(
            "/video/")) url else "${baseUrl}/embed/video/$id"
        val metadataUrl = "$baseUrl/player/metadata/video/$id"

        val response = app.get(metadataUrl, referer = embedUrl).text
        val meta = JSONObject(response)
        if (meta.has("error")) return

        val qualities = meta.optJSONObject("qualities")
        val auto = qualities?.optJSONArray("auto")
        if (auto != null) {
            for (i in 0 until auto.length()) {
                val videoUrl = auto.optJSONObject(i)?.optString("url")
                if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) {
                    MasterLinkGenerator.createSmartLink(
                        this.name, videoUrl, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback
                    )
                }
            }
        }

        meta.optJSONObject("subtitles")?.optJSONObject("data")?.let { subs ->
            val keys = subs.keys()
            while (keys.hasNext()) {
                val subData = subs.optJSONObject(keys.next()) ?: continue
                val label = subData.optString("label")
                val subUrls = subData.optJSONArray("urls") ?: continue
                for (i in 0 until subUrls.length()) {
                    val subUrl = subUrls.optString(i)
                    if (subUrl.isNotBlank()) {
                        subtitleCallback(SubtitleFile(label, subUrl))
                    }
                }
            }
        }
    }
}