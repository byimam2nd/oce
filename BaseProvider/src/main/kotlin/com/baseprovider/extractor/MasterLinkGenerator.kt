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

    /**
     * URL non-media/tracking yang terdeteksi pernah lolos sebagai "link"
     * (kasus Sacrifice: ping.gif JWPlayer & blank.mp4 plyr). Ditolak di pintu.
     */
    val JUNK_URL_REGEX = Regex(
        "(?i)(jwpltx\\.com|plyr\\.io/static|google-analytics|googletagmanager|" +
        "doubleclick|/ping[._]|\\.gif(\\?|\$)|/static/blank\\.)"
    )

    suspend fun createSmartLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP,
        bareHeaders: Boolean = false,
        providerTag: String = "ExtractorEngine",
        runId: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val __t0 = System.currentTimeMillis()
        if (url.isBlank() || JUNK_URL_REGEX.containsMatchIn(url)) {
            // Link kosong ATAU non-media/tracking: jangan sampai ke player.
            com.baseprovider.log.logFail(
                providerTag,
                "createSmartLink rejected blank url for $source",
                url = url,
                method = "createSmartLink",
                type = com.baseprovider.log.FailureType.INVALID_URL,
                stage = "VERIFY",
                extractor = source,
                runId = runId
            )
            return
        }
        val isAdaptive = url.contains(".m3u8") || url.contains(".mpd")
        val safeHeaders = enrichHeaders(headers, bareHeaders)

        // Adaptive headers: untuk link bare, probe otomatis (valid + tercepat)
        // per-host, hasil di-cache. Menghindari test manual per extractor
        // (kasus OkRu dulu): uji beberapa combo header (bare/referer/origin/
        // browser-like) paralel, pilih yang valid 2xx/3xx dan tercepat.
        var effectiveReferer = referer
        var effectiveHeaders = safeHeaders
        var probeBody: String? = null
        var probeBodyTruncated = false
        if (bareHeaders) {
            // Probe otomatis (valid pertama yang selesai menang, sisanya di-cancel).
            // Headers asli extractor ikut diuji sebagai combo EXPLICIT.
            // captureBody hanya untuk master m3u8: body pemenang dipakai
            // verifikasi variant di pass yang sama (P1, hindari fetch 2x).
            val decision = AdaptiveHeaderProbe.resolve(url, referer, headers,
                captureBody = isAdaptive && url.contains(".m3u8"))
            if (!decision.valid) {
                // Link gagal test (non-2xx/3xx) di semua combo header.
                // Jangan kirim link rusak ke player (avoid error 2004).
                com.baseprovider.log.logFail(
                    providerTag,
                    "AdaptiveHeaderProbe rejected link (non-2xx/3xx on all combos): $url",
                    url = url,
                    method = "createSmartLink",
                    type = com.baseprovider.log.FailureType.HTTP_FAILURE,
                    stage = "PROBE",
                    extractor = source,
                    runId = runId
                )
                return
            }
            effectiveReferer = decision.referer
            effectiveHeaders = decision.headers
            probeBody = decision.capturedBody
            probeBodyTruncated = decision.bodyTruncated
        }

        val cleanName = source.replace(qualityStripRegex, "").trim()

        // Verifikasi master m3u8: buang variant tanpa URI (mis. baris kosong
        // setelah #EXT-X-STREAM-INF) yang bikin ExoPlayer error 3002
        // (PARSING_MANIFEST_MALFORMED). Master bersih tetap dikirim as-is
        // (ABR jalan); hanya saat ada variant rusak variant valid dikirim
        // terpisah dan yang rusak TIDAK pernah sampai ke player.
        if (isAdaptive && url.contains(".m3u8")) {
            // Verifikasi master: pakai body hasil probe pemenang bila tersedia
            // (P1, satu fetch), fallback fetch penuh bila body null/truncated
            // (waiter single-flight, master >1MB, atau gagal baca body).
            val verdict = if (probeBody != null && !probeBodyTruncated) {
                M3u8MasterVerifier.classify(url, M3u8MasterVerifier.parseVariants(probeBody))
            } else {
                M3u8MasterVerifier.verify(url, effectiveReferer, effectiveHeaders)
            }
            when (verdict) {
                is M3u8MasterVerifier.Verdict.Valid -> {
                    for ((variantUrl, height) in verdict.variants) {
                        callback(newExtractorLink(
                            source = source,
                            name = cleanName,
                            url = variantUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = height ?: detectQualityFromUrl(variantUrl)
                            this.referer = effectiveReferer ?: ""
                            this.headers = effectiveHeaders
                        })
                    }
                    com.baseprovider.log.logSuccess(source,
                        "M3U8 master valid -> ${verdict.variants.size} variant dikirim",
                        url = url, extractor = source, runId = runId)
                    return
                }
                is M3u8MasterVerifier.Verdict.AllMalformed -> {
                    com.baseprovider.log.logFail(
                        providerTag,
                        "M3u8MasterVerifier rejected master (all variants malformed): $url",
                        url = url,
                        method = "createSmartLink",
                        type = com.baseprovider.log.FailureType.INVALID_URL,
                        stage = "VERIFY",
                        extractor = source,
                        runId = runId
                    )
                    return
                }
                M3u8MasterVerifier.Verdict.Clean -> {
                    // Master bersih / bukan master / fetch gagal: deliver as-is.
                    com.baseprovider.log.logSuccess(source,
                        "M3U8 clean/bukan-master -> dikirim as-is",
                        url = url, extractor = source, runId = runId)
                }
            }
        }

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
        com.baseprovider.log.logSuccess(source,
            "link delivered (${if (isAdaptive) "adaptive" else "direct"}) " +
                "dalam ${System.currentTimeMillis() - __t0} ms",
            url = url, extractor = source, runId = runId,
            durationMs = System.currentTimeMillis() - __t0)
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
