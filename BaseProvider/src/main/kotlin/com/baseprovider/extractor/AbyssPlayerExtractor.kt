package com.baseprovider

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )
        val doc = app.get(url, headers = headers).document
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val response = app.post("https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".trimIndent().toRequestBody("application/json".toMediaType())
        ).text
        val json = JSONObject(response).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val src = sources.getJSONObject(i)
            if (src.optBoolean("status", false)) {
                MasterLinkGenerator.createSmartLink(this.name, src.getString("url"), "https://playhydrax.com", callback = callback)
            }
        }
    }
}
