package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log

import com.fasterxml.jackson.annotation.JsonProperty

open class Odnoklassniki : ExtractorApi() {
    override var name = "OkRu"; override var mainUrl = "https://ok.ru"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to DEFAULT_UA
        )
        val videoHeaders = MasterLinkGenerator.minimalVideoHeaders
        val embedUrl = url.replace("/video/", "/videoembed/")
        val videoReq = app.get(embedUrl, headers = headers).text.replace("\\&quot;", "\"")
            .replace("\\\\", "\\")

        val hlsUrl = Regex(""""hlsManifestUrl":\s*"([^"]+)"""")
            .find(videoReq)?.groupValues?.getOrNull(1)
            ?.let { MasterLinkGenerator.decodeUnicodeEscapes(it) }
        if (!hlsUrl.isNullOrBlank()) {
            Log.d("OkRu", "Using adaptive HLS: ${hlsUrl.take(90)}...")
            // Kirim master HLS penuh (perilaku stabil). Headers dipilih otomatis
            // per-host oleh AdaptiveHeaderProbe (bare vs referer).
            MasterLinkGenerator.createSmartLink(
                this.name, hlsUrl, null,
                headers = videoHeaders, bareHeaders = true,
                callback = callback
            )
            return
        }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)
            ?.groupValues?.get(1) ?: return
        tryParseJson<List<OkRuVideo>>(videosStr)?.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            MasterLinkGenerator.createSmartLink(
                this.name,
                videoUrl,
                null,
                MasterLinkGenerator.getQualityFromName(video.name),
                headers = videoHeaders,
                bareHeaders = true,
                callback = callback
            )
        }
    }
    data class OkRuVideo(@JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String)
}
