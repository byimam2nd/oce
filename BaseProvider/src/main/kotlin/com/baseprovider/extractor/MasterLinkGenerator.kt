package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

object MasterLinkGenerator {

    private val DEFAULT_QUALITY_STRIP = Regex("""\d{3,4}p|HD|SD|FHD""",
        RegexOption.IGNORE_CASE)

    private val BROWSER_LIKE_HEADERS = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to DEFAULT_UA
    )

    fun decodeUnicodeEscapes(input: String): String {
        if (!input.contains("\\u")) return input
        return Regex("""\\u([0-9A-Fa-f]{4})""").replace(input) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
    }

    val minimalVideoHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to DEFAULT_UA
    )

    private fun enrichHeaders(
        headers: Map<String, String>?,
        bareHeaders: Boolean
    ): Map<String, String> {
        val provided = headers ?: emptyMap()
        if (bareHeaders) {
            return if (provided.isEmpty()) minimalVideoHeaders else provided
        }
        if (provided.isEmpty()) return minimalVideoHeaders
        val merged = HashMap(BROWSER_LIKE_HEADERS)
        merged.putAll(provided)
        return merged
    }

    suspend fun createSmartLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP,
        bareHeaders: Boolean = false,
        callback: (ExtractorLink) -> Unit
    ) {
        val isAdaptive = url.contains(".m3u8") || url.contains(".mpd")
        val safeHeaders = enrichHeaders(headers, bareHeaders)

        // Adaptive headers: untuk link bare, probe otomatis (valid + tercepat)
        // per-host, hasil di-cache. Menghindari test manual per extractor
        // (kasus OkRu dulu): uji beberapa combo header (bare/referer/origin/
        // browser-like) paralel, pilih yang valid 2xx/3xx dan tercepat.
        var effectiveReferer = referer
        var effectiveHeaders = safeHeaders
        if (bareHeaders) {
            // Probe otomatis (valid pertama yang selesai menang, sisanya di-cancel).
            // Headers asli extractor ikut diuji sebagai combo EXPLICIT.
            val decision = AdaptiveHeaderProbe.resolve(url, referer, headers)
            if (!decision.valid) {
                // Link gagal test (non-2xx/3xx) di semua combo header.
                // Jangan kirim link rusak ke player (avoid error 2004).
                return
            }
            effectiveReferer = decision.referer
            effectiveHeaders = decision.headers
        }

        val cleanName = source.replace(qualityStripRegex, "").trim()
        callback(newExtractorLink(
            source = source,
            name = cleanName,
            url = url,
            type = if (url.contains(".mpd")) ExtractorLinkType.DASH
                else if (isAdaptive) ExtractorLinkType.M3U8
                else ExtractorLinkType.VIDEO
        ) {
            if (!isAdaptive) this.quality = quality ?: detectQualityFromUrl(url)
            this.referer = effectiveReferer ?: ""
            this.headers = effectiveHeaders
        })
    }

    @Suppress("DEPRECATION_ERROR")
    fun refineAndDeliver(
        links: List<ExtractorLink>,
        finalCallback: (ExtractorLink) -> Unit,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP
    ) {
        val seenM3u8Sources = mutableSetOf<String>()
        links.forEach { link ->
            val isM3u8 = link.type == ExtractorLinkType.M3U8 || link.type ==
                ExtractorLinkType.DASH
            if (isM3u8) {
                if (seenM3u8Sources.add(link.source)) {
                    val refinedName = link.name.ifBlank {
                        link.source.replace(qualityStripRegex, "").trim()
                    }
                    finalCallback(ExtractorLink(
                        source = link.source,
                        name = refinedName,
                        url = link.url,
                        referer = link.referer,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    ))
                }
            } else {
                val cleanSource = link.name.ifBlank {
                    link.source.replace(qualityStripRegex, "").trim()
                }
                finalCallback(ExtractorLink(
                    source = link.source,
                    name = cleanSource,
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality,
                    type = link.type,
                    headers = link.headers,
                    extractorData = link.extractorData
                ))
            }
        }
    }

    private fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            CompiledRegexPatterns.MLG_QUALITY_1080
                .containsMatchIn(urlLower) -> 1080
            CompiledRegexPatterns.MLG_QUALITY_720
                .containsMatchIn(urlLower) -> 720
            CompiledRegexPatterns.MLG_QUALITY_480
                .containsMatchIn(urlLower) -> 480
            CompiledRegexPatterns.MLG_QUALITY_360
                .containsMatchIn(urlLower) -> 360
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
