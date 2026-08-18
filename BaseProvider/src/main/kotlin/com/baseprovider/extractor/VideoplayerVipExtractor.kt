package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * Videoplayer.vip — embed page berisi packed JS (Dean Edwards). Unpack →
 * `window.kaken` → GET api/?{kaken} → sources[].file (mp4) + tracks[].file
 * (subtitle). Decrypt pl/{id} TIDAK diperlukan untuk extractor.
 */
class VideoplayerVip : ExtractorApi() {
    override var name = "VideoplayerVip"
    override var mainUrl = "https://videoplayer.vip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (url.contains("/embed/")) url
            else url.replace("/e/", "/embed/")
        val html = app.get(embedUrl, referer = referer).text
        val packed = findPackedJsInPage(html) ?: return
        val unpacked = decodePackedJs(packed.first, packed.second, packed.third)
        val kaken = Regex("""window\.kaken="([^"]+)""").find(unpacked)
            ?.groupValues?.get(1) ?: return

        val apiText = app.get("$mainUrl/api/?$kaken",
            referer = embedUrl).text
        val json = runCatching { JSONObject(apiText) }.getOrNull() ?: return
        if (json.optString("status") != "ok") return

        json.optJSONArray("sources")?.let { sources ->
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val file = src.optString("file")
                if (file.isBlank()) continue
                val label = src.optString("label")
                val quality = MasterLinkGenerator.getQualityFromName(label)
                MasterLinkGenerator.createSmartLink(
                    this.name, file, referer,
                    quality = quality,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true,
                    callback = callback
                )
            }
        }

        json.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val subUrl = track.optString("file")
                if (subUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile(track.optString("label"), subUrl))
                }
            }
        }
    }
}