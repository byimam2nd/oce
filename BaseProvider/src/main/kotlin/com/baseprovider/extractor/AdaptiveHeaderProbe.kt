package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Probe otomatis pemilihan header video per-host (konsep adaptive, tanpa
 * test manual per extractor). Untuk host pertama kali, uji 2 varian:
 *  - BARE   : UA+Accept saja, tanpa referer (pola OkRu yang terbukti anti-throttle)
 *  - REFERER: UA+Accept + referer (hint atau origin video)
 * Pilih varian yang valid (2xx/3xx) dan paling cepat. Hasil di-cache per-host
 * sehingga host berikutnya tidak di-probe ulang dalam satu sesi.
 *
 * Probe BLOCKING: link TIDAK dikirim ke player sebelum lolos test. Jika kedua
 * varian gagal (non-2xx/3xx), Decision.valid=false dan caller harus SKIP link
 * (jangan kirim link rusak yang berakhir error 2004 di player). Single-flight
 * per-host: pemanggil bersamaan menunggu hasil probe yang sama, bukan memulai
 * probe ganda.
 */
object AdaptiveHeaderProbe {
    enum class Mode { BARE, REFERER }

    data class Decision(val mode: Mode, val referer: String?, val valid: Boolean = true)

    private val cache = ConcurrentHashMap<String, Pair<Long, Decision>>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    private const val TTL_MS = 60 * 60_000L
    private const val PROBE_TIMEOUT = 4000L

    suspend fun resolve(url: String, refererHint: String?): Decision {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Decision(Mode.BARE, null, valid = false)
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
                        Decision(Mode.BARE, null, valid = false)
                    }
                    // Hanya cache hasil VALID. Hasil invalid tidak di-cache agar
                    // link yang sempat down (transient) bisa di-probe ulang,
                    // bukan mem-blow seluruh host selama 60 menit.
                    if (decision.valid) {
                        cache[host] = System.currentTimeMillis() to decision
                    }
                    deferred.complete(decision)
                } catch (e: Throwable) {
                    // Owner dibatalkan: pastikan waiter tidak hang, lalu teruskan.
                    deferred.complete(Decision(Mode.BARE, null, valid = false))
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

    private suspend fun probe(url: String, refererHint: String?): Decision =
        coroutineScope {
            val referer = refererHint ?: runCatching {
                val u = URI(url)
                "${u.scheme}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}"
            }.getOrNull()

            val bare = async { probeOnce(url, null) }
            val withRef = async { probeOnce(url, referer) }
            val bareMs = bare.await()
            val refMs = withRef.await()

            when {
                bareMs != null && (refMs == null || bareMs <= refMs) ->
                    Decision(Mode.BARE, null, valid = true)
                refMs != null -> Decision(Mode.REFERER, referer, valid = true)
                else -> Decision(Mode.BARE, null, valid = false)
            }
        }

    private suspend fun probeOnce(url: String, referer: String?): Long? =
        runCatching {
            val start = System.currentTimeMillis()
            val r = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to DEFAULT_UA,
                    "Accept" to "*/*",
                    "Range" to "bytes=0-1023"
                ),
                timeout = PROBE_TIMEOUT
            )
            if (r.code in 200..399) System.currentTimeMillis() - start else null
        }.getOrNull()
}