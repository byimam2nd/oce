package com.baseprovider.extractor

import com.baseprovider.collector.FallbackPipeline
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: budget waktu ekstraksi tidak boleh lebih kecil dari rantai kerja
 * yang harus selesai di dalamnya.
 *
 * Context: Anichin mengirim ~9 link per episode. Satu link abyssplayer butuh
 * fetch halaman + POST decrypt ke enc-dec.app + `deliver()` yang mem-probe
 * source secara SEQUENTIAL (`forEach` di ConfigDrivenExtractor.deliver).
 * Dengan PER_LINK 20s dan EXTRACTOR_BLOCK 20s, link yang sebenarnya hidup
 * sering killed sebelum decrypt selesai -> "tidak ada tautan ditemukan".
 */
class ExtractionBudgetTest {

    @Test
    fun `budget per link harus lebih besar dari budget blok extractor`() {
        // Kalau tidak, global/direct/deep-scan tidak pernah sempat jalan:
        // withTimeoutOrNull(EXTRACTOR_BLOCK) consume seluruh budget per link.
        assertTrue(
            "EXTRACTOR_BLOCK_TIMEOUT_MS (${EXTRACTOR_BLOCK_TIMEOUT_MS}) harus < " +
                "PER_LINK_TIMEOUT_MS (${FallbackPipeline.PER_LINK_TIMEOUT_MS})",
            EXTRACTOR_BLOCK_TIMEOUT_MS < FallbackPipeline.PER_LINK_TIMEOUT_MS
        )
    }

    @Test
    fun `budget per link harus cukup untuk decrypt + probe berurutan`() {
        // fetch + decrypt ~8s, lalu `deliver()` mem-probe tiap source satu per
        // satu (ConfigDrivenExtractor.deliver pakai forEach, bukan paralel).
        // Satu link abyssplayer wajar punya >=3 source, jadi budget harus
        // menutup beberapa probe, bukan satu.
        // CATATAN SATUAN: probe timeout NiceHttp dalam DETIK, budget dalam MS.
        val probeMs = AdaptiveHeaderProbe.PROBE_TIMEOUT_SECONDS * 1000L
        val decryptAndFetchMs = 8_000L
        val minimum = decryptAndFetchMs + probeMs * 3
        assertTrue(
            "PER_LINK_TIMEOUT_MS (${FallbackPipeline.PER_LINK_TIMEOUT_MS}) < " +
                "minimum yang dibutuhkan ($minimum = decrypt/fetch + 3 probe)",
            FallbackPipeline.PER_LINK_TIMEOUT_MS >= minimum
        )
    }

    @Test
    fun `fetch master m3u8 harus punya waktu di dalam budget per link`() {
        // createSmartLink memanggil M3u8MasterVerifier di dalam blok per link.
        // Kalau FETCH_TIMEOUT lebih besar dari sisa budget, verifikasi master
        // selalu timeout dan variant valid ikut terbuang.
        assertTrue(
            "M3u8MasterVerifier.FETCH_TIMEOUT_MS (" +
                "${M3u8MasterVerifier.FETCH_TIMEOUT_MS}) harus < " +
                "PER_LINK_TIMEOUT_MS (${FallbackPipeline.PER_LINK_TIMEOUT_MS})",
            M3u8MasterVerifier.FETCH_TIMEOUT_MS < FallbackPipeline.PER_LINK_TIMEOUT_MS
        )
    }
}
