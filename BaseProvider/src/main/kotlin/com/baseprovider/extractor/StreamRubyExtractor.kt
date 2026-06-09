package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


private val STREAMRUBY_EMBED_REGEX = Regex("embed-([a-zA-Z0-9]+)\\.html")
private val STREAMRUBY_FILE_REGEX = Regex("""file\s*:\s*"([^"]+)""")

open class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"; override var mainUrl = "https://rubyvidhub.com"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = STREAMRUBY_EMBED_REGEX.find(url)?.groupValues?.get(1) ?: return
        val response = app.post("$mainUrl/dl", data = mapOf("op" to "embed", "file_code" to id, "auto" to "1"), referer = referer)
        var urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        if (urls.isEmpty()) {
            val decoded = findPackedJsInPage(response.text)?.let { (p, k, b) -> decodePackedJs(p, k, b) } ?: response.text
            val fileMatch = STREAMRUBY_FILE_REGEX.find(decoded)
            if (fileMatch != null) {
                val fileUrl = fileMatch.groupValues[1]
                if (fileUrl.startsWith("http")) {
                    MasterLinkGenerator.createSmartLink(this.name, fileUrl, mainUrl, callback = callback)
                    return
                }
            }
            urls = CompiledRegexPatterns.extractAllVideoUrls(decoded)
        }
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
            MasterLinkGenerator.createSmartLink(this.name, it, mainUrl, callback = callback)
        }
    }
}

