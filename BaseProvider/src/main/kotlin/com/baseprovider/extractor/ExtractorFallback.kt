package com.baseprovider.extractor
import com.baseprovider.log.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * Catat pesan diagnostik kegagalan PERTAMA (compareAndSet-null). Fallback
 * global/direct/deep-scan hanya overwrite jika belum ada nilai — penyebab
 * paling spesifik (mis. nama extractor + HTTP code) tetap menang.
 */
private fun diag(
    ref: AtomicReference<String>?, message: String
) {
    if (ref != null && ref.get() == null) ref.set(message)
}

suspend fun loadExtractorWithFallbackCustom(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    headers: Map<String, String>? = null,
    callback: (ExtractorLink) -> Unit,
    providerTag: String = "ExtractorEngine",
    callChain: String = "-",
    qualityStripRegex: Regex = Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption
        .IGNORE_CASE),
    runId: String? = null,
    failureDetail: AtomicReference<String>? = null
): Boolean {
    val collectedLinks = java.util.Collections
        .synchronizedList(mutableListOf<ExtractorLink>())
    val seenUrls = java.util.Collections
        .synchronizedSet(mutableSetOf<String>())
    val providerId = providerTag

    val internalCallback: (ExtractorLink) -> Unit = { link ->
        if (seenUrls.add(link.url)) { collectedLinks.add(link) }
    }

    val matchingExtractors = ProviderExtractors.getMatchingExtractors(url)
    val urlDomain = url.normalizeDomain()

    if (matchingExtractors.isEmpty()) {
        logDebug(providerId, "No matching extractor for host: $urlDomain")
    } else {
        logDebug(providerId, "Matching extractors for $urlDomain: ${matchingExtractors.joinToString(", ") { it.name }}")
    }

    if (matchingExtractors.isNotEmpty()) {
        // Jalankan extractor paralel (sem 3). TANPA budget wall-clock — batas
        // hanya per-request alami (NiceHttp, probe 5s, master fetch 20s, plus
        // envelope 120s bawaan APIRepository di app). Saat extractor pertama
        // mengirim source, CANCEL hanya extractor LAINNYA. Extractor pemenang
        // TIDAK di-cancel agar selesai mengirim SEMUA sourcenya (config-driven
        // seperti abyssplayer mem-probe 3 source berurutan — cancel di link
        // pertama membuat source 2/3 hilang).
        val firstWinner = CompletableDeferred<Int>()
        val extractorJobs = java.util.Collections
            .synchronizedList(mutableListOf<Job>())
        coroutineScope {
            val semaphore = Semaphore(3)
            matchingExtractors.forEachIndexed { idx, extractor ->
                extractorJobs.add(launch {
                    semaphore.withPermit {
                        runCatching {
                            extractor.getUrl(url, referer, subtitleCallback) { link ->
                                internalCallback(link)
                                // Pemenang pertama: cancel extractor lain, biarkan
                                // pemenang menyelesaikan semua source-nya.
                                if (firstWinner.tryComplete(idx)) {
                                    extractorJobs.forEachIndexed { j, job ->
                                        if (j != idx) job.cancel()
                                    }
                                }
                            }
                        }.onFailure { e ->
                            // Cancellation (dari pemenang lain) WAJIB diteruskan,
                            // bukan ditelan — kalau ditelan extractor lambat tidak
                            // berhenti dan coroutineScope menunggu lama.
                            if (e is kotlinx.coroutines.CancellationException) {
                                throw e
                            }
                            diag(failureDetail, "${extractor.name}: " +
                                (e.message?.substringBefore('\n')?.trim()
                                    ?: e.javaClass.simpleName))
                            logFail(
                                providerId,
                                "Local Extractor (${extractor.name}) failed for $url: ${e.message}",
                                url = url, method = "extractLinks",
                                type = FailureType.EXTRACTOR_FAILURE,
                                selectors = extractor.name,
                                stage = "EXTRACT",
                                extractor = extractor.name,
                                attempt = idx + 1,
                                runId = runId,
                                error = e
                            )
                        }
                    }
                })
            }
        }
    }

    if (collectedLinks.isEmpty()) {
        runCatching {
            loadExtractor(url, referer, subtitleCallback, internalCallback)
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            diag(failureDetail, "Global: " +
                (e.message?.substringBefore('\n')?.trim()
                    ?: e.javaClass.simpleName))
            logFail(
                providerId, "Global Extractor failed for $url: ${e.message}",
                url = url, method = "extractLinks",
                type = FailureType.EXTRACTOR_FAILURE,
                selectors = callChain,
                stage = "EXTRACT",
                extractor = urlDomain,
                runId = runId,
                error = e
            )
        }
    }

    if (collectedLinks.isEmpty() && url.isDirectMediaUrl()) {
        MasterLinkGenerator.createSmartLink(
            "Direct", url, null,
            headers = headers,
            bareHeaders = true,
            qualityStripRegex = qualityStripRegex,
            providerTag = providerId,
            runId = runId,
            callback = internalCallback
        )
    }

    if (collectedLinks.isEmpty()) {
        runCatching {
            val response = withTimeout(15000L) { app.get(url, referer =
                referer, headers = headers ?: emptyMap(), timeout = 15000L)
                    .text }
            val urls = CompiledRegexPatterns.extractAllVideoUrls(response)
            val filtered = CompiledRegexPatterns.filterMasterM3u8(urls)
            if (filtered.isNotEmpty()) {
                filtered.forEach { videoUrl ->
                    MasterLinkGenerator.createSmartLink(
                        "DeepScan", videoUrl, null,
                        headers = headers,
                        bareHeaders = true,
                        qualityStripRegex = qualityStripRegex,
                        providerTag = providerId,
                        runId = runId,
                        callback = internalCallback
                    )
                }
            } else {
                diag(failureDetail, "DeepScan: no video URLs in HTML source")
                // DeepScan = fallback terakhir, jadi tidak ada extractor
                // spesifik yang bisa disalahkan. Pakai host (urlDomain) —
                // konsisten dengan site "All extraction methods failed" —
                // supaya query "host mana yang selalu gagal" tetap jalan.
                logFail(
                    providerId, "DeepScan found no video URLs in HTML source of $url",
                    url = url, method = "extractLinks",
                    type = FailureType.EMPTY_RESPONSE,
                    selectors = callChain,
                    stage = "EXTRACT",
                    extractor = urlDomain,
                    runId = runId
                )
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            diag(failureDetail, "DeepScan: " +
                (e.message?.substringBefore('\n')?.trim()
                    ?: e.javaClass.simpleName))
            logFail(
                providerId, "DeepScan network failure for $url: ${e.message}",
                url = url, method = "extractLinks",
                type = FailureType.NETWORK_FAILURE,
                selectors = callChain,
                stage = "EXTRACT",
                extractor = urlDomain,
                runId = runId
            )
        }
    }

    val extractorNames = matchingExtractors.joinToString(", ") { it.name }
        .ifBlank { "none" }
    val chainInfo = if (callChain == "-") extractorNames else "$callChain → $extractorNames"
    if (collectedLinks.isEmpty() && urlDomain.isNotBlank() && url
        .startsWith("http")) {
        val ft = if (urlDomain.contains("short.") || urlDomain.contains("shorte")) FailureType.SHORTLINK_FAILURE
            else FailureType.EXTRACTOR_FAILURE
        diag(failureDetail, "all: $urlDomain")
        logFail(
            providerId, "All extraction methods failed to find playable links for host: $urlDomain",
            url = url, method = "extractLinks",
            type = ft, selectors = chainInfo,
            stage = "EXTRACT",
            extractor = urlDomain,
            runId = runId
        )
    } else if (collectedLinks.isNotEmpty()) {
        logSuccess(providerId, "${collectedLinks.size} links", url = url,
            method = "extractLinks", selectors = chainInfo,
            stage = "EXTRACT",
            extractor = urlDomain,
            runId = runId)
    }

    MasterLinkGenerator.refineAndDeliver(collectedLinks, callback,
        qualityStripRegex)
    return collectedLinks.isNotEmpty()
}
