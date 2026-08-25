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
 *  - RAW          : UA non-browser (okhttp), tanpa referer — untuk host yang
 *                   justru MEMBLOKIR UA browser dari klien non-browser
 *                   (kasus rumble.com/hls-vod: CF 403 Chrome/Firefox, 200 okhttp)
 *  - EXPLICIT     : headers asli yang diset extractor (jika bukan minimal)
 * Combo valid pertama (2xx/3xx) yang selesai langsung MENANG — sisanya
 * di-cancel (uji berjalan serentak, tidak menunggu yang lambat). Probe
 * membaca body sungguhan (Range 1MB) sehingga pemenang = combo dengan
 * throughput terbaik untuk streaming, bukan sekadar latency.
 *
 * Probe BLOCKING: link TIDAK dikirim ke player sebelum lolos test. Jika semua
 * combo ditolak server via HTTP non-2xx/3xx, Decision.valid=false dan caller
 * harus SKIP link (jangan kirim link rusak yang berakhir error 2004 di player).
 * Jika kegagalan terjadi di level jaringan (internet mati / TLS reset /
 * timeout), link TETAP dikirim (mode BARE, networkBlocked=true) — network
 * error bukan bukti link rusak, dan skip hanya membuat extractor mengembalikan
 * "0 link" saat jaringan bermasalah.
 *
 * HASIL TIDAK DI-CACHE: setiap extractor dijalankan, host selalu di-probe
 * ulang dari website. Tidak ada cache di sisi plugin agar keputusan header
 * tidak basi (link yang sempat berubah perilaku header tetap ter-cover).
 * Single-flight per-host tetap dipakai: pemanggil bersamaan menunggu hasil
 * probe yang sama, bukan memulai probe ganda.
 */
object AdaptiveHeaderProbe {
    enum class Mode { BARE, REFERER, ORIGIN, BROWSER_LIKE, RAW, EXPLICIT }

    data class Decision(
        val mode: Mode,
        val referer: String?,
        val headers: Map<String, String>,
        val valid: Boolean = true,
        // true = probe gagal di level jaringan (internet mati/TLS reset/timeout),
        // link tetap dikirim BARE (keputusan tidak basi karena tidak di-cache).
        val networkBlocked: Boolean = false,
        // Body hasil fetch pemenang (hanya diisi pemanggil inisiator saat
        // captureBody=true, mis. master m3u8). Waiter single-flight menerima
        // null agar tidak memakai body URL milik pemanggil lain.
        val capturedBody: String? = null,
        // true = body terpotong di PROBE_READ_BYTES (master >1MB sangat
        // langka) -> caller harus fetch penuh untuk verifikasi.
        val bodyTruncated: Boolean = false
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
        data class Ok(val ms: Long, val bytesRead: Long = 0L,
                      val captured: Captured? = null) : ProbeResult()
        data object HttpReject : ProbeResult()
        data object NetworkError : ProbeResult()
    }

    /** Body pemenang + penanda terpotong di batas baca probe. */
    private data class Captured(val text: String, val truncated: Boolean)

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
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

    /**
     * UA non-browser: sebagian CDN (kasus rumble.com/hls-vod, 2026-08)
     * mem-blokir UA browser yang datang dari klien non-browser (deteksi
     * TLS mismatch), tapi meloloskan UA generik/okhttp. Combo RAW menguji
     * jalur ini agar probe benar-benar adaptif dua arah (A-Z): host yang
     * MENUNTUT UA browser tetap ter-cover BARE/BROWSER_LIKE, host yang
     * MEMBLOKIRnya tetap ter-cover RAW. Nilai eksplisit "okhttp/..." penting:
     * kalau header UA dihapus, NiceHttp menyuntikkan default global
     * (browser-like) sehingga combo jadi tidak berbeda dari BARE.
     */
    private val rawClientHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to "okhttp/4.12.0"
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
            Combo(Mode.BROWSER_LIKE, referer, browserLikeHeaders + originHeader),
            // RAW: tanpa referer + UA non-browser (anti CF-bot-rule untuk
            // host yang memblokir UA browser non-browser).
            Combo(Mode.RAW, null, rawClientHeaders)
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
        explicitHeaders: Map<String, String>? = null,
        captureBody: Boolean = false
    ): Decision {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Decision(Mode.BARE, null, minimalHeaders, valid = false)
        // Single-flight: satu probe per host, pemanggil lain menunggu hasil yang sama.
        while (true) {
            inFlight[host]?.let { deferred ->
                // Waiter: hasil probe milik URL lain — body tidak ikut dipakai.
                return deferred.await().copy(capturedBody = null, bodyTruncated = false)
            }
            val deferred = CompletableDeferred<Decision>()
            if (inFlight.putIfAbsent(host, deferred) == null) {
                try {
                    val decision = try {
                        probe(url, refererHint, explicitHeaders, captureBody)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Decision(Mode.BARE, null, minimalHeaders, valid = false)
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
        explicitHeaders: Map<String, String>?,
        captureBody: Boolean
    ): Decision = coroutineScope {
        val combos = buildCombos(url, refererHint, explicitHeaders)
        val jobs = combos.map { combo ->
            async { combo to probeOnce(url, combo, captureBody) }
        }.toMutableList()
        val host = runCatching { URI(url).host }.getOrNull() ?: url.take(60)
        var anyNetworkError = false
        data class Cand(val combo: Combo, val res: ProbeResult.Ok) {
            // KB/s sejati: bytes -> KB lalu bagi durasi detik
            fun kbps(): Double =
                (res.bytesRead / 1024.0) * 1000.0 / res.ms.coerceAtLeast(1)
        }
        val oks = mutableListOf<Cand>()
        while (jobs.isNotEmpty()) {
            val done = select {
                jobs.forEach { job -> job.onAwait { job } }
            }
            jobs.remove(done)
            val (combo, result) = done.await()
            when (result) {
                is ProbeResult.Ok -> {
                    // Kumpulkan SEMUA kandidat valid: pemenang dipilih dari
                    // THROUGHPUT terbaik (bytes/ms), bukan yang pertama selesai
                    // — kasus rumble.com: mode cepat merespons tapi kena throttle
                    // saat streaming segmen (buffering lambat), mode lain lebih
                    // lancar. Pemilihan by-throughput menjawab keluhan itu.
                    Log.d("AdaptiveProbe",
                        "$host: ${combo.mode} OK ${result.ms}ms " +
                            "${result.bytesRead}B")
                    oks.add(Cand(combo, result))
                }
                is ProbeResult.HttpReject ->
                    Log.d("AdaptiveProbe", "$host: ${combo.mode} HTTP-reject")
                ProbeResult.NetworkError -> {
                    anyNetworkError = true
                    Log.d("AdaptiveProbe", "$host: ${combo.mode} network-error")
                }
            }
        }
        if (oks.isNotEmpty()) {
            var win = oks.maxWithOrNull(
                compareBy({ it.kbps() }, { -it.res.ms })
            )!!
            // Tie-breaker utk sampel mikro (<10KB): throughput tak terukur
            // secara bermakna (playlist ~1KB). Prefer BROWSER_LIKE sebagai
            // paling tahan deteksi HTTP-layer saat streaming segmen panjang.
            if (win.res.bytesRead < 10_000L) {
                oks.firstOrNull { it.combo.mode == Mode.BROWSER_LIKE }?.let {
                    if (it !== win) {
                        com.baseprovider.log.logDebug("AdaptiveProbe",
                            "$host: sampel mikro (${win.res.bytesRead}B) -> " +
                                "override ke BROWSER_LIKE")
                        win = it
                    }
                }
            }
            oks.filter { it !== win }.forEach {
                com.baseprovider.log.logDebug("AdaptiveProbe",
                    "$host: kalah mode=${it.combo.mode} " +
                        "${"%.0f".format(it.kbps())} KB/s")
            }
            com.baseprovider.log.logSuccess("AdaptiveProbe",
                "$host: PROBE WIN mode=${win.combo.mode} ${win.res.ms}ms " +
                    "%.0f KB/s (${win.res.bytesRead}B) url=$url"
                        .format(win.kbps()),
                url = url)
            return@coroutineScope Decision(
                win.combo.mode, win.combo.referer, win.combo.headers, valid = true,
                capturedBody = win.res.captured?.text,
                bodyTruncated = win.res.captured?.truncated ?: false
            )
        }
        if (anyNetworkError) {
            // Ada kegagalan jaringan (bukan HTTP reject): kirim BARE tetap
            // valid. Saat internet pulih, extractor berikutnya memprobe ulang.
            return@coroutineScope Decision(
                Mode.BARE, null, minimalHeaders,
                valid = true, networkBlocked = true
            )
        }
        // Semua combo ditolak server via HTTP non-2xx -> link rusak.
        Decision(Mode.BARE, null, minimalHeaders, valid = false)
    }

    private suspend fun probeOnce(url: String, combo: Combo, captureBody: Boolean): ProbeResult =
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
                        if (captureBody) {
                            // Body pemenang disimpan (mis. master m3u8) agar
                            // verifikasi variant bisa pakai fetch yang sama
                            // (P1: probe + verifier jadi satu pass).
                            val out = java.io.ByteArrayOutputStream()
                            var total = 0L
                            while (total < PROBE_READ_BYTES) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                total += n
                            }
                            val text = out.toString(Charsets.UTF_8)
                            if (text.isNotEmpty()) {
                                return ProbeResult.Ok(
                                    System.currentTimeMillis() - start,
                                    total,
                                    Captured(text, total >= PROBE_READ_BYTES)
                                )
                            }
                        } else {
                            var total = 0L
                            while (total < PROBE_READ_BYTES) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                total += n
                            }
                            return ProbeResult.Ok(
                                System.currentTimeMillis() - start, total)
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
