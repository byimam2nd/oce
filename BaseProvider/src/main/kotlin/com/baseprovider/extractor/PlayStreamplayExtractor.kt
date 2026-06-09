package com.baseprovider.extractor
import com.baseprovider.model.fixUrlSmart
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup


class PlayStreamplay : ExtractorApi() {
    override var name = "PlayStreamplay"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer).text
        val doc = Jsoup.parse(html)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("ads") && !src.contains("ads?")) {
                loadExtractorWithFallbackCustom(
                    fixUrlSmart(src, url), url, subtitleCallback,
                    callback = callback,
                    providerTag = name,
                    callChain = "PlayStreamplay"
                )
            }
        }
        var urls = CompiledRegexPatterns.extractAllVideoUrls(html)
        if (urls.isEmpty()) {
            val decoded = findPackedJsInPage(html)?.let { (p, k, b) -> decodePackedJs(p, k, b) } ?: html
            urls = CompiledRegexPatterns.extractAllVideoUrls(decoded)
        }
        if (urls.isNotEmpty()) {
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                MasterLinkGenerator.createSmartLink(this.name, it, mainUrl, callback = callback)
            }
        }
    }
}
