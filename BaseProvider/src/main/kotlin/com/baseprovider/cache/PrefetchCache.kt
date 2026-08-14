package com.baseprovider.cache

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache prefetch untuk auto-warm episode page dan extractor links.
 *
 * Level 1 - LoadResponse per URL detail page: saat home list tampil, episode
 * page item di-fetch di background dan di-simpan di sini. Ketika user membuka
 * episode page, `load(url)` langsung return dari cache → instan. "Update check"
 * berbasis TTL: selama cache fresh, dipakai terus; setelah TTL lewat, di-fetch
 * ulang (dianggap ada update).
 *
 * Level 2 - Extractor links per episode URL: saat episode page dibuka, links
 * seluruh episode yang muncul di-extract di background. Ketika user play,
 * `loadLinks(data)` langsung return dari cache → player instan.
 *
 * In-flight guard mencegah double-fetch jika prefetch dan pemanggilan user
 * berjalan bersamaan untuk key yang sama: pemanggil kedua menunggu hasil yang
 * pertama, bukan fetch ulang.
 */
class PrefetchCache(
    private val ttlMs: Long,
    private val maxEntries: Int = 128
) {
    private data class Entry<T>(val value: T, val timestamp: Long)
    data class CachedLinks(
        val subtitles: List<SubtitleFile>,
        val links: List<ExtractorLink>
    )

    private val loadCache = ConcurrentHashMap<String, Entry<LoadResponse>>()
    private val linkCache = ConcurrentHashMap<String, Entry<CachedLinks>>()
    private val loadInFlight = ConcurrentHashMap<String, CompletableDeferred<LoadResponse?>>()
    private val linkInFlight = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, CachedLinks>?>>()

    fun getLoad(url: String): LoadResponse? = loadCache[url]?.let { e ->
        if (isFresh(e.timestamp)) e.value else null
    }

    fun putLoad(url: String, response: LoadResponse) {
        evictIfNeeded(loadCache)
        loadCache[url] = Entry(response, now())
    }

    fun isLoadFresh(url: String): Boolean =
        loadCache[url]?.let { isFresh(it.timestamp) } ?: false

    fun getLinks(data: String): CachedLinks? = linkCache[data]?.let { e ->
        if (isFresh(e.timestamp)) e.value else null
    }

    fun putLinks(data: String, cached: CachedLinks) {
        evictIfNeeded(linkCache)
        linkCache[data] = Entry(cached, now())
    }

    fun isLinksFresh(data: String): Boolean =
        linkCache[data]?.let { isFresh(it.timestamp) } ?: false

    /** Apakah ada fetch link in-flight untuk [data]. */
    fun isLinksLoading(data: String): Boolean =
        linkInFlight.containsKey(data)

    /**
     * Tunggu fetch link in-flight untuk [data] sampai [timeoutMs]. Return null
     * jika tidak ada in-flight atau timeout — pemanggil (jalur user-play) lalu
     * jalan dengan hasil parsial sendiri, TIDAK menunggu prefetch penuh.
     */
    suspend fun awaitLinks(data: String, timeoutMs: Long): Pair<Boolean, CachedLinks>? =
        linkInFlight[data]?.let { withTimeoutOrNull(timeoutMs) { it.await() } }

    fun clear() {
        loadCache.clear()
        linkCache.clear()
    }

    /**
     * Return LoadResponse untuk [url]. Jika belum ada / expired, jalankan
     * [loader]. Jika ada fetch lain yang sedang berjalan untuk [url], tunggu
     * hasilnya (in-flight guard) — tidak fetch ulang.
     */
    suspend fun getOrLoad(url: String, loader: suspend () -> LoadResponse): LoadResponse {
        getLoad(url)?.let { return it }
        val deferred = CompletableDeferred<LoadResponse?>()
        val prev = loadInFlight.putIfAbsent(url, deferred)
        if (prev != null) {
            val result = prev.await()
            if (result != null) return result
            return loader().also { putLoad(url, it) }
        }
        try {
            val resp = loader()
            putLoad(url, resp)
            deferred.complete(resp)
            return resp
        } catch (t: Throwable) {
            deferred.complete(null)
            throw t
        } finally {
            loadInFlight.remove(url, deferred)
        }
    }

    /**
     * Return (success, links) untuk [data]. Jika sudah ada di cache, return
     * segera. Jika tidak, jalankan [loader]; pemanggil kedua menunggu hasil
     * pemanggil pertama (in-flight guard). Hasil di-cache hanya jika success.
     */
    suspend fun getOrLoadLinks(
        data: String,
        loader: suspend () -> Pair<Boolean, CachedLinks>
    ): Pair<Boolean, CachedLinks> {
        getLinks(data)?.let { return true to it }
        val deferred = CompletableDeferred<Pair<Boolean, CachedLinks>?>()
        val prev = linkInFlight.putIfAbsent(data, deferred)
        if (prev != null) {
            val res = prev.await()
            if (res != null) return res
            return loader().also { (ok, links) -> if (ok) putLinks(data, links) }
        }
        try {
            val result = loader()
            val (ok, links) = result
            if (ok) putLinks(data, links)
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.complete(null)
            throw t
        } finally {
            linkInFlight.remove(data, deferred)
        }
    }

    private fun isFresh(timestamp: Long) = now() - timestamp < ttlMs

    private fun now() = System.currentTimeMillis()

    private fun <T> evictIfNeeded(map: ConcurrentHashMap<String, Entry<T>>) {
        if (map.size < maxEntries) return
        val oldestKey = map.entries.minByOrNull { it.value.timestamp }?.key
        if (oldestKey != null) map.remove(oldestKey)
    }
}