package com.baseprovider.extractor
import com.baseprovider.cache.AdaptiveDecryptCache
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

import org.json.JSONObject

open class Hownetwork : ExtractorApi() {
    override var name = "Hownetwork"
    override var mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    private val decryptCache = AdaptiveDecryptCache()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("id=")
            val responseText = decryptCache.get(url) ?: run {
                val resp = app.post(
                    "$mainUrl/api2.php?id=$id",
                    data = mapOf("r" to "", "d" to mainUrl),
                    referer = url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
                decryptCache.put(url, resp)
                resp
            }
            JSONObject(responseText).optString("file").let {
                MasterLinkGenerator.createSmartLink(
                    this.name, it, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true,
                    callback = callback
                )
            }
        } catch (e: Exception) {
            Log.d("Hownetwork", "Extraction failed: ${e.message}")
        }
    }
}
