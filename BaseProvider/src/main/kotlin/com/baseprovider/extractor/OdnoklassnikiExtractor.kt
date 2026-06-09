package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import com.fasterxml.jackson.annotation.JsonProperty

open class Odnoklassniki : ExtractorApi() {
    override var name = "OkRu"; override var mainUrl = "https://odnoklassniki.ru"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val videoReq = app.get(embedUrl).text.replace("\\&quot;", "\"").replace("\\\\", "\\")
        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1) ?: return
        tryParseJson<List<OkRuVideo>>(videosStr)?.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            MasterLinkGenerator.createSmartLink(
                this.name,
                videoUrl,
                "$mainUrl/",
                MasterLinkGenerator.getQualityFromName(video.name),
                callback = callback
            )
        }
    }
    data class OkRuVideo(@JsonProperty("name") val name: String, @JsonProperty("url") val url: String)
}
