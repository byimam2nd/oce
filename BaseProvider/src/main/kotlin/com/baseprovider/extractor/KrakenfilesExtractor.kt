package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

/**
 * Krakenfiles fallback legacy — dipakai bila config-driven gagal load.
 * URL: embed-video/{id} → <video><source src="..."> (file mp4/mkv langsung).
 */
open class Krakenfiles : ExtractorApi() {
    override var name = "Krakenfiles"
    override var mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:view|embed-video)/([0-9a-zA-Z]+)")
            .find(url)?.groupValues?.get(1) ?: return
        val doc = app.get("$mainUrl/embed-video/$id").document
        val raw = doc.selectFirst("source")?.attr("src") ?: return
        val link = if (raw.startsWith("//")) "https:$raw" else raw
        MasterLinkGenerator.createSmartLink(
            this.name, link, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true,
            callback = callback
        )
    }
}
