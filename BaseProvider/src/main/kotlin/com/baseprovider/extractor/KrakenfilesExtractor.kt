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
        val response = app.get("$mainUrl/embed-video/$id")
        if (response.code == 404) {
            // File dihapus dari krakenfiles: halaman balas 404 tanpa <source>.
            // Provider asal (samehadaku/animasu) masih menautkan URL mati, jadi
            // ini kondisi normal, bukan kegagalan extractor yang bisa diperbaiki.
            com.baseprovider.log.logFail(
                this.name, "File krakenfiles tidak ada (HTTP 404)",
                url = url, method = "getUrl",
                type = com.baseprovider.log.FailureType.CONTENT_REMOVED,
                stage = "EXTRACT", extractor = this.name
            )
            return
        }
        val raw = response.document.selectFirst("source")?.attr("src") ?: return
        val link = if (raw.startsWith("//")) "https:$raw" else raw
        MasterLinkGenerator.createSmartLink(
            this.name, link, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true,
            callback = callback
        )
    }
}
