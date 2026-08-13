package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI

/**
 * Probe otomatis pemilihan header video per-host (konsep adaptive, tanpa
 * test manual per extractor). Untuk host pertama kali, uji 2 varian:
 *  - BARE   : UA+Accept saja, tanpa referer (pola OkRu yang terbukti anti-throttle)
 *  - REFERER: UA+Accept + referer (hint atau origin video)
 * Pilih varian yang valid (2xx/3xx) dan paling cepat. Hasil di-cache per-host
 * sehingga host berikutnya tidak di-probe ulang dalam satu sesi.
 */
object AdaptiveHeaderProbe {
    enum class Mode { BARE, REFERER }

    data class Decision(val mode: Mode, val referer: String?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Decision>>()
    private const val TTL_MS = 60 * 60_000L
    private const val PROBE_TIMEOUT = 4000L

    suspend fun resolve(url: String, refererHint: String?): Decision {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Decision(Mode.BARE, null)
        cache[host]?.let { (ts, d) ->
            if (System.currentTimeMillis() - ts < TTL_MS) return d
        }
        val decision = probe(url, refererHint)
        cache[host] = System.currentTimeMillis() to decision
        return decision
    }

    fun reset() = cache.clear()

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