package com.baseprovider.collector

import com.baseprovider.config.*
import com.baseprovider.extractor.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI

class FallbackPipeline(private val config: ProviderConfig) {

    /**
     * Proses satu kandidat link sampai tuntas TANPA budget wall-clock.
     *
     * Link yang hidup TIDAK boleh terbunuh oleh timeout buatan, dan kandidat
     * yang gagal secara alami tidak memproduksi link apa pun. Batas waktu
     * tetap ada di level request tunggal (NiceHttp timeout, probe 5s, master
     * fetch 20s) plus envelope 120s bawaan app CloudStream di
     * APIRepository.loadLinks (withTimeout), jadi tidak ada yang menggantung
     * selamanya. Link yang ter-deliver selagi loadLinks masih berjalan tampil
     * live di daftar sumber player (stream-in, lihat PlayerGeneratorViewModel).
     */
    suspend fun processLink(
        raw: String, label: String?, currentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        wrappedCallback: (ExtractorLink) -> Unit,
        runId: String? = null
    ) {
        val stepStartedAt = System.currentTimeMillis()
        // Kandidat mentah sering berupa base64 embed code; simpan URL hasil
        // decode agar scrape_steps.link_url bisa dibaca tanpa dekoding manual.
        var resolvedUrl = raw
        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        val lastFailure = java.util.concurrent.atomic.AtomicReference<String>()
        com.lagradost.api.Log.d("FallbackPipeline",
            "[${config.id}] processLink: $raw")
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            delivered.incrementAndGet()
            wrappedCallback(link)
        }
        val ok = runCatching {
                val decodeStart = System.currentTimeMillis()
                val decodedRaw = decodeRawLink(raw)
                val decodeMs = System.currentTimeMillis() - decodeStart
                val fixedUrl = fixUrlSmart(decodedRaw, currentUrl)
                    .safeHttpsify().substringBefore("#").fixKnownDomainAliases()
                if (fixedUrl.isNotBlank()) resolvedUrl = fixedUrl
                val guardHit = isUnusableCandidate(raw, fixedUrl, currentUrl)
                if (guardHit) {
                    // DIAG (TEMPORARY): hapus setelah investigasi guard selesai.
                    // Ditulis ke tabel `logs` (bukan scrape_steps) karena
                    // batch step sering hilang, sedangkan baris `logs` terbukti
                    // konsisten tersimpan.
                    logFail(
                        config.id,
                        "DIAG-GUARD unusable rawHttp=${raw
                            .startsWith("http")} rawB64=${raw
                            .safeIsBase64()} rawLen=${raw.length} " +
                            "decodeMs=$decodeMs decLen=${decodedRaw
                            .length} decHead=${decodedRaw.take(50)} " +
                            "fixed=${fixedUrl.take(70)} cur=${currentUrl.take(60)}",
                        url = resolvedUrl, method = "processLink",
                        type = FailureType.INVALID_URL, stage = "EXTRACT",
                        extractor = config.id, runId = runId,
                        durationMs = System.currentTimeMillis() - stepStartedAt
                    )
                    logDebug(config.id, "Skipping unusable candidate: $raw")
                    SupabaseObservability.logStep(
                        runId, kind = "EXTRACT", status = "failed",
                        linkUrl = resolvedUrl, errorType = FailureType
                            .INVALID_URL.label,
                        durationMs = System.currentTimeMillis() - stepStartedAt
                    )
                    return@runCatching false
                }

                val host = runCatching { URI(fixedUrl).host }
                    .getOrNull()?.lowercase() ?: ""
                if (config.skipHosts.any { h ->
                        h.isNotBlank() && (host == h.lowercase()
                            || host.endsWith(".${h.lowercase()}"))
                    }) {
                    logDebug(config.id, "Skipping skipped host $host: $fixedUrl")
                    SupabaseObservability.logStep(
                        runId, kind = "EXTRACT", status = "failed",
                        linkUrl = fixedUrl, errorType = FailureType
                            .EXTRACTOR_FAILURE.label,
                        durationMs = System.currentTimeMillis() - stepStartedAt
                    )
                    return@runCatching false
                }

                logDebug(config.id, "Processing link: $fixedUrl (label: $label)")

                val okDirect = runCatching {
                    loadExtractorWithFallbackCustom(
                        fixedUrl, currentUrl, subtitleCallback,
                        headers = config.globalHeaders,
                        callback = countingCallback,
                        providerTag = config.id,
                        qualityStripRegex = config.qualityStripRegexCompiled,
                        runId = runId,
                        failureDetail = lastFailure
                    )
                }.getOrDefault(false)
                if (!okDirect) {
                    if (ProviderExtractors.hasMatchingExtractor(fixedUrl)) {
                        logDebug(config.id, "Skipping manual iframe fetch: extractor already tried for $fixedUrl")
                        SupabaseObservability.logStep(
                            runId, kind = "EXTRACT", status = "failed",
                            linkUrl = fixedUrl, errorType = FailureType
                                .EXTRACTOR_FAILURE.label,
                            extractorChain = lastFailure.get()
                                ?.substringBefore('\n')?.trim(),
                            durationMs = System.currentTimeMillis() - stepStartedAt
                        )
                        return@runCatching false
                    }
                    tryManualIframeFetch(fixedUrl, label, currentUrl,
                        subtitleCallback, countingCallback, runId, lastFailure)
                }
                delivered.get() > 0
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logDebug(config.id, "Link Processor Error on $raw: ${e.message}")
                SupabaseObservability.logStep(
                    runId, kind = "EXTRACT", status = "failed",
                    linkUrl = resolvedUrl, errorType = FailureType
                        .EXTRACTOR_FAILURE.label,
                    extractorChain = (lastFailure.get() ?: e.message)
                        ?.substringBefore('\n')?.trim(),
                    durationMs = System.currentTimeMillis() - stepStartedAt
                )
                false
            }
        if (ok) {
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "success",
                linkUrl = resolvedUrl,
                durationMs = System.currentTimeMillis() - stepStartedAt
            )
        }
    }

    internal fun isUnusableCandidate(raw: String, resolved: String, currentUrl: String): Boolean {
        val r = raw.trim()
        if (r.isEmpty() || resolved.isBlank()) return true
        // Token sampah hasil scraper (mis. "all_comment") tidak punya scheme,
        // host, path, maupun query — pasti bukan link.
        if (!r.startsWith("http") && !r.startsWith("//") && !r.startsWith("/") &&
            !r.contains(".") && !r.contains("/") && !r.contains("?") && !r.contains("#")
        ) return true
        // Kandidat yang menunjuk halaman yang sedang diproses (path identik,
        // query diabaikan) tidak bisa menghasilkan link baru.
        return isSamePage(resolved, currentUrl)
    }

    private fun isSamePage(a: String, b: String): Boolean {
        if (b.isBlank()) return false
        return try {
            val ua = URI(a)
            val ub = URI(b)
            ua.host != null && ua.host == ub.host &&
                ua.path.trimEnd('/') == ub.path.trimEnd('/')
        } catch (_: Exception) {
            false
        }
    }

    internal suspend fun decodeRawLink(raw: String): String {
        // Sebagian mirror mengirim HTML iframe mentah (tanpa base64).
        if (raw.trimStart().startsWith("<")) {
            val src = Jsoup.parse(raw).selectFirst("iframe")?.attr("src")?.trim()
            if (!src.isNullOrBlank()) return src
        }
        if (raw.startsWith("http") || raw.startsWith("//") || raw
            .startsWith("/") || !raw.safeIsBase64()) return raw
        val lk21 = decryptLk21PlayerUrl(raw)
        if (lk21 != null) return lk21
        val dec = raw.safeDecode()
        // Sebagian embed code memakai kapital (<IFRAME SRC=...>); pemeriksaan
        // harus case-insensitive agar Morencius/StreamRuby tetap terpanggil.
        if (dec.contains("iframe", ignoreCase = true)) return Jsoup
            .parse(dec).selectFirst("iframe")?.attr("src") ?: ""
        if (dec.startsWith("http") || dec.startsWith("//") || dec
            .startsWith("/")) return dec
        return ""
    }

    suspend fun tryManualIframeFetch(
        fixedUrl: String, label: String?, currentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        wrappedCallback: (ExtractorLink) -> Unit,
        runId: String? = null,
        lastFailure: java.util.concurrent.atomic.AtomicReference<String>? = null
    ) {
        val startedAt = System.currentTimeMillis()
        val baseForReferer = config.seriesUrl ?: config.mainUrl
        val refererForPlayer = if (config.refererPlayerMode == "series_url") "${baseForReferer.trimEnd('/')}/" else currentUrl
        logDebug(config.id, "Direct extraction failed, trying manual iframe fetch for: $fixedUrl (Referer: $refererForPlayer)")

        val playerDoc = fetchDocument(
            fixedUrl, config, referer = refererForPlayer,
            skipCache = false
        )
        val iframeSelectors = config.iframeSelectors
        val iframeAttributes = config.iframeSources

        logDebug(config.id, "Manual iframe: selectors=$iframeSelectors, attrs=$iframeAttributes")

        val iframeEl = if (iframeSelectors.isNotBlank()) playerDoc
            .selectFirst(iframeSelectors) else null
        if (iframeEl == null) {
            // stage=COLLECT: gagal sebelum extractor sempat jalan, jadi `extractor`
            // sengaja dibiarkan null (tidak ada extractor yang bisa disalahkan).
            logFail(
                config.id, "No iframe found",
                url = currentUrl, method = "loadLinks",
                type = FailureType.INVALID_IFRAME,
                selectors = iframeSelectors,
                stage = "COLLECT"
            )
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "failed",
                linkUrl = fixedUrl, errorType = FailureType
                    .INVALID_IFRAME.label,
                extractorChain = lastFailure?.get()?.substringBefore('\n')
                    ?.trim(),
                durationMs = System.currentTimeMillis() - startedAt
            )
            return
        }

        val iframeSrc = iframeAttributes.firstNotNullOfOrNull { iframeEl
            .attr(it).takeIf { v -> v.isNotBlank() && v != "about:blank" } }
        if (iframeSrc == null) {
            // stage=COLLECT: element iframe ada tapi tanpa src — belum ada
            // extractor yang dijalankan, jadi `extractor` tetap null.
            logFail(
                config.id, "Iframe has no src",
                url = currentUrl, method = "loadLinks",
                type = FailureType.INVALID_IFRAME,
                selectors = iframeAttributes.joinToString(", "),
                stage = "COLLECT"
            )
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "failed",
                linkUrl = fixedUrl, errorType = FailureType
                    .INVALID_IFRAME.label,
                extractorChain = lastFailure?.get()?.substringBefore('\n')
                    ?.trim(),
                durationMs = System.currentTimeMillis() - startedAt
            )
            return
        }

        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
        val refererForExtractor = getBaseUrl(fixedUrl)

        // Skip check untuk iframe recursive — iframe samehadaku sering
        // menunjuk ke host yang sudah di-skip (acefile/gofile), dan path
        // ini tidak lewat skip check di loop utama.
        val iframeHost = runCatching { URI(finalIframe).host }
            .getOrNull()?.lowercase() ?: ""
        if (config.skipHosts.any { h ->
                h.isNotBlank() && (iframeHost == h.lowercase()
                    || iframeHost.endsWith(".${h.lowercase()}"))
            }) {
            logDebug(config.id, "Skipping skipped host (iframe) $iframeHost: $finalIframe")
            return
        }

        logDebug(config.id, "Found iframe: $finalIframe, extracting...")

        val okRecursive = runCatching {
            loadExtractorWithFallbackCustom(
                finalIframe, refererForExtractor, subtitleCallback,
                headers = config.globalHeaders,
                callback = wrappedCallback,
                providerTag = config.id,
                runId = runId
            )
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (!okRecursive && finalIframe.isDirectMediaUrl()) {
            MasterLinkGenerator.createSmartLink(
                label ?: config.name, finalIframe, refererForExtractor,
                headers = config.globalHeaders,
                qualityStripRegex = config.qualityStripRegexCompiled,
                providerTag = config.id,
                runId = runId,
                callback = wrappedCallback
            )
        } else if (!okRecursive) {
            // Iframe tidak membawa media langsung: catat kegagalan (sebelumnya
            // host tanpa matching extractor "hilang" tanpa jejak di step).
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "failed",
                linkUrl = fixedUrl, errorType = FailureType
                    .EXTRACTOR_FAILURE.label,
                extractorChain = (lastFailure?.get() ?: "manual iframe: " +
                    "no playable source").substringBefore('\n').trim(),
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    fun logLinkResults(extracted: Int, totalLinks: Int, data: String) {
        if (extracted > 0) {
            logSuccess(config.id, "$extracted/$totalLinks video(s) extracted", url = data, method = "loadLinks", selectors = config.linkOptions)
        } else if (totalLinks > 0) {
            logFail(
                config.id, "0/$totalLinks links produced video",
                url = data, method = "loadLinks",
                type = FailureType.EXTRACTOR_FAILURE,
                selectors = config.linkOptions
            )
        }
    }
}
