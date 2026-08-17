package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Probe otomatis pemilihan header video per-host (konsep adaptive, tanpa
 * test manual per extractor). Untuk host pertama kali, uji beberapa combo
 * header secara paralel dan pilih yang valid (2xx/3xx) dan paling cepat:
 *  - BARE        : UA+Accept saja, tanpa referer (pola OkRu yang terbukti anti-throttle)
 *  - REFERER     : UA+Accept + referer (hint atau origin video)
 *  - ORIGIN      : UA+Accept + referer + Origin (CDN yang memvalidasi Origin)
 *  - BROWSER_LIKE: header browser penuh (Accept-Language, Sec-Fetch-*, Origin)
 * Hasil di-cache per-host sehingga host berikutnya tidak di-probe ulang
 * dalam satu sesi.
 *
 * Probe BLOCKING: link TIDAK dikirim ke player sebelum lolos test. Jika semua
 * combo ditolak server via HTTP non-2xx/3xx, Decision.valid=false dan caller
 * harus SKIP link (jangan kirim link rusak yang berakhir error 2004 di player).
 * Jika kegagalan terjadi di level jaringan (internet mati / TLS reset /
 * timeout), link TETAP dikirim (mode BARE, networkBlocked=true) — network
 * error bukan bukti link rusak, dan skip hanya membuat extractor mengembalikan
 * "0 link" saat jaringan bermasalah. Hasil networkBlocked tidak di-cache agar
 * saat internet pulih host di-probe ulang. Single-flight per-host: pemanggil
 * bersamaan menunggu hasil probe yang sama, bukan memulai probe ganda.
 */
object AdaptiveHeaderProbe {
    enum class Mode { BARE, REFERER, ORIGIN, BROWSER_LIKE }

    data class Decision(
        val mode: Mode,
        val referer: String?,
        val headers: Map<String, String>,
        val valid: Boolean = true,
        // true = probe gagal di level jaringan (internet mati/TLS reset/timeout),
        // link tetap dikirim BARE tapi JANGAN di-cache — saat internet pulih,
        // host harus di-probe ulang untuk dapat combo terbaik.
        val networkBlocked: Boolean = false
    )

    private data class Combo(
        val mode: Mode,
        val referer: String?,
        val headers: Map<String, String>
    )

    /**
     * Hasil probe satu combo. Membedakan HTTP reject (server merespons non-2xx,
     * bukti link benar-benar rusak) dari network error (internet mati / TLS
     * reset / timeout — BUKAN bukti link rusak).
     */
    private sealed class ProbeResult {
        data class Ok(val ms: Long) : ProbeResult()
        data object HttpReject : ProbeResult()
        data object NetworkError : ProbeResult()
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, Decision>>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    private const val TTL_MS = 60 * 60_000L
    // NiceHttp timeout dalam DETIK (callTimeout/connectTimeout TimeUnit.SECONDS).
    private const val PROBE_TIMEOUT = 5L

    private val minimalHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to DEFAULT_UA
    )

    private val browserLikeHeaders = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "User-Agent" to DEFAULT_UA
    )

    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        "${u.scheme}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}"
    }.getOrNull()

    private fun buildCombos(url: String, refererHint: String?): List<Combo> {
        val origin = originOf(url)
        val referer = refererHint ?: origin
        val originHeader = origin?.let { mapOf("Origin" to it) } ?: emptyMap()
        return listOf(
            Combo(Mode.BARE, null, minimalHeaders),
            Combo(Mode.REFERER, referer, minimalHeaders),
            Combo(Mode.ORIGIN, referer, minimalHeaders + originHeader),
            Combo(Mode.BROWSER_LIKE, referer, browserLikeHeaders + originHeader)
        )
    }

    suspend fun resolve(url: String, refererHint: String?): Decision {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Decision(Mode.BARE, null, minimalHeaders, valid = false)
        cache[host]?.let { (ts, d) ->
            if (System.currentTimeMillis() - ts < TTL_MS) return d
        }
        // Single-flight: satu probe per host, pemanggil lain menunggu hasil yang sama.
        while (true) {
            inFlight[host]?.let { return it.await() }
            val deferred = CompletableDeferred<Decision>()
            if (inFlight.putIfAbsent(host, deferred) == null) {
                try {
                    val decision = try {
                        probe(url, refererHint)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Decision(Mode.BARE, null, minimalHeaders, valid = false)
                    }
                    // Hanya cache hasil VALID & bukan network-blocked. Hasil
                    // invalid tidak di-cache agar link yang sempat down
                    // (transient) bisa di-probe ulang, bukan mem-blow seluruh
                    // host selama 60 menit. Hasil network-blocked juga tidak
                    // di-cache agar saat internet pulih di-probe ulang.
                    if (decision.valid && !decision.networkBlocked) {
                        cache[host] = System.currentTimeMillis() to decision
                    }
                    deferred.complete(decision)
                } catch (e: Throwable) {
                    // Owner dibatalkan: pastikan waiter tidak hang, lalu teruskan.
                    deferred.complete(Decision(Mode.BARE, null, minimalHeaders, valid = false))
                    throw e
                } finally {
                    inFlight.remove(host)
                }
                return deferred.await()
            }
        }
    }

    fun reset() {
        cache.clear()
        inFlight.clear()
    }

    /**
     * Uji semua combo paralel; pilih yang valid (2xx/3xx) dan paling cepat.
     * Jika tidak ada yang valid:
     *  - semua menolak via HTTP (server merespons non-2xx) -> Decision.invalid
     *    (link benar-benar rusak, harus di-skip).
     *  - ada network error (internet mati / TLS reset / timeout) -> kirim BARE
     *    tetap valid. Network error BUKAN bukti link rusak; kalau di-skip,
     *    extractor menghasilkan 0 link saat internet bermasalah padahal link
     *    sebenarnya bagus (bug "tidak ada tautan ditemukan").
     */
    private suspend fun probe(url: String, refererHint: String?): Decision =
        coroutineScope {
            val combos = buildCombos(url, refererHint)
            val timed = combos.map { combo ->
                async { combo to probeOnce(url, combo) }
            }.map { it.await() }
            val valid = timed.filter { it.second is ProbeResult.Ok }
                .minByOrNull { (it.second as ProbeResult.Ok).ms }
            if (valid != null) {
                val (combo, _) = valid
                return@coroutineScope Decision(combo.mode, combo.referer, combo.headers, valid = true)
            }
            if (timed.any { it.second is ProbeResult.NetworkError }) {
                // Setidaknya ada kegagalan jaringan (bukan HTTP reject): kirim
                // BARE tetap valid, TAPI jangan di-cache (networkBlocked=true).
                // Saat internet pulih, host di-probe ulang untuk combo terbaik.
                return@coroutineScope Decision(
                    Mode.BARE, null, minimalHeaders,
                    valid = true, networkBlocked = true
                )
            }
            // Semua combo ditolak server via HTTP non-2xx -> link rusak.
            Decision(Mode.BARE, null, minimalHeaders, valid = false)
        }

    private suspend fun probeOnce(url: String, combo: Combo): ProbeResult =
        try {
            val start = System.currentTimeMillis()
            val r = app.get(
                url,
                referer = combo.referer,
                headers = combo.headers + mapOf("Range" to "bytes=0-1023"),
                timeout = PROBE_TIMEOUT
            )
            if (r.code in 200..399) ProbeResult.Ok(System.currentTimeMillis() - start)
            else ProbeResult.HttpReject
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.NetworkError
        }
}
