package com.Animasu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
    val matchingExtractors = AnimasuEkstraktors.list.filter { extractor ->
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

class Archivd : ExtractorApi() {
    override val name: String = "Archivd"
    override val mainUrl: String = "https://archivd.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url).document
            val json = res.select("div#app").attr("data-page")
            val video = tryParseJson<Sources>(json)
                ?.props
                ?.datas
                ?.data
                ?.link
                ?.media ?: return
            callback.invoke(newExtractorLink(this.name, this.name, video, INFER_TYPE) { this.referer = "$mainUrl/" })
        } catch (_: Exception) {
        }
    }

    data class Link(
        @JsonProperty("media") val media: String? = null
    )

    data class Data(
        @JsonProperty("link") val link: Link? = null
    )

    data class Datas(
        @JsonProperty("data") val data: Data? = null
    )

    data class Props(
        @JsonProperty("datas") val datas: Datas? = null
    )

    data class Sources(
        @JsonProperty("props") val props: Props? = null
    )
}

class Newuservideo : ExtractorApi() {
    override val name: String = "Uservideo"
    override val mainUrl: String = "https://new.uservideo.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframe = app
                .get(url, referer = referer)
                .document
                .select("iframe#videoFrame")
                .attr("src")
            val doc = app.get(iframe, referer = "$mainUrl/").text
            val json = "VIDEO_CONFIG\\s?=\\s?(.*)".toRegex().find(doc)?.groupValues?.get(1)

            tryParseJson<Sources>(json)?.streams?.map {
                callback.invoke(
                    newExtractorLink(this.name, this.name, it.playUrl ?: return@map, INFER_TYPE) {
                        this.referer = "$mainUrl/"
                        this.quality = when (it.formatId) {
                            18 -> 360
                            22 -> 720
                            else -> 0
                        }
                    }
                )
            }
        } catch (_: Exception) {
        }
    }

    data class Streams(
        @JsonProperty("play_url") val playUrl: String? = null,
        @JsonProperty("format_id") val formatId: Int? = null
    )

    data class Sources(
        @JsonProperty("streams") val streams: ArrayList<Streams>? = null
    )
}

// Vidhidepro extends Filesim, assuming Filesim is in library.
// If it fails, I'll need to find the implementation of Filesim.
class Vidhidepro : Filesim() {
    override val mainUrl = "https://vidhidepro.com"
    override val name = "Vidhidepro"
}

// ============================================
// REGION 4: EXTRACTORS LIST
// ============================================

object AnimasuEkstraktors {
    val list = listOf(
        Archivd(),
        Newuservideo(),
        Vidhidepro()
    )
}
