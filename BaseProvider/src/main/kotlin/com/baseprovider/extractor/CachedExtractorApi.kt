package com.baseprovider.extractor

import com.baseprovider.cache.AdaptiveDecryptCache
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Extractor dengan cache decrypt adaptif built-in.
 *
 * Turunkan dari class ini dan gunakan [cachedGetText] / [cachedPostText] /
 * [cachedPostJsonText] untuk request ke API decrypt/embed. TTL adaptif otomatis
 * menyesuaikan stabilitas response (stabil → 6 jam, volatile → 60 detik),
 * sehingga tidak perlu memutuskan manual apakah request layak di-cache.
 *
 * Key cache mencakup METHOD + URL + body + referer sehingga request berbeda
 * tidak saling menimpa — contoh: StreamRuby memakai URL /dl yang sama untuk
 * semua id, dibedakan oleh body (file_code).
 */
abstract class CachedExtractorApi : ExtractorApi() {
    private val decryptCache = AdaptiveDecryptCache()

    protected suspend fun cachedGetText(
        url: String,
        referer: String? = null,
        headers: Map<String, String>? = null
    ): String {
        val key = "GET:$url:${referer ?: ""}"
        return decryptCache.get(key) ?: run {
            val text = app.get(url, referer = referer, headers = headers).text
            decryptCache.put(key, text)
            text
        }
    }

    protected suspend fun cachedPostText(
        url: String,
        data: Map<String, String>? = null,
        referer: String? = null,
        headers: Map<String, String>? = null
    ): String {
        val body = data?.entries?.sortedBy { it.key }
            ?.joinToString(",") { "${it.key}=${it.value}" }.orEmpty()
        val key = "POST:$url:$body:${referer ?: ""}"
        return decryptCache.get(key) ?: run {
            val text = app.post(url, data = data, referer = referer,
                headers = headers).text
            decryptCache.put(key, text)
            text
        }
    }

    protected suspend fun cachedPostJsonText(
        url: String,
        jsonBody: String,
        referer: String? = null,
        headers: Map<String, String>? = null
    ): String {
        val key = "POST:$url:${jsonBody.hashCode()}:${referer ?: ""}"
        return decryptCache.get(key) ?: run {
            val text = app.post(url, headers = headers, requestBody = jsonBody
                .toRequestBody("application/json".toMediaType())).text
            decryptCache.put(key, text)
            text
        }
    }
}
