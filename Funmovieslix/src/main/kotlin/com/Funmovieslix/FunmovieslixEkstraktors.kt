package com.Funmovieslix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ============================================
// REGION 1: MASTER LINK GENERATOR
// ============================================

object MasterLinkGenerator {
    suspend fun createLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null
    ): ExtractorLink? {
        val detectedQuality = quality ?: detectQualityFromUrl(url)
        return newExtractorLink(
            source = source,
            name = source,
            url = url,
            type = INFER_TYPE
        ) {
            this.quality = detectedQuality
            if (referer != null) this.referer = referer
            this.headers = headers ?: emptyMap()
        }
    }

    fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("1080") -> 1080
            urlLower.contains("720") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 480
        }
    }
}

// ============================================
// REGION 2: LOAD EXTRACTOR WITH FALLBACK
// ============================================

suspend fun loadExtractorWithFallback(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var deliveredLinks = 0
    val trackedCallback: (ExtractorLink) -> Unit = { link ->
        deliveredLinks++
        callback(link)
    }

    try {
        if (loadExtractor(url, referer, subtitleCallback, trackedCallback)) return true
    } catch (_: Exception) {
    }

    val urlDomain = url
        .removePrefix("http://")
        .removePrefix("https://")
        .split("/")
        .first()
        .lowercase()
    val matchingExtractors = FunmovieslixEkstraktors.list.filter { extractor ->
        urlDomain
            .contains(
                extractor.mainUrl
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .split("/")
                    .first()
                    .lowercase()
            )
    }

    coroutineScope {
        val semaphore = Semaphore(3)
        matchingExtractors.forEach { extractor ->
            launch {
                semaphore.withPermit {
                    try {
                        extractor.getUrl(url, referer, subtitleCallback, trackedCallback)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    return deliveredLinks > 0
}

// ============================================
// REGION 3: EXTRACTOR CLASSES
// ============================================

class Ryderjet : StreamWishExtractor() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Dhtpre : StreamWishExtractor() {
    override var name = "Dhtpre"
    override var mainUrl = "https://dhtpre.com"
}

class VideyV2 : ExtractorApi() {
    override var name = "Videy"
    override var mainUrl = "https://videy.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        val videoUrl = "https://cdn.videy.co/$id.mp4"
        callback.invoke(newExtractorLink(this.name, this.name, videoUrl, INFER_TYPE))
    }
}

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return java.util.Base64
            .getDecoder()
            .decode(fixed + "=".repeat(pad))
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
            val details = app.get("$base/api/videos/$code/embed/details").parsedSafe<ByseDetailsRoot>() ?: return
            val embedFrameUrl = details.embedFrameUrl
            val embedBase = URI(embedFrameUrl).let { "${it.scheme}://${it.host}" }
            val embedCode = URI(embedFrameUrl).path.trimEnd('/').substringAfterLast('/')
            val headers = mapOf("referer" to embedFrameUrl, "x-embed-parent" to url)
            val playback =
                app.get("$embedBase/api/videos/$embedCode/embed/playback", headers = headers).parsedSafe<BysePlaybackRoot>()?.playback
                    ?: return

            val key = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher
                .init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64UrlDecode(playback.iv)))
            var jsonStr = String(cipher.doFinal(b64UrlDecode(playback.payload)), StandardCharsets.UTF_8)
            if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1)
            val streamUrl = tryParseJson<BysePlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url ?: return

            M3u8Helper.generateM3u8(name, streamUrl, mainUrl, headers = mapOf("Referer" to base)).forEach(callback)
        } catch (_: Exception) {
        }
    }

    data class ByseDetailsRoot(
        val id: Long,
        val code: String,
        val title: String,
        @JsonProperty("poster_url") val posterUrl: String,
        val description: String,
        @JsonProperty("embed_frame_url") val embedFrameUrl: String
    )

    data class BysePlaybackRoot(
        val playback: BysePlayback
    )

    data class BysePlayback(
        val algorithm: String,
        val iv: String,
        val payload: String,
        @JsonProperty("key_parts") val keyParts: List<String>
    )

    data class BysePlaybackDecrypt(
        val sources: List<BysePlaybackSource>
    )

    data class BysePlaybackSource(
        val quality: String,
        val label: String,
        val url: String
    )
}

class Bysezoxexe : ByseSX() {
    override var name = "Bysezoxexe"
    override var mainUrl = "https://bysezoxexe.com"
}

class Vidhideplus : VidhideExtractor() {
    override var name = "Vidhideplus"
    override var mainUrl = "https://vidhideplus.com"
}

class F75s : VidhideExtractor() {
    override var name = "F75s"
    override var mainUrl = "https://f75s.com"
}

// ============================================
// REGION 4: EXTRACTORS LIST
// ============================================

object FunmovieslixEkstraktors {
    val list = listOf(
        Ryderjet(),
        Dhtpre(),
        VideyV2(),
        Bysezoxexe(),
        Vidhideplus(),
        F75s()
    )
}
