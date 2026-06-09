package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class ByseSX : ExtractorApi() {
    override var name = "Byse"; override var mainUrl = "https://byse.sx"; override val requiresReferer = true
    private fun b64UrlDecode(s: String): ByteArray { val fixed = s.replace('-', '+').replace('_', '/'); return Base64.getDecoder().decode(fixed + "=".repeat((4 - fixed.length % 4) % 4)) }
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try { val code = URI(url).path.trimEnd('/').substringAfterLast('/'); val base = URI(url).let { "${it.scheme}://${it.host}" }; val details = app.get("$base/api/videos/$code/embed/details").parsedSafe<ByseDetailsRoot>() ?: return
            val embedFrameUrl = details.embedFrameUrl; val embedBase = URI(embedFrameUrl).let { "${it.scheme}://${it.host}" }; val embedCode = URI(embedFrameUrl).path.trimEnd('/').substringAfterLast('/'); val headers = mapOf("referer" to embedFrameUrl, "x-embed-parent" to url)
            val playback = app.get("$embedBase/api/videos/$embedCode/embed/playback", headers = headers).parsedSafe<BysePlaybackRoot>()?.playback ?: return
            val key = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1]); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64UrlDecode(playback.iv)))
            val jsonStr = String(cipher.doFinal(b64UrlDecode(playback.payload)), StandardCharsets.UTF_8).let { if (it.startsWith("\uFEFF")) it.substring(1) else it }; tryParseJson<BysePlaybackDecrypt>(jsonStr)?.sources?.forEach { MasterLinkGenerator.createSmartLink(name, it.url, mainUrl, headers = mapOf("Referer" to base), callback = callback) } } catch (e: Exception) { Log.d("ByseSX", "Extraction failed: ${e.message}") }
    }
    data class ByseDetailsRoot(val id: Long, val code: String, val title: String, @JsonProperty("poster_url") val posterUrl: String, val description: String, @JsonProperty("embed_frame_url") val embedFrameUrl: String)
    data class BysePlaybackRoot(val playback: BysePlayback); data class BysePlayback(val algorithm: String, val iv: String, val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
    data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>); data class BysePlaybackSource(val quality: String, val label: String, val url: String)
}
