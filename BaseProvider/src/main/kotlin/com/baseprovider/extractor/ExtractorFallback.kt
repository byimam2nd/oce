package com.baseprovider.extractor
import com.baseprovider.log.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// Batas total waktu untuk blok extractor paralel (local extractor) supaya
// satu extractor yang lambat tidak menahan jalur fallback ke global/direct.
private const val EXTRACTOR_BLOCK_TIMEOUT_MS = 20_000L

/**
 * Selesai pada hasil pertama: jika link pertama terkumpul, cancel extractor
 * lain yang masih jalan (latency = extractor tercepat, bukan terlambat).
 * Jika semua selesai tanpa link (allDone), lanjut fallback. Caller tidak
 * memblokir menunggu extractor yang lambat.
 */
private suspend fun selectFirstOf(
    firstLink: CompletableDeferred<Unit>,
    allDone: CompletableDeferred<Unit>
) {
    select<Unit> {
        firstLink.onAwait {
            // Link pertama ditemukan — hentikan extractor lain yang menunggu.
            currentCoroutineContext().cancelChildren()
        }
        allDone.onAwait { Unit }
    }
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
    runId: String? = null
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
        // Jalankan extractor paralel (sem 3) tapi SELESAI saat link pertama
        // ditemukan — jangan menunggu extractor terlambat. Job lain di-cancel.
        val firstLink = CompletableDeferred<Unit>()
        val allDone = CompletableDeferred<Unit>()
        val firstCallback: (ExtractorLink) -> Unit = { link ->
            internalCallback(link)
            firstLink.complete(Unit)
        }
        withTimeoutOrNull(EXTRACTOR_BLOCK_TIMEOUT_MS) {
            coroutineScope {
                val semaphore = Semaphore(3)
                val extractorJobs = matchingExtractors.mapIndexed { idx, extractor ->
                    launch {
                        semaphore.withPermit {
                            runCatching {
                                extractor.getUrl(url, referer, subtitleCallback,
                                    firstCallback)
                            }.onFailure { e ->
                                // Cancellation (dari cancel-on-first-success atau
                                // timeout blok) WAJIB diteruskan, bukan ditelan —
                                // kalau ditelan, extractor lambat tidak berhenti dan
                                // coroutineScope menunggu sampai timeout alaminya.
                                if (e is kotlinx.coroutines.CancellationException) {
                                    throw e
                                }
                                logFail(
                                    providerId,
                                    "Local Extractor (${extractor.name}) failed for $url: ${e.message}",
                                    url = url, method = "extractLinks",
                                    type = FailureType.EXTRACTOR_FAILURE,
                                    selectors = extractor.name,
                                    stage = "EXTRACT",
                                    extractor = extractor.name,
                                    attempt = idx + 1,
                                    runId = runId
                                )
                            }
                        }
                    }
                }
                launch {
                    extractorJobs.forEach { it.join() }
                    allDone.complete(Unit)
                }
                // Selesai saat link pertama terkumpul — ekstraktor lain di-cancel.
                selectFirstOf(firstLink, allDone)
            }
        }
    }

    if (collectedLinks.isEmpty()) {
        runCatching {
            loadExtractor(url, referer, subtitleCallback, internalCallback)
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logFail(
                providerId, "Global Extractor failed for $url: ${e.message}",
                url = url, method = "extractLinks",
                type = FailureType.EXTRACTOR_FAILURE,
                selectors = callChain,
                stage = "EXTRACT",
                runId = runId
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
                logFail(
                    providerId, "DeepScan found no video URLs in HTML source of $url",
                    url = url, method = "extractLinks",
                    type = FailureType.EMPTY_RESPONSE,
                    selectors = callChain,
                    stage = "EXTRACT",
                    runId = runId
                )
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logFail(
                providerId, "DeepScan network failure for $url: ${e.message}",
                url = url, method = "extractLinks",
                type = FailureType.NETWORK_FAILURE,
                selectors = callChain,
                stage = "EXTRACT",
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
