package com.baseprovider.network

import com.baseprovider.cache.ExpiringCache
import com.baseprovider.config.ProviderConfig
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private const val DEFAULT_TIMEOUT = 15000L

private val FALLBACK_UA_POOL = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
)

private fun resolveUaVariants(config: ProviderConfig): List<String> {
    val fromConfig = config.uaPool.filter { it.isNotBlank() }
    if (fromConfig.isNotEmpty()) return fromConfig.distinct()
    val configured = config.globalHeaders["User-Agent"]?.takeIf { it.isNotBlank() }
    return buildList {
        if (configured != null) add(configured)
        addAll(FALLBACK_UA_POOL)
    }.distinct().take(4)
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
        var hostFailed = false
        // Kalau Cloudflare sudah di-solve via WebView utk host ini, cf_clearance
        // terikat ke UA WebView - pakai UA itu dulu sebelum pool.
        hostLoop@ while (true) {
            val webViewUa = WebViewCloudflareSolver.userAgentFor(host)
            val uaOrder = buildList {
                if (!webViewUa.isNullOrBlank()) add(webViewUa)
                for (u in uaVariants) if (u != webViewUa) add(u)
            }
            for (ua in uaOrder) {
                if (host.isNotBlank() && HostCircuitBreaker.isOpen(host)) break
                val headers = config.globalHeaders.withUa(ua)
                try {
                    val res = executeWithRetry {
                        rateLimitDelay(attemptUrl)
                        val r = withTimeout(DEFAULT_TIMEOUT) {
                            app.get(
                                attemptUrl,
                                timeout = DEFAULT_TIMEOUT,
                                headers = headers,
                                referer = referer ?: googleReferer(config),
                                cookies = HostCookieJar.getFor(attemptUrl)
                            )
                        }
                        // app.get (NiceHttp) tidak throw pada status error — cek secara eksplisit.
                        // Ditaruh di dalam executeWithRetry agar 429 di-retry dengan delay Retry-After.
                        if (r.code >= 400) {
                            val retryAfter = parseRetryAfter(r.headers["Retry-After"])
                            throw HttpStatusException(
                                r.code,
                                retryAfter,
                                "HTTP ${r.code} on $attemptUrl"
                            )
                        }
                        r
                    }
                    HostCookieJar.update(attemptUrl, res.cookies)
                    val doc = if (config.useDocumentLarge) res.documentLarge else res.document
                    if (!skipCache) { htmlCache?.put(attemptUrl, doc) }
                    if (host.isNotBlank()) {
                        HostCircuitBreaker.reportSuccess(host)
                        SmartThrottle.reportSuccess(host)
                    }
                    return doc
                } catch (e: Exception) {
                    lastError = e
                    hostFailed = true
                    when {
                        e is HttpStatusException -> {
                            val msg = e.message.orEmpty()
                            SmartThrottle.reportFailure(host)
                            when {
                                CLOUDFLARE_HTTP.containsMatchIn(msg) -> {
                                    // 403 CF: coba solve challenge via WebView sekali utk host ini.
                                    // Kalau sukses, cf_clearance + UA WebView tersimpan - restart
                                    // sub-loop supaya request ulang diprioritaskan pakai UA WebView.
                                    if (config.useWebViewFallback && !WebViewCloudflareSolver.isSolved(host)) {
                                        Log.d("OCE", "fetchDocument CF/403 on $attemptUrl, trying WebView CF solver")
                                        val solved = WebViewCloudflareSolver.trySolve(attemptUrl, referer ?: config.mainUrl)
                                        Log.d("OCE", "WebView CF solver for $attemptUrl: ${if (solved) "solved" else "failed"}")
                                        if (solved) continue@hostLoop
                                    }
                                    // Rotasi UA berikutnya, lalu mirror berikutnya.
                                    Log.d("OCE", "fetchDocument CF/403 on $attemptUrl (UA=$ua), trying next variant/host")
                                    continue
                                }
                                e.code == 429 -> {
                                    // Rate limit: hormati Retry-After via SmartThrottle
                                    SmartThrottle.reportRetryAfter(host, e.retryAfterSeconds ?: 0L)
                                    Log.d("OCE", "fetchDocument 429 on $attemptUrl, trying next variant/host")
                                    continue
                                }
                                e.code == 404 || e.code in 500..599 -> {
                                    // Geo-block 404 / server error: mirror mungkin berbeda
                                    Log.d("OCE", "fetchDocument HTTP ${e.code} on $attemptUrl, trying next host")
                                    break
                                }
                                else -> throw e
                            }
                        }
                        else -> {
                            val msg = e.message ?: ""
                            if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
                            if (CLOUDFLARE_HTTP.containsMatchIn(msg)) {
                                SmartThrottle.reportFailure(host)
                                Log.d("OCE", "fetchDocument CF/403 on $attemptUrl (UA=$ua), trying next variant/host")
                                continue
                            }
                            break
                        }
                    }
                }
            }
            break
        }
        if (hostFailed && host.isNotBlank()) HostCircuitBreaker.reportFailure(host)
    }

    throw lastError ?: Exception("All mirrors failed for $url")
}

private fun googleReferer(config: ProviderConfig): String? =
    if (config.googleReferer) "https://www.google.com/" else null

/**
 * Solver Cloudflare via WebView (pola resmi Cloudstream3 `CloudflareKiller`).
 * Saat halaman kena Managed Challenge / Turnstile (403 CF), WebView dimuat
 * untuk menjalankan JS challenge hingga Cloudflare me-set `cf_clearance`.
 * Cookie hasil disimpan ke [HostCookieJar] dan UA WebView dicatat per-host
 * agar request berikutnya memakai UA yang sama dengan sesi solve (cf_clearance
 * terikat ke UA).
 */
object WebViewCloudflareSolver {
    private val solvedUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun userAgentFor(host: String): String? = solvedUserAgents[host]

    fun isSolved(host: String): Boolean = solvedUserAgents.containsKey(host)

    /**
     * Buka URL di WebView, jalankan challenge CF, lalu baca cookie dari
     * Android CookieManager (dishare dgn WebView). Kembalikan true jika
     * berhasil mendapat cf_clearance untuk host.
     */
    suspend fun trySolve(url: String, referer: String? = null): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val cookieManager = runCatching {
            android.webkit.CookieManager.getInstance()
        }.getOrNull() ?: return false
        val resolver = WebViewResolver(
            // Tidak pernah exit berdasarkan URL - exit via requestCallBack
            interceptUrl = Regex(".^"),
            userAgent = null,
            useOkhttp = false,
            // Match semua request supaya requestCallBack dipanggil tiap navigasi
            additionalUrls = listOf(Regex(".")),
            timeout = 30_000L
        )
        return runCatching {
            var solved = false
            resolver.resolveUsingWebView(url, referer = referer) { _ ->
                val cookie = cookieManager.getCookie(url)
                if (cookie != null && cookie.contains("cf_clearance")) {
                    HostCookieJar.update(url, parseCookieMap(cookie))
                    WebViewResolver.webViewUserAgent?.let { solvedUserAgents[host] = it }
                    solved = true
                    true // true = destroy WebView segera
                } else false
            }
            solved
        }.getOrDefault(false)
    }

    private fun parseCookieMap(cookie: String): Map<String, String> {
        return cookie.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap().filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    fun reset() = solvedUserAgents.clear()
}

/**
 * Cookie jar per-host in-memory (ringan). Mengumpulkan `Set-Cookie` dari
 * respon dan mengirimnya kembali pada request berikutnya ke host yang sama.
 * Inspirasi: `user_data_dir` / session Scrapling, tapi tanpa persistensi disk.
 */
object HostCookieJar {
    private val jars = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun getFor(url: String): Map<String, String> {
        val host = runCatching { URI(url).host }.getOrNull() ?: return emptyMap()
        return jars[host] ?: emptyMap()
    }

    fun update(url: String, setCookies: Map<String, String>) {
        if (setCookies.isEmpty()) return
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        jars.compute(host) { _, prev -> (prev ?: emptyMap()) + setCookies }
    }

    fun clear() = jars.clear()
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
