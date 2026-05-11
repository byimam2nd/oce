package com.Idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val matchingExtractors = IdlixEkstraktors.list.filter { extractor ->
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

class Jeniusplay : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hash = url.split("/").last().substringAfter("data=")
            val m3uLink = app
                .post(
                    url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                    data = mapOf("hash" to hash, "r" to "$referer"),
                    referer = referer,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseSource>()
                .videoSource

            callback.invoke(newExtractorLink(name, name, url = m3uLink, ExtractorLinkType.M3U8))
        } catch (_: Exception) {
        }
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String
    )
}

// ============================================
// REGION 4: EXTRACTORS LIST
// ============================================

object IdlixEkstraktors {
    val list = listOf(
        Jeniusplay()
    )
}
