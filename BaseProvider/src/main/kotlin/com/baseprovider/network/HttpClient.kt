package com.baseprovider.network

import com.baseprovider.cache.ExpiringCache
import com.baseprovider.config.ProviderConfig
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private const val DEFAULT_TIMEOUT = 15000L

private val FALLBACK_UA_POOL = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0"
)

private fun resolveUaVariants(config: ProviderConfig): List<String> {
    val fromConfig = config.uaPool.filter { it.isNotBlank() }
    if (fromConfig.isNotEmpty()) return fromConfig.distinct()
    val configured = config.globalHeaders["User-Agent"]?.takeIf { it.isNotBlank() }
    return buildList {
        if (configured != null) add(configured)
        addAll(FALLBACK_UA_POOL)
    }.distinct().take(3)
}

private fun Map<String, String>.withUa(ua: String): Map<String, String> =
    if (ua.isBlank() || this["User-Agent"] == ua) this
    else this + ("User-Agent" to ua)

suspend fun fetchDocument(
    url: String,
    config: ProviderConfig,
    referer: String? = null,
    skipCache: Boolean = false,
    htmlCache: ExpiringCache<Document>? = null
): Document {
    val fallbackUrls = resolveFallbackUrls(url, config)
    val uaVariants = resolveUaVariants(config)
    var lastError: Exception? = null

    for ((attemptUrl, host) in fallbackUrls) {
        if (!skipCache) { htmlCache?.get(attemptUrl)?.let { return it } }
        if (host.isNotBlank() && HostCircuitBreaker.isOpen(host)) continue
        for (ua in uaVariants) {
            if (host.isNotBlank() && HostCircuitBreaker.isOpen(host)) break
            val headers = config.globalHeaders.withUa(ua)
            return try {
                executeWithRetry {
                    rateLimitDelay(attemptUrl)
                    val res = withTimeout(DEFAULT_TIMEOUT) {
                        app.get(
                            attemptUrl,
                            timeout = DEFAULT_TIMEOUT,
                            headers = headers,
                            referer = referer
                        )
                    }
                    val doc = if (config.useDocumentLarge) res
                        .documentLarge else res.document
                    if (!skipCache) { htmlCache?.put(attemptUrl, doc) }
                    doc
                }.also { HostCircuitBreaker.reportSuccess(host) }
            } catch (e: Exception) {
                lastError = e
                if (host.isNotBlank()) HostCircuitBreaker.reportFailure(host)
                val msg = e.message ?: ""
                if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
                if (CLOUDFLARE_HTTP.containsMatchIn(msg)) {
                    Log.d("OCE", "fetchDocument CF/403 on $attemptUrl (UA=$ua), trying next variant/host")
                    continue
                }
                break
            }
        }
    }

    throw lastError ?: Exception("All mirrors failed for $url")
}

private suspend fun resolveFallbackUrls(url: String,
    config: ProviderConfig): List<Pair<String, String>> {
    val originalUri = runCatching { URI(url) }
        .getOrNull() ?: return listOf(url to "")
    val host = originalUri.host ?: return listOf(url to "")
    val candidates = mutableListOf(url to host)
    val portPart = if (originalUri.port > 0 && originalUri.port != 80
        && originalUri.port != 443) ":${originalUri.port}" else ""
    val pathPart = originalUri.rawPath ?: ""
    val queryPart = if (originalUri.query !=
        null) "?${originalUri.query}" else ""
    val fragmentPart = if (originalUri.fragment != null) "#${originalUri.fragment}" else ""
    for (mirror in config.mirrorUrls) {
        val mirrorHost = runCatching { URI(mirror).host }
            .getOrNull() ?: continue
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
