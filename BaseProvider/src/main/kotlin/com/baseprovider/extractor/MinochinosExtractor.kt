package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.Jsoup


class Minochinos : ExtractorApi() {
    override var name = "Minochinos";
    override var mainUrl = "https://minochinos.com";
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val text = app.get(url, referer = referer).text
        val packed = findPackedJsInPage(text)
        val script = if (packed != null) decodePackedJs(packed.first, packed.second, packed.third) else text
        var found = false
        CompiledRegexPatterns.extractAllVideoUrls(script).let { urls ->
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach { found = true; MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
        }
        if (!found) {
            val docScripts = try { Jsoup.parse(text).selectFirst("script:containsData(sources:)")?.data() } catch (e: Exception) { Log.d("Minochinos", "Script fetch failed: ${e.message}"); null }
            if (docScripts != null) {
                CompiledRegexPatterns.extractAllVideoUrls(docScripts).let { urls ->
                    CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
                }
            }
        }
    }
}
