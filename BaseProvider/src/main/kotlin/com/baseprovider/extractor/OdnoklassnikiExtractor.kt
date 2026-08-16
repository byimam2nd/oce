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

        // Jalur utama: videos array -> MP4 progressive (pola CS3 Odnoklassniki).
        // CS3 meng-emit link ini dengan referer "$mainUrl/" + headers ber-Origin;
        // CDN OkRu memvalidasi srcIp & Origin, jadi TIDAK boleh bare (error 2004).
        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)
            ?.groupValues?.get(1)
        val videos = videosStr?.let {
            tryParseJson<List<OkRuVideo>>(it)
        }.orEmpty()
        if (videos.isNotEmpty()) {
            Log.d("OkRu", "Using MP4 progressive: ${videos.size} quality links")
            videos.forEach { video ->
                val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
                MasterLinkGenerator.createSmartLink(
                    this.name,
                    videoUrl,
                    "$mainUrl/",
                    MasterLinkGenerator.getQualityFromName(video.name),
                    headers = headers,
                    callback = callback
                )
            }
        }

        // Jalur tambahan: hlsManifestUrl adaptive (m3u8). Fallback kualitas jika
        // MP4 tersedia; sumber utama jika videos array kosong. Tetap kirim
        // referer "$mainUrl/" + headers ber-Origin (tidak bare).
        val hlsUrl = Regex(""""hlsManifestUrl":\s*"([^"]+)"""")
            .find(videoReq)?.groupValues?.getOrNull(1)
            ?.let { MasterLinkGenerator.decodeUnicodeEscapes(it) }
        if (!hlsUrl.isNullOrBlank()) {
            Log.d("OkRu", "Using adaptive HLS: ${hlsUrl.take(90)}...")
            // Adaptive quality picker: pilih SATU varian stabil sesuai kecepatan
            // CDN OkRu (throttle fluktuatif bikin ABR penuh sering buffering).
            // Gagal -> fallback ke master penuh. Additive.
            val pickedUrl = AdaptiveQualityPicker.selectBestVariant(hlsUrl, videoHeaders)
            if (pickedUrl != null) {
                Log.d("OkRu", "Adaptive picker selected stable variant: ${pickedUrl.take(90)}...")
                callback(newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = pickedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = headers
                })
            } else {
                MasterLinkGenerator.createSmartLink(
                    this.name, hlsUrl, "$mainUrl/",
                    headers = headers,
                    callback = callback
                )
            }
        }
    }
    data class OkRuVideo(@JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String)
}
