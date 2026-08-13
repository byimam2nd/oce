package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.getDecoder().decode(fixed + pad)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = URI(url).path.trimEnd('/').substringAfterLast('/')
            val base = URI(url).let { "${it.scheme}://${it.host}" }
            val details =
                app.get("$base/api/videos/$code/embed/details")
                    .parsedSafe<ByseDetailsRoot>() ?: return

            val embedFrameUrl = details.embedFrameUrl
            val embedBase =
                URI(embedFrameUrl).let { "${it.scheme}://${it.host}" }
            val embedCode =
                URI(embedFrameUrl).path.trimEnd('/')
                    .substringAfterLast('/')
            val headers = mapOf(
                "referer" to embedFrameUrl,
                "x-embed-parent" to url
            )

            val playback =
                app.get(
                    "$embedBase/api/videos/$embedCode/embed/playback",
                    headers = headers
                ).parsedSafe<BysePlaybackRoot>()?.playback ?: return

            val key =
                b64UrlDecode(playback.keyParts[0]) +
                    b64UrlDecode(playback.keyParts[1])
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, b64UrlDecode(playback.iv))
            )
            val decrypted =
                cipher.doFinal(b64UrlDecode(playback.payload))
            val jsonStr = String(decrypted, StandardCharsets.UTF_8)
                .let { if (it.startsWith("\uFEFF")) it
                    .substring(1) else it }

            val sourceUrls = tryParseJson<BysePlaybackDecrypt>(jsonStr)
                ?.sources?.map { it.url } ?: emptyList()
            CompiledRegexPatterns.prioritizeAdaptiveUrls(sourceUrls)
                .forEach {
                MasterLinkGenerator.createSmartLink(
                    name, it, mainUrl,
                    headers = mapOf("Referer" to base),
                    callback = callback
                )
            }
        } catch (e: Exception) {
            Log.d("ByseSX", "Extraction failed: ${e.message}")
        }
    }
}
