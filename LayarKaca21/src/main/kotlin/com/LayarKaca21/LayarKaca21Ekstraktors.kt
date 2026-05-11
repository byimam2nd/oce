package com.LayarKaca21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

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
    val matchingExtractors = LayarKaca21Ekstraktors.list.filter { extractor ->
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

open class Hownetwork : ExtractorApi() {
    override val name = "Hownetwork"
    override val mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("id=")
            val response = app
                .post(
                    "$mainUrl/api2.php?id=$id", data = mapOf("r" to "", "d" to mainUrl), referer = url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
            val json = JSONObject(response)
            val file = json.optString("file")
            callback.invoke(
                newExtractorLink(this.name, this.name, file, type = INFER_TYPE) {
                    this.referer = file
                    this.headers =
                        mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                }
            )
        } catch (_: Exception) {
        }
    }
}

class Cloudhownetwork : Hownetwork() {
    override val mainUrl = "https://cloud.hownetwork.xyz"
}

open class EmturbovidExtractorM3U8 : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val ref = referer ?: "$mainUrl/"
            val headers = mapOf(
                "Referer" to "$mainUrl/", "Origin" to mainUrl,
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            val page = app.get(url, referer = ref)
            val playerScript = page.document.selectXpath("//script[contains(text(),'var urlPlay')]").html()
            if (playerScript.isBlank()) return

            var masterUrl = playerScript.substringAfter("var urlPlay = '").substringBefore("'").trim()
            if (masterUrl.startsWith("//")) masterUrl = "https:$masterUrl"
            if (masterUrl.startsWith("/")) masterUrl = mainUrl + masterUrl

            val masterText = app.get(masterUrl, headers = headers).text
            val lines = masterText.lines()
            for (i in 0 until lines.size) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val qualityLine = lines.getOrNull(i + 1)?.trim() ?: continue
                if (!qualityLine.startsWith("http")) continue
                val resolution = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)
                val quality = resolution?.toIntOrNull() ?: 480
                callback.invoke(
                    newExtractorLink(this.name, this.name, qualityLine, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        this.headers = headers
                    }
                )
            }
        } catch (_: Exception) {
        }
    }
}

class EmturbovidExtractor : EmturbovidExtractorM3U8() {
    override var mainUrl = "https://turbovidhls.com"
}

class Co4nxtrl : Filesim() {
    override val mainUrl = "https://co4nxtrl.com"
    override val name = "Co4nxtrl"
    override val requiresReferer = true
}

class Furher : Filesim() {
    override val mainUrl = "https://furher.xyz"
    override val name = "Furher"
    override val requiresReferer = true
}

class Furher2 : Filesim() {
    override val mainUrl = "https://furher.com"
    override val name = "Furher2"
    override val requiresReferer = true
}

class Turbovidhls : Filesim() {
    override val mainUrl = "https://turbovidhls.com"
    override val name = "Turbovidhls"
    override val requiresReferer = true
}

class VidHidePro6 : Filesim() {
    override val mainUrl = "https://vidhidepro.com"
    override val name = "VidHidePro6"
    override val requiresReferer = true
}

// ============================================
// REGION 4: EXTRACTORS LIST
// ============================================

object LayarKaca21Ekstraktors {
    val list = listOf(
        Hownetwork(),
        Cloudhownetwork(),
        EmturbovidExtractorM3U8(),
        EmturbovidExtractor(),
        Co4nxtrl(),
        Furher(),
        Furher2(),
        Turbovidhls(),
        VidHidePro6()
    )
}
