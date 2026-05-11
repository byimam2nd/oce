package com.Donghuastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

// ============================================
// REGION 1: COMPILED REGEX PATTERNS
// ============================================

object CompiledRegexPatterns {
    val ARCHIVE_ORG_URL = Regex("""\"url\":\"(.*?)\"""")
    val DAILYMOTION_VIDEO_URL = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
    val RUMBLE_URL_PATTERN = Regex("""\"url\":\"(.*?)\"|h\":(.*?)\}""")
}

// ============================================
// REGION 2: MASTER LINK GENERATOR
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
// REGION 3: LOAD EXTRACTOR WITH FALLBACK
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
    val matchingExtractors = DonghuastreamEkstraktors.list.filter { extractor ->
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
// REGION 4: EXTRACTOR CLASSES
// ============================================

class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url).document
            val sources = response.select("script").find { it.data().contains("\"sources\"") }?.data() ?: return
            CompiledRegexPatterns.ARCHIVE_ORG_URL.findAll(sources).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    callback
                        .invoke(
                            newExtractorLink(name, name, videoUrl, if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                }
            }
        } catch (_: Exception) {
        }
    }
}

class Dailymotion : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id =
                URI(url)
                    .path
                    .substringAfter("/video/")
                    .substringBefore("?")
                    .takeIf { it.matches("^[kx][a-zA-Z0-9]+$".toRegex()) }
                    ?: return
            val response = app.get("https://www.dailymotion.com/player/metadata/video/$id", referer = url).text
            CompiledRegexPatterns.DAILYMOTION_VIDEO_URL.findAll(response).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(this.name, videoUrl, url).forEach(callback)
                }
            }
        } catch (_: Exception) {
        }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer ?: "$mainUrl/")
            val scriptData = response.document
                .selectFirst("script:containsData(mp4)")
                ?.data()
                ?.substringAfter("{\"mp4")
                ?.substringBefore("\"evt\":{") ?: return
            CompiledRegexPatterns.RUMBLE_URL_PATTERN.findAll(scriptData).forEach { match ->
                val cleanedUrl = match.groupValues[1].replace("\\/", "/")
                if (cleanedUrl.contains("rumble.com") && cleanedUrl.endsWith(".m3u8")) {
                    callback.invoke(newExtractorLink(this.name, "Rumble", cleanedUrl, ExtractorLinkType.M3U8))
                    return@forEach
                }
            }
        } catch (_: Exception) {
        }
    }
}

class PlayStreamplay : ExtractorApi() {
    override var name = "PlayStreamplay"
    override var mainUrl = "https://playstreamplay.com"
    override val requiresReferer = true
}

class Ultrahd : ExtractorApi() {
    override var name = "Ultrahd"
    override var mainUrl = "https://ultrahd.to"
    override val requiresReferer = true
}

class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.com"
    override val requiresReferer = true
}

class wishfast : ExtractorApi() {
    override var name = "wishfast"
    override var mainUrl = "https://wishfast.to"
    override val requiresReferer = true
}

// ============================================
// REGION 5: EXTRACTORS LIST
// ============================================

object DonghuastreamEkstraktors {
    val list = listOf(
        ArchiveOrgExtractor(),
        Dailymotion(),
        Rumble(),
        PlayStreamplay(),
        Ultrahd(),
        Vtbe(),
        wishfast()
    )
}
