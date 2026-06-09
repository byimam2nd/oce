package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class BloggerVideo : ExtractorApi() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        doc.select("video source[src], video[src], iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains("youtube"))) {
                loadExtractorWithFallbackCustom(
                    src, url, subtitleCallback,
                    callback = callback,
                    providerTag = name,
                    callChain = "BloggerVideo"
                )
            }
        }
    }
}
