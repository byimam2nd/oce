package com.baseprovider.core

import com.baseprovider.config.ProviderConfig
import java.net.URI

/**
 * Utility adaptive path fallback (config-driven, tanpa IO) supaya mudah diuji:
 *  - deteksi off-host utk row [ProviderConfig.mainPageLists] — saat sebuah row
 *    (mis. /horror/) redirect keluar dari host milik provider, dipakai row
 *    lokal pengganti dari `mainPageListFallbacks` (semua nilai lama tetap
 *    dipertahankan; fallback HANYA menambah, tidak menghapus).
 *  - template builder URL search utk `searchPathPattern` dan fallback-nya.
 */
object AdaptiveRouter {

    fun hostOf(url: String?): String? =
        if (url.isNullOrBlank()) null
        else runCatching { URI(url).host }.getOrNull()

    fun normalizeHost(host: String?): String =
        host?.lowercase()?.removePrefix("www.") ?: ""

    /** Host-host milik provider (main, series, search, mirror). */
    fun allowedHosts(config: ProviderConfig): Set<String> =
        (listOfNotNull(config.mainUrl, config.seriesUrl, config.searchUrl) +
            config.mirrorUrls)
            .mapNotNull { normalizeHost(hostOf(it)) }
            .filter { it.isNotEmpty() }
            .toSet()

    fun isOffHost(url: String?, allowedHosts: Set<String>): Boolean {
        val host = normalizeHost(hostOf(url))
        return host.isNotEmpty() && host !in allowedHosts
    }

    /**
     * Fallback row utama saat URL final keluar dari host provider.
     * Mengembalikan [fallbacks] entry utk [data] bila off-host; null bila
     * request dalam host sendiri atau tidak ada mapping.
     */
    fun mainPageFallback(
        data: String,
        finalUrl: String?,
        allowedHosts: Set<String>,
        fallbacks: Map<String, String>
    ): String? {
        if (!isOffHost(finalUrl, allowedHosts)) return null
        return fallbacks[data]?.takeIf { it.isNotBlank() }
    }

    /**
     * Bangun URL search dari template ({baseUrl}/{query}/{page}).
     * Bisa dipakai utk pattern utama maupun fallback.
     */
    fun buildSearchUrl(
        pattern: String,
        baseUrl: String,
        query: String,
        page: Int
    ): String = pattern.replace("{baseUrl}", baseUrl)
        .replace("{query}", query)
        .replace("{page}", page.toString())
}