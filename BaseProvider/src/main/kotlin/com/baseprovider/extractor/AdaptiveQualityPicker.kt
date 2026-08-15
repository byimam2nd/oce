package com.baseprovider.extractor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.net.URI

/**
 * Pilih varian HLS OkRu yang stabil sesuai kecepatan CDN saat itu.
 *
 * Problem OkRu: CDN (ok6-4.vkuser.net) me-throttle per-request dengan kecepatan
 * fluktuatif. Master m3u8 berisi 6 varian (144p-1080p); kalau ExoPlayer dibiarkan
 * ABR penuh, ia mencoba bitrate tinggi (1080p ~4.7Mbps) padahal CDN cuma sanggup
 * ~0.5-1MB/s -> buffering. Solusi: probe kecepatan turun sekali lalu pilih SATU
 * varian yang bitrate-nya muat dengan safety margin.
 *
 * Purely additive: hanya dipakai oleh OdnoklassnikiExtractor. Gagal di langkah
 * mana pun -> return null -> caller fallback ke master (perilaku lama).
 */
object AdaptiveQualityPicker {

    data class OkRuVariant(
        val bandwidth: Long,
        val height: Int,
        val url: String
    )

    private const val SAFETY = 0.8
    private const val PROBE_TIMEOUT_MS = 8000L
    private const val PROBE_RANGE_END = "262143" // 256KB

    /**
     * Parse master m3u8: blok #EXT-X-STREAM-INF + baris URL relatif di bawahnya.
     * Pure & testable.
     */
    internal fun parseVariants(masterText: String): List<OkRuVariant> {
        val variants = mutableListOf<OkRuVariant>()
        val streamInf = Regex(
            """#EXT-X-STREAM-INF:.*?BANDWIDTH=(\d+).*?(?:RESOLUTION=\d+x(\d+))?"""
        )
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val match = streamInf.find(lines[i])
            if (match != null) {
                val bandwidth = match.groupValues[1].toLongOrNull() ?: 0L
                val height = match.groupValues[2].toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    variants.add(OkRuVariant(bandwidth, height, lines[j].trim()))
                    i = j
                }
            }
            i++
        }
        return variants
    }

    /**
     * Resolve path relatif (bisa "/..." absolute-path ataupun "seg.ts" biasa)
     * terhadap baseUrl. Pure & testable.
     */
    internal fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return path
        val origin = buildString {
            append(uri.scheme ?: "https")
            append("://")
            append(uri.host.orEmpty())
            val port = uri.port
            if (port > 0 && port != 80 && port != 443) append(":$port")
        }
        if (path.startsWith("/")) return origin + path
        // path relatif (mis. nama segmen): join ke direktori baseUrl
        val basePath = uri.path.orEmpty()
        val dir = basePath.substringBeforeLast('/', "")
        return origin + dir + "/" + path
    }

    /**
     * Pilih varian tertinggi yang bitrate-nya muat di kecepatan terukur
     * (dengan safety margin). Kalau tidak ada yang muat, jatuh ke varian
     * terendah. Pure & testable.
     */
    internal fun chooseVariant(
        variants: List<OkRuVariant>,
        measuredBytesPerSec: Double
    ): OkRuVariant? {
        if (variants.isEmpty()) return null
        val capacity = measuredBytesPerSec * 8 * SAFETY
        val sorted = variants.sortedBy { it.bandwidth }
        return sorted.lastOrNull { it.bandwidth <= capacity }
            ?: sorted.minByOrNull { it.bandwidth }
    }

    /** Segmen pertama dari media playlist (baris non-komentar pertama). */
    internal fun firstSegmentOf(playlistText: String): String? =
        playlistText.lines().firstOrNull {
            it.isNotBlank() && !it.startsWith("#")
        }?.trim()

    /**
     * Alur utama: fetch master -> probe kecepatan via 1 segmen (Range 256KB)
     * dari varian tengah -> pilih varian stabil -> return URL media playlist.
     * Return null pada kegagalan apa pun (caller fallback ke master).
     */
    suspend fun selectBestVariant(
        masterUrl: String,
        headers: Map<String, String>
    ): String? {
        return try {
            val masterText = app.get(
                masterUrl, timeout = PROBE_TIMEOUT_MS, headers = headers
            ).text
            val variants = parseVariants(masterText)
            if (variants.isEmpty()) return null

            val sorted = variants.sortedBy { it.bandwidth }
            val probeVariant = sorted[sorted.size / 2]
            val probePlaylistUrl = resolveUrl(masterUrl, probeVariant.url)

            val playlistText = app.get(
                probePlaylistUrl, timeout = PROBE_TIMEOUT_MS, headers = headers
            ).text
            val firstSegment = firstSegmentOf(playlistText) ?: return null

            val segmentUrl = resolveUrl(probePlaylistUrl, firstSegment)
            val start = System.currentTimeMillis()
            val probe = app.get(
                segmentUrl,
                timeout = PROBE_TIMEOUT_MS,
                headers = headers + ("Range" to "bytes=0-$PROBE_RANGE_END")
            )
            if (probe.code != 200 && probe.code != 206) return null
            val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            val bytes = probe.body.bytes().size.toDouble()
            probe.body.close()
            val speed = bytes / (elapsedMs / 1000.0)

            val chosen = chooseVariant(variants, speed) ?: return null
            resolveUrl(masterUrl, chosen.url)
        } catch (e: Exception) {
            Log.d("OkRu", "Adaptive picker failed, fallback ke master: ${e.message}")
            null
        }
    }
}
