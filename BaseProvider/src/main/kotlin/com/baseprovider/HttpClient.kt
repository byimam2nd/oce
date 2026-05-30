package com.baseprovider

import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private const val DEFAULT_TIMEOUT = 15000L

suspend fun fetchDocument(url: String, config: ProviderConfig, referer: String? = null, skipCache: Boolean = false): Document {
    val fallbackUrls = resolveFallbackUrls(url, config)
    var lastError: Exception? = null

    for ((attemptUrl, host) in fallbackUrls) {
        if (!skipCache) { globalHtmlCache.get(attemptUrl)?.let { return it } }
        if (host.isNotBlank() && HostCircuitBreaker.isOpen(host)) continue

        return try {
            executeWithRetry {
                rateLimitDelay(attemptUrl)
                val res = withTimeout(DEFAULT_TIMEOUT) { app.get(attemptUrl, timeout = DEFAULT_TIMEOUT, headers = config.globalHeaders, referer = referer) }
                val doc = if (config.useDocumentLarge) res.documentLarge else res.document
                if (!skipCache) { globalHtmlCache.put(attemptUrl, doc) }
                doc
            }.also { HostCircuitBreaker.reportSuccess(host) }
        } catch (e: Exception) {
            lastError = e
            HostCircuitBreaker.reportFailure(host)
            val msg = e.message ?: ""
            if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
            if (attemptUrl != fallbackUrls.last().first) continue
            throw e
        }
    }

    throw lastError ?: Exception("All mirrors failed for $url")
}

private suspend fun resolveFallbackUrls(url: String, config: ProviderConfig): List<Pair<String, String>> {
    val originalUri = runCatching { URI(url) }.getOrNull() ?: return listOf(url to "")
    val host = originalUri.host ?: return listOf(url to "")
    val candidates = mutableListOf(url to host)
    val portPart = if (originalUri.port > 0 && originalUri.port != 80 && originalUri.port != 443) ":${originalUri.port}" else ""
    val pathPart = originalUri.rawPath ?: ""
    val queryPart = if (originalUri.query != null) "?${originalUri.query}" else ""
    val fragmentPart = if (originalUri.fragment != null) "#${originalUri.fragment}" else ""
    for (mirror in config.mirrorUrls) {
        val mirrorHost = runCatching { URI(mirror).host }.getOrNull() ?: continue
        if (mirrorHost == host) continue
        candidates.add("${originalUri.scheme}://$mirrorHost$portPart$pathPart$queryPart$fragmentPart" to mirrorHost)
    }
    return candidates
}

fun Element.selectAttr(attrNames: List<String>): String? {
    for (name in attrNames) {
        val v = attr(name)
        if (v.isNotBlank() && v != "about:blank") return v
    }
    return null
}
