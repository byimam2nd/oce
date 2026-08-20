package com.baseprovider.extractor
import com.baseprovider.model.fixUrlSmart
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

class AnichinPlayer : ExtractorApi() {
    override var name = "AnichinPlayer"
    override var mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit) {
        // Server mengembalikan 403 jika referer = halaman sumber (mis. anichin.cafe).
        // Pakai referer = domain sendiri agar halaman player terbuka.
        val response = app.get(url, referer = "https://anichin-player.web.id/")
        response.document.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                val resolved = fixUrlSmart(src, "$mainUrl/")
                if (resolved.startsWith("http")) {
                    loadExtractorWithFallbackCustom(
                        resolved, url, subtitleCallback,
                        callback = callback,
                        providerTag = name,
                        callChain = name
                    )
                }
            }
        }
    }
}