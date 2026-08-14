package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * Probe TIDAK memblokir link pertama: host baru langsung dapat mode BARE
 * (aman, minimal header), lalu probe dijalankan di background (single-flight)
 * untuk menyempurnakan keputusan link-link berikutnya ke host yang sama.
 */
object AdaptiveHeaderProbe {
    enum class Mode { BARE, REFERER }

    data class Decision(val mode: Mode, val referer: String?)

    private val cache = ConcurrentHashMap<String, Pair<Long, Decision>>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private const val TTL_MS = 60 * 60_000L
    private const val PROBE_TIMEOUT = 4000L

    suspend fun resolve(url: String, refererHint: String?): Decision {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Decision(Mode.BARE, null)
        cache[host]?.let { (ts, d) ->
            if (System.currentTimeMillis() - ts < TTL_MS) return d
        }
        // Host baru: kirim link dengan mode BARE dulu (tidak menunggu probe).
        // Probe dijalankan background sekali saja per host (single-flight).
        if (inFlight.putIfAbsent(host, true) == null) {
            backgroundScope.launch {
                runCatching {
                    val decision = probe(url, refererHint)
                    cache[host] = System.currentTimeMillis() to decision
                }.onFailure { cache[host] = System.currentTimeMillis() to Decision(Mode.BARE, null) }
                inFlight.remove(host)
            }
        }
        return Decision(Mode.BARE, null)
    }

    fun reset() {
        cache.clear()
        inFlight.clear()
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    Decision(Mode.BARE, null)
                refMs != null -> Decision(Mode.REFERER, referer)
                else -> Decision(Mode.BARE, null)
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