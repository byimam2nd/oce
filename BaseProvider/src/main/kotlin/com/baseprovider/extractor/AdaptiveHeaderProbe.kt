package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Probe otomatis pemilihan header video per-host (konsep adaptive, tanpa
 * test manual per extractor). Untuk host pertama kali, uji beberapa combo
 * header secara paralel:
 *  - BARE         : UA+Accept saja, tanpa referer (pola OkRu anti-throttle)
 *  - REFERER      : UA+Accept + referer (hint atau origin video)
 *  - ORIGIN       : UA+Accept + referer + Origin (CDN yang memvalidasi Origin)
 *  - BROWSER_LIKE : header browser penuh (Accept-Language, Sec-Fetch-*, Origin)
 *  - EXPLICIT     : headers asli yang diset extractor (jika bukan minimal)
 * Combo valid pertama (2xx/3xx) yang selesai langsung MENANG — sisanya
 * di-cancel (uji berjalan serentak, tidak menunggu yang lambat). Probe
 * membaca body sungguhan (Range 1MB) sehingga pemenang = combo dengan
 * throughput terbaik untuk streaming, bukan sekadar latency. Hasil di-cache
 * per-host sehingga host berikutnya tidak di-probe ulang dalam satu sesi.
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
    enum class Mode { BARE, REFERER, ORIGIN, BROWSER_LIKE, EXPLICIT }

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
    // Probe baca body sungguhan hingga batas ini agar pemenang = combo dengan
    // throughput terbaik (bukan sekadar latency). Playlist kecil tetap selesai
    // cepat; direct video mengukur throughput nyata. Server yang tidak support
    // Range dan mengirim file penuh tetap berhenti di batas ini (stream ditutup).
    private const val PROBE_READ_BYTES = 1024 * 1024L
    private const val PROBE_RANGE_END = PROBE_READ_BYTES - 1

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

    private fun buildCombos(
        url: String,
        refererHint: String?,
        explicitHeaders: Map<String, String>?
    ): List<Combo> {
        val origin = originOf(url)
        val referer = refererHint ?: origin
        val originHeader = origin?.let { mapOf("Origin" to it) } ?: emptyMap()
        val combos = mutableListOf(
            Combo(Mode.BARE, null, minimalHeaders),
            Combo(Mode.REFERER, referer, minimalHeaders),
            Combo(Mode.ORIGIN, referer, minimalHeaders + originHeader),
            Combo(Mode.BROWSER_LIKE, referer, browserLikeHeaders + originHeader)
        )
        // EXPLICIT: uji headers asli extractor (jika berbeda dari minimal).
        // Header minimal sudah terwakili oleh combo BARE/REFERER, jadi skip
        // jika extractor tidak menetapkan headers khusus.
        if (explicitHeaders != null && explicitHeaders.isNotEmpty() && explicitHeaders != minimalHeaders) {
            combos.add(Combo(Mode.EXPLICIT, referer, explicitHeaders))
        }
        return combos
    }

    suspend fun resolve(
        url: String,
        refererHint: String?,
        explicitHeaders: Map<String, String>? = null
    ): Decision {
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
                        probe(url, refererHint, explicitHeaders)
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
     * Uji semua combo PARALEL; combo valid (2xx/3xx) PERTAMA yang selesai
     * langsung MENANG, sisanya di-cancel. Terbukti (simulasi + uji HLS nyata)
     * bahwa karena semua combo start bersamaan, yang pertama selesai selalu
     * yang tercepat — jadi tidak ada grace window / tunggu semua. Semua
     * kecepatan yang sempat terukur di-log untuk observabilitas.
     * Jika tidak ada yang valid:
     *  - semua menolak via HTTP (server merespons non-2xx) -> Decision.invalid
     *    (link benar-benar rusak, harus di-skip).
     *  - ada network error (internet mati / TLS reset / timeout) -> kirim BARE
     *    tetap valid. Network error BUKAN bukti link rusak; kalau di-skip,
     *    extractor menghasilkan 0 link saat internet bermasalah padahal link
     *    sebenarnya bagus (bug "tidak ada tautan ditemukan").
     */
    private suspend fun probe(
        url: String,
        refererHint: String?,
        explicitHeaders: Map<String, String>?
    ): Decision = coroutineScope {
        val combos = buildCombos(url, refererHint, explicitHeaders)
        val jobs = combos.map { combo ->
            async { combo to probeOnce(url, combo) }
        }.toMutableList()
        val host = runCatching { URI(url).host }.getOrNull() ?: url.take(60)
        var anyNetworkError = false
        while (jobs.isNotEmpty()) {
            // Ambil hasil combo yang selesai PALING AWAL (via select).
            val done = select {
                jobs.forEach { job -> job.onAwait { job } }
            }
            jobs.remove(done)
            val (combo, result) = done.await()
            when (result) {
                is ProbeResult.Ok -> {
                    // Combo valid pertama menang; cancel probe yang masih jalan.
                    Log.d(
                        "AdaptiveProbe",
                        "$host: WIN ${combo.mode} ${result.ms}ms"
                    )
                    jobs.forEach {
                        it.cancel()
                        Log.d("AdaptiveProbe", "$host: cancelled")
                    }
                    return@coroutineScope Decision(
                        combo.mode, combo.referer, combo.headers, valid = true
                    )
                }
                is ProbeResult.HttpReject ->
                    Log.d("AdaptiveProbe", "$host: ${combo.mode} HTTP-reject")
                ProbeResult.NetworkError -> {
                    anyNetworkError = true
                    Log.d("AdaptiveProbe", "$host: ${combo.mode} network-error")
                }
            }
        }
        if (anyNetworkError) {
            // Ada kegagalan jaringan (bukan HTTP reject): kirim BARE tetap
            // valid, TAPI jangan di-cache (networkBlocked=true). Saat internet
            // pulih, host di-probe ulang untuk combo terbaik.
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
                headers = combo.headers + mapOf("Range" to "bytes=0-$PROBE_RANGE_END"),
                timeout = PROBE_TIMEOUT
            )
            if (r.code !in 200..399) {
                ProbeResult.HttpReject
            } else {
                // Baca body sungguhan hingga batas agar pemenang = combo dengan
                // throughput terbaik (latency + transfer), bukan latency murni.
                // Stream ditutup setelah batas agar server yang tidak support
                // Range (mengirim file penuh) tidak menguras bandwidth.
                try {
                    r.body?.byteStream()?.use { stream ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (total < PROBE_READ_BYTES) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            total += n
                        }
                    }
                } catch (e: Exception) {
                    // Status sudah valid (2xx/3xx); kegagalan baca body diabaikan.
                }
                ProbeResult.Ok(System.currentTimeMillis() - start)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.NetworkError
        }
}
