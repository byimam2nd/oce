package com.baseprovider

import com.baseprovider.ProviderConfig
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup

class FallbackPipeline(private val config: ProviderConfig) {

    suspend fun processLink(raw: String, label: String?, currentUrl: String, subtitleCallback: (SubtitleFile) -> Unit, wrappedCallback: (ExtractorLink) -> Unit) {
        runCatching {
            val decodedRaw = decodeRawLink(raw)
            val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify().substringBefore("#")
            if (fixedUrl.isBlank()) return@runCatching

            logDebug(config.id, "Processing link: $fixedUrl (label: $label)")

            val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, headers = config.globalHeaders, callback = wrappedCallback, providerTag = config.id, qualityStripRegex = config.qualityStripRegexCompiled) }.getOrDefault(false)
            if (!okDirect) {
                if (ProviderExtractors.hasMatchingExtractor(fixedUrl)) {
                    logDebug(config.id, "Skipping manual iframe fetch: extractor already tried for $fixedUrl")
                    return@runCatching
                }
                tryManualIframeFetch(fixedUrl, label, currentUrl, subtitleCallback, wrappedCallback)
            }
        }.getOrElse { e -> logDebug(config.id, "Link Processor Error on $raw: ${e.message}") }
    }

    private suspend fun decodeRawLink(raw: String): String {
        if (raw.startsWith("http") || raw.startsWith("//") || raw.startsWith("/") || !raw.safeIsBase64()) return raw
        val lk21 = decryptLk21PlayerUrl(raw)
        if (lk21 != null) return lk21
        val dec = raw.safeDecode()
        if (dec.contains("iframe")) return Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: ""
        if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) return dec
        return ""
    }

    suspend fun tryManualIframeFetch(fixedUrl: String, label: String?, currentUrl: String, subtitleCallback: (SubtitleFile) -> Unit, wrappedCallback: (ExtractorLink) -> Unit) {
        val baseForReferer = config.seriesUrl ?: config.mainUrl
        val refererForPlayer = if (config.refererPlayerMode == "series_url") "${baseForReferer.trimEnd('/')}/" else currentUrl
        logDebug(config.id, "Direct extraction failed, trying manual iframe fetch for: $fixedUrl (Referer: $refererForPlayer)")

        val playerDoc = withTimeout(15000L) { app.get(fixedUrl, referer = refererForPlayer, headers = config.globalHeaders, timeout = 15000L).document }
        val iframeSelectors = config.iframeSelectors
        val iframeAttributes = config.iframeSources

        logDebug(config.id, "Manual iframe: selectors=$iframeSelectors, attrs=$iframeAttributes")

        val iframeEl = if (iframeSelectors.isNotBlank()) playerDoc.selectFirst(iframeSelectors) else null
        if (iframeEl == null) {
            logFail(config.id, "No iframe found", url = currentUrl, method = "loadLinks", type = FailureType.INVALID_IFRAME, selectors = iframeSelectors)
            return
        }

        val iframeSrc = iframeAttributes.firstNotNullOfOrNull { iframeEl.attr(it).takeIf { v -> v.isNotBlank() && v != "about:blank" } }
        if (iframeSrc == null) {
            logFail(config.id, "Iframe has no src", url = currentUrl, method = "loadLinks", type = FailureType.INVALID_IFRAME, selectors = iframeAttributes.joinToString(", "))
            return
        }

        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
        val refererForExtractor = getBaseUrl(fixedUrl)

        logDebug(config.id, "Found iframe: $finalIframe, extracting...")

        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, headers = config.globalHeaders, callback = wrappedCallback, providerTag = config.id) }.getOrDefault(false)
        if (!okRecursive && finalIframe.isDirectMediaUrl()) {
            MasterLinkGenerator.createSmartLink(label ?: config.name, finalIframe, refererForExtractor, headers = config.globalHeaders, qualityStripRegex = config.qualityStripRegexCompiled, callback = wrappedCallback)
        }
    }

    fun logLinkResults(extracted: Int, totalLinks: Int, data: String) {
        if (extracted > 0) {
            logSuccess(config.id, "$extracted/$totalLinks video(s) extracted", url = data, method = "loadLinks", selectors = config.linkOptions)
        } else if (totalLinks > 0) {
            logFail(config.id, "0/$totalLinks links produced video", url = data, method = "loadLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = config.linkOptions)
        }
    }
}
