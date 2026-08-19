package com.baseprovider.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Extractor dengan helper request HTTP seragam (GET / POST form / POST JSON).
 *
 * TANPA cache hasil fetch — selaras aturan "no-cache extractor": extractor
 * selalu fetch ulang dari website. Hasil decrypt/embed bisa mati dalam
 * hitungan detik–menit (mis. CDN sssrr.org AbyssPlayer), jadi menyimpan
 * response di cache berisiko menyajikan link basi ke player.
 *
 * Nama metode [cachedGetText] / [cachedPostText] / [cachedPostJsonText]
 * dipertahankan agar pemanggil tidak berubah; mereka kini fetch langsung.
 */
abstract class CachedExtractorApi : ExtractorApi() {
    protected suspend fun cachedGetText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String = app.get(url, referer = referer, headers = headers).text

    protected suspend fun cachedPostText(
        url: String,
        data: Map<String, String>? = null,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String = app.post(url, data = data, referer = referer,
        headers = headers).text

    protected suspend fun cachedPostJsonText(
        url: String,
        jsonBody: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String = app.post(url, headers = headers, requestBody = jsonBody
        .toRequestBody("application/json".toMediaType())).text
}