package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Voe : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val resp = app.get(url, referer = referer)
        val text = resp.text
        for (src in listOf(
            Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(text)?.value,
            Regex("""file:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1),
        )) {
            if (src != null) { MasterLinkGenerator.createSmartLink(this.name, src, url, callback = callback); return }
        }
    }
}
