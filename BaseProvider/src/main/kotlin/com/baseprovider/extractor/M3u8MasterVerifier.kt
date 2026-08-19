package com.baseprovider.extractor

import com.baseprovider.cache.ExpiringCache
import com.lagradost.cloudstream3.app

/**
 * Verifikasi otomatis master playlist HLS sebelum dikirim ke player.
 *
 * Problem nyata: server terkadang menerbitkan master dengan variant yang
 * TIDAK memiliki URI (mis. `#EXT-X-STREAM-INF` diikuti baris kosong). Media3
 * menafsirkan baris berikutnya (termasuk blank) sebagai URI → resolve ke
 * master itu sendiri → ExoPlayer fetch master sebagai media playlist →
 * rekursi → `ERROR_CODE_PARSING_MANIFEST_MALFORMED` (3002) yang intermitten
 * dan kadang force-close. Server-side tidak bisa diubah, jadi plugin yang
 * harus menyaringnya.
 *
 * Aturan deliver (otomatis, tanpa konfigurasi manual):
 *  - Master bersih (semua variant punya URI) → Verdict.Clean → caller kirim
 *    master as-is (ABR tetap berjalan, perilaku lama dipertahankan).
 *  - Ada variant tanpa URI / self-reference → Verdict.Valid berisi variant
 *    valid saja (yang malformed dibuang; variant lain tetap ada).
 *  - Semua variant malformed / tidak ada STREAM-INF → Verdict.AllMalformed
 *    → caller TIDAK mengirim apa pun (link rusak tidak sampai ke player).
 *  - Fetch master gagal → Verdict.Clean (fallback aman ke perilaku lama).
 *
 * Hasil di-cache per master URL (TTL 30 menit) sehingga link episode yang
 * sama / pengulangan load tidak memicu fetch master ulang.
 */
object M3u8MasterVerifier {

    data class MasterVariant(
        val url: String?,
        val bandwidth: Long,
        val height: Int?
    )

    sealed class Verdict {
        /** Master bersih / bukan master playlist / gagal fetch → deliver as-is. */
        data object Clean : Verdict()

        /** Ada variant malformed → deliver list (resolvedUrl, height) saja. */
        data class Valid(val variants: List<Pair<String, Int?>>) : Verdict()

        /** Semua variant malformed → jangan deliver apa pun. */
        data object AllMalformed : Verdict()
    }

    private val BANDWIDTH_RE = Regex("""BANDWIDTH=(\d+)""")
    private val RESOLUTION_RE = Regex("""RESOLUTION=\d+x(\d+)""")
    private const val FETCH_TIMEOUT_MS = 8000L
    private const val CACHE_TTL_MS = 30 * 60_000L

    private val cache = ExpiringCache<Verdict>(CACHE_TTL_MS, maxSize = 200)

    /**
     * Parse baris #EXT-X-STREAM-INF + URI baris berikutnya. Variant dianggap
     * malformed jika baris berikutnya blank / berupa tag / EOF (tanpa URI).
     * Pure & testable.
     */
    internal fun parseVariants(masterText: String): List<MasterVariant> {
        val variants = mutableListOf<MasterVariant>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = BANDWIDTH_RE.find(line)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val height = RESOLUTION_RE.find(line)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val uriLine = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                val malformed = uriLine.isBlank() || uriLine.startsWith("#")
                variants.add(
                    MasterVariant(
                        url = if (malformed) null else uriLine,
                        bandwidth = bandwidth,
                        height = height
                    )
                )
                i += 2
            } else {
                i++
            }
        }
        return variants
    }

    /**
     * Tentukan Verdict dari hasil parse (pure & testable).
     */
    internal fun classify(masterUrl: String, parsed: List<MasterVariant>): Verdict {
        if (parsed.isEmpty()) {
            // Bukan master playlist (media playlist langsung / bukan HLS).
            return Verdict.Clean
        }
        val valid = parsed.mapNotNull { v ->
            if (v.url == null) return@mapNotNull null
            val resolved = AdaptiveQualityPicker.resolveUrl(masterUrl, v.url)
            // Self-reference: resolve ke master itu sendiri → buang.
            if (resolved == masterUrl || resolved == masterUrl.trimEnd('/')) {
                null
            } else {
                resolved to v.height
            }
        }
        return when {
            valid.isEmpty() -> Verdict.AllMalformed
            valid.size < parsed.size -> Verdict.Valid(valid)
            else -> Verdict.Clean
        }
    }

    /**
     * Verifikasi master lalu kembalikan Verdict. Dipanggil per master URL;
     * hasil di-cache.
     */
    suspend fun verify(
        masterUrl: String,
        referer: String?,
        headers: Map<String, String>
    ): Verdict {
        cache.get(masterUrl)?.let { return it }
        val verdict = try {
            val text = app.get(
                masterUrl,
                referer = referer,
                headers = headers,
                timeout = FETCH_TIMEOUT_MS
            ).text
            classify(masterUrl, parseVariants(text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Gagal fetch: jangan rusakkan perilaku lama, deliver master as-is.
            Verdict.Clean
        }
        cache.put(masterUrl, verdict)
        return verdict
    }
}
