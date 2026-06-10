package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

private val VOE_M3U8_REGEX = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""")
private val VOE_FILE_REGEX = Regex("""file:\s*"([^"]+)"""")


class Voe : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        val resp = app.get(url, referer = referer)
        val text = resp.text
        for (src in listOf(
            VOE_M3U8_REGEX.find(text)?.value,
            VOE_FILE_REGEX.find(text)?.groupValues?.getOrNull(1),
        )) {
            if (src != null) { MasterLinkGenerator.createSmartLink(this
                .name, src, url, callback = callback); return }
        }
    }
}
