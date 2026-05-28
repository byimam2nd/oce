package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


object MasterLinkGenerator {

    suspend fun createSmartLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val isAdaptive = url.contains(".m3u8") || url.contains(".mpd")
        val safeHeaders = headers ?: emptyMap()

        val cleanName = if (isAdaptive) source.replace(QUALITY_STRIP_REGEX, "").trim() else source
        callback(newExtractorLink(
            source = source,
            name = cleanName,
            url = url,
            type = if (url.contains(".mpd")) ExtractorLinkType.DASH else if (isAdaptive) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.quality = if (isAdaptive) -1 else (quality ?: detectQualityFromUrl(url))
            this.referer = referer ?: ""
            this.headers = safeHeaders
        })
    }

    fun refineAndDeliver(links: List<ExtractorLink>, finalCallback: (ExtractorLink) -> Unit) {
        val seenM3u8Sources = mutableSetOf<String>()
        links.forEach { link ->
            val isM3u8 = link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.DASH
            if (isM3u8) {
                if (seenM3u8Sources.add(link.source)) {
                    val refinedName = link.source.replace(QUALITY_STRIP_REGEX, "").trim()
                    finalCallback(ExtractorLink(source = link.source, name = refinedName, url = link.url, referer = link.referer, quality = -1, type = link.type, headers = link.headers, extractorData = null))
                }
            } else {
                val qualityLabel = if (link.quality > 0) "${link.quality}p" else ""
                val cleanSource = link.source.replace(QUALITY_STRIP_REGEX, "").trim()
                finalCallback(ExtractorLink(source = link.source, name = "$cleanSource $qualityLabel".trim(), url = link.url, referer = link.referer, quality = link.quality, type = link.type, headers = link.headers, extractorData = null))
            }
        }
    }

    private fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            CompiledRegexPatterns.MLG_QUALITY_1080.containsMatchIn(urlLower) -> 1080
            CompiledRegexPatterns.MLG_QUALITY_720.containsMatchIn(urlLower) -> 720
            CompiledRegexPatterns.MLG_QUALITY_480.containsMatchIn(urlLower) -> 480
            CompiledRegexPatterns.MLG_QUALITY_360.containsMatchIn(urlLower) -> 360
            else -> 480
        }
    }

    fun getQualityFromName(name: String?): Int {
        if (name == null) return 480
        val n = name.lowercase()
        return when {
            n.contains("1080") || n.contains("fhd") -> 1080
            n.contains("720") || n.contains("hd") -> 720
            n.contains("480") || n.contains("sd") -> 480
            else -> 360
        }
    }
}
