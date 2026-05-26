package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import org.json.JSONObject

open class Hownetwork : ExtractorApi() {
    override var name = "Hownetwork"; override var mainUrl = "https://stream.hownetwork.xyz"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try { val id = url.substringAfter("id="); val response = app.post("$mainUrl/api2.php?id=$id", data = mapOf("r" to "", "d" to mainUrl), referer = url, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
            JSONObject(response).optString("file").let { MasterLinkGenerator.createSmartLink(this.name, it, it, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"), callback = callback) } } catch (_: Exception) {}
    }
}

class Cloudhownetwork : Hownetwork() { override var mainUrl = "https://cloud.hownetwork.xyz" }
