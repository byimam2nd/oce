package com.baseprovider.extractor
import com.baseprovider.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

suspend fun loadExtractorWithFallbackCustom(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    headers: Map<String, String>? = null,
    callback: (ExtractorLink) -> Unit,
    providerTag: String = "ExtractorEngine",
    callChain: String = "-",
    qualityStripRegex: Regex = Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE)
): Boolean {
    val collectedLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
    val seenUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
        coroutineScope {
            val semaphore = Semaphore(3)
            matchingExtractors.forEach { extractor ->
                launch { semaphore.withPermit {
                    runCatching {
                        extractor.getUrl(url, referer, subtitleCallback, internalCallback)
                    }.onFailure { e ->
                        logFail(providerId, "Local Extractor (${extractor.name}) failed for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = extractor.name)
                    }
                } }
            }
        }
    }

    if (collectedLinks.isEmpty()) {
        runCatching {
            loadExtractor(url, referer, subtitleCallback, internalCallback)
        }.onFailure { e ->
            logFail(providerId, "Global Extractor failed for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = callChain)
        }
    }

    if (collectedLinks.isEmpty() && url.isDirectMediaUrl()) {
        MasterLinkGenerator.createSmartLink("Direct", url, referer, headers = headers, qualityStripRegex = qualityStripRegex, callback = internalCallback)
    }

    if (collectedLinks.isEmpty()) {
        runCatching {
            val response = withTimeout(15000L) { app.get(url, referer = referer, headers = headers ?: emptyMap(), timeout = 15000L).text }
            val urls = CompiledRegexPatterns.extractAllVideoUrls(response)
            val filtered = CompiledRegexPatterns.filterMasterM3u8(urls)
            if (filtered.isNotEmpty()) {
                filtered.forEach { videoUrl ->
                    MasterLinkGenerator.createSmartLink("DeepScan", videoUrl, url, headers = headers, qualityStripRegex = qualityStripRegex, callback = internalCallback)
                }
            } else {
                logFail(providerId, "DeepScan found no video URLs in HTML source of $url", url = url, method = "extractLinks", type = FailureType.EMPTY_RESPONSE, selectors = callChain)
            }
        }.onFailure { e ->
            logFail(providerId, "DeepScan network failure for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.NETWORK_FAILURE, selectors = callChain)
        }
    }

    val extractorNames = matchingExtractors.joinToString(", ") { it.name }.ifBlank { "none" }
    val chainInfo = if (callChain == "-") extractorNames else "$callChain → $extractorNames"
    if (collectedLinks.isEmpty() && urlDomain.isNotBlank() && url.startsWith("http")) {
        val ft = if (urlDomain.contains("short.") || urlDomain.contains("shorte")) FailureType.SHORTLINK_FAILURE
            else FailureType.EXTRACTOR_FAILURE
        logFail(providerId, "All extraction methods failed to find playable links for host: $urlDomain", url = url, method = "extractLinks", type = ft, selectors = chainInfo)
    } else if (collectedLinks.isNotEmpty()) {
        logSuccess(providerId, "${collectedLinks.size} links", url = url, method = "extractLinks", selectors = chainInfo)
    }

    MasterLinkGenerator.refineAndDeliver(collectedLinks, callback, qualityStripRegex)
    return collectedLinks.isNotEmpty()
}
