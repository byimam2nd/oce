package com.baseprovider.collector

import com.baseprovider.config.*
import com.baseprovider.extractor.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI

class FallbackPipeline(private val config: ProviderConfig) {

    /**
     * Per-link timeout budget: chain extractor lokal → global → direct →
     * deep-scan → manual iframe tidak boleh menghabiskan 60-90s per link
     * rusak. Timeout → link dianggap gagal, lanjut link berikutnya.
     */
    suspend fun processLink(
        raw: String, label: String?, currentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        wrappedCallback: (ExtractorLink) -> Unit,
        runId: String? = null
    ) {
        val stepStartedAt = System.currentTimeMillis()
        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            delivered.incrementAndGet()
            wrappedCallback(link)
        }
        val ok = withTimeoutOrNull(PER_LINK_TIMEOUT_MS) {
            runCatching {
                val decodedRaw = decodeRawLink(raw)
                val fixedUrl = fixUrlSmart(decodedRaw, currentUrl)
                    .safeHttpsify().substringBefore("#").fixKnownDomainAliases()
                if (fixedUrl.isBlank()) {
                    SupabaseObservability.logStep(
                        runId, kind = "EXTRACT", status = "failed",
                        linkUrl = raw, errorType = FailureType
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
                        runId = runId
                    )
                }.getOrDefault(false)
                if (!okDirect) {
                    if (ProviderExtractors.hasMatchingExtractor(fixedUrl)) {
                        logDebug(config.id, "Skipping manual iframe fetch: extractor already tried for $fixedUrl")
                        SupabaseObservability.logStep(
                            runId, kind = "EXTRACT", status = "failed",
                            linkUrl = fixedUrl, errorType = FailureType
                                .EXTRACTOR_FAILURE.label,
                            durationMs = System.currentTimeMillis() - stepStartedAt
                        )
                        return@runCatching false
                    }
                    tryManualIframeFetch(fixedUrl, label, currentUrl,
                        subtitleCallback, countingCallback, runId)
                }
                delivered.get() > 0
            }.getOrElse { e ->
                logDebug(config.id, "Link Processor Error on $raw: ${e.message}")
                SupabaseObservability.logStep(
                    runId, kind = "EXTRACT", status = "failed",
                    linkUrl = raw, errorType = FailureType
                        .EXTRACTOR_FAILURE.label,
                    durationMs = System.currentTimeMillis() - stepStartedAt
                )
                false
            }
        } ?: run {
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "timeout",
                linkUrl = raw, errorType = FailureType.TIMEOUT.label,
                durationMs = PER_LINK_TIMEOUT_MS
            )
            false
        }
        if (ok) {
            SupabaseObservability.logStep(
                runId, kind = "EXTRACT", status = "success",
                linkUrl = raw,
                durationMs = System.currentTimeMillis() - stepStartedAt
            )
        }
    }

    companion object {
        private const val PER_LINK_TIMEOUT_MS = 20_000L
    }

    private suspend fun decodeRawLink(raw: String): String {
        if (raw.startsWith("http") || raw.startsWith("//") || raw
            .startsWith("/") || !raw.safeIsBase64()) return raw
        val lk21 = decryptLk21PlayerUrl(raw)
        if (lk21 != null) return lk21
        val dec = raw.safeDecode()
        if (dec.contains("iframe")) return Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: ""
        if (dec.startsWith("http") || dec.startsWith("//") || dec
            .startsWith("/")) return dec
        return ""
    }

    suspend fun tryManualIframeFetch(
        fixedUrl: String, label: String?, currentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        wrappedCallback: (ExtractorLink) -> Unit,
        runId: String? = null
    ) {
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
            logFail(
                config.id, "No iframe found",
                url = currentUrl, method = "loadLinks",
                type = FailureType.INVALID_IFRAME,
                selectors = iframeSelectors
            )
            return
        }

        val iframeSrc = iframeAttributes.firstNotNullOfOrNull { iframeEl
            .attr(it).takeIf { v -> v.isNotBlank() && v != "about:blank" } }
        if (iframeSrc == null) {
            logFail(
                config.id, "Iframe has no src",
                url = currentUrl, method = "loadLinks",
                type = FailureType.INVALID_IFRAME,
                selectors = iframeAttributes.joinToString(", ")
            )
            return
        }

        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
        val refererForExtractor = getBaseUrl(fixedUrl)

        logDebug(config.id, "Found iframe: $finalIframe, extracting...")

        val okRecursive = runCatching {
            loadExtractorWithFallbackCustom(
                finalIframe, refererForExtractor, subtitleCallback,
                headers = config.globalHeaders,
                callback = wrappedCallback,
                providerTag = config.id,
                runId = runId
            )
        }.getOrDefault(false)
        if (!okRecursive && finalIframe.isDirectMediaUrl()) {
            MasterLinkGenerator.createSmartLink(
                label ?: config.name, finalIframe, refererForExtractor,
                headers = config.globalHeaders,
                qualityStripRegex = config.qualityStripRegexCompiled,
                providerTag = config.id,
                runId = runId,
                callback = wrappedCallback
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
