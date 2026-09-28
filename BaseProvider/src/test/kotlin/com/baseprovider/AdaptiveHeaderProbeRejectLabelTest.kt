package com.baseprovider

import com.baseprovider.extractor.AdaptiveHeaderProbe
import com.baseprovider.extractor.AdaptiveHeaderProbe.Decision
import com.baseprovider.extractor.AdaptiveHeaderProbe.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `rejectLabel` adalah satu-satunya tempat status HTTP probe berubah jadi teks
 * yang terbaca di log produksi. Bugs lama ("link ditolak, tapi tidak tahu
 * kenapa") selalu kembali ke sini, jadi formatnya dikunci dengan test.
 */
class AdaptiveHeaderProbeRejectLabelTest {

    private fun decision(
        statuses: List<Int> = emptyList(),
        networkError: String? = null
    ) = Decision(
        mode = Mode.BARE, referer = null, headers = emptyMap(),
        valid = false, rejectedStatuses = statuses, networkError = networkError
    )

    @Test
    fun `403 single status tampil sebagai HTTP 403`() {
        assertEquals("HTTP 403", AdaptiveHeaderProbe.rejectLabel(decision(listOf(403))))
    }

    @Test
    fun `beberapa status digabung dengan slash agar bisa dibedakan`() {
        assertEquals(
            "HTTP 403/404",
            AdaptiveHeaderProbe.rejectLabel(decision(listOf(403, 404)))
        )
    }

    @Test
    fun `network error tampil sebagai kelas exception, bukan HTTP`() {
        assertEquals(
            "network: SocketTimeoutException: timeout",
            AdaptiveHeaderProbe.rejectLabel(decision(
                networkError = "SocketTimeoutException: timeout"))
        )
    }

    @Test
    fun `HTTP menang dari network error saat keduanya ada`() {
        assertEquals(
            "HTTP 403",
            AdaptiveHeaderProbe.rejectLabel(decision(
                statuses = listOf(403), networkError = "timeout"))
        )
    }

    @Test
    fun `tanpa status maupun network error tidak boleh crash atau kosong`() {
        assertEquals("no response", AdaptiveHeaderProbe.rejectLabel(decision()))
    }
}
