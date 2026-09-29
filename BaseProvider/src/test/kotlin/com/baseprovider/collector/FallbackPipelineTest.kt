package com.baseprovider.collector

import com.baseprovider.config.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FallbackPipelineTest {

    private val pipeline = FallbackPipeline(
        ProviderConfig(id = "TestProvider")
    )

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `embed code iframe huruf kecil ter-decode ke src`() = runBlocking {
        val raw = b64("""<iframe src="https://dood.to/e/abc123" width="100%"></iframe>""")
        assertEquals("https://dood.to/e/abc123", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `embed code iframe huruf kapital ter-decode ke src`() = runBlocking {
        val raw = b64(
            "<IFRAME SRC=\"https://morencius.com/embed/gjyusjb6sjrd\" " +
                "FRAMEBORDER=0 WIDTH=640 HEIGHT=360 allowfullscreen></IFRAME>"
        )
        assertEquals("https://morencius.com/embed/gjyusjb6sjrd", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `embed code iframe kapital di dalam div tetap ter-decode`() = runBlocking {
        val raw = b64(
            "<div style=\"position:relative\">" +
                "<IFRAME SRC=\"https://rubyvidhub.com/embed-mddp6uzp7c7u.html\" SCROLLING=NO></div>"
        )
        assertEquals("https://rubyvidhub.com/embed-mddp6uzp7c7u.html", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `URL biasa diteruskan tanpa perubahan`() = runBlocking {
        assertEquals("https://ok.ru/videoembed/1", pipeline.decodeRawLink("https://ok.ru/videoembed/1"))
    }

    @Test
    fun `base64 non-iframe yang tidak dikenali menghasilkan string kosong`() = runBlocking {
        // Harus kosong agar FallbackPipeline mencatat INVALID_URL, bukan exception.
        assertEquals("", pipeline.decodeRawLink(b64("dnd+fxdp5037wmt=")))
    }

    @Test
    fun `base64 html tanpa iframe menghasilkan string kosong`() = runBlocking {
        assertEquals("", pipeline.decodeRawLink(b64("<div>no iframe here</div>")))
    }

    // ── iframe HTML mentah (tanpa base64) ──

    @Test
    fun `iframe html mentah ter-unwrap ke src`() = runBlocking {
        val raw = """<iframe width="560" height="315" src="//ok.ru/videoembed/10091720346164?nochat=1" frameborder="0" allowfullscreen></iframe>"""
        assertEquals("//ok.ru/videoembed/10091720346164?nochat=1", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `iframe html mentah dengan huruf kapital ter-unwrap`() = runBlocking {
        val raw = """<IFRAME SRC="https://morencius.com/embed/gjyusjb6sjrd" WIDTH=640></IFRAME>"""
        assertEquals("https://morencius.com/embed/gjyusjb6sjrd", pipeline.decodeRawLink(raw))
    }

    // ── isUnusableCandidate ──

    private val page = "https://anichin.moe/lord-of-the-ancient-god-grave-episode-01-subtitle-indonesia/"

    @Test
    fun `token sampah ditolak`() {
        assertTrue(pipeline.isUnusableCandidate("all_comment", "$page/all_comment", page))
    }

    @Test
    fun `path relatif yang menunjuk halaman sama ditolak`() {
        // Gnomon 46.151.28.185: /fall-2-deadpoint-2026/?player=3 -> halaman itself
        val cur = "http://46.151.28.185/fall-2-deadpoint-2026/"
        assertTrue(
            pipeline.isUnusableCandidate(
                "/fall-2-deadpoint-2026/?player=3",
                "http://46.151.28.185/fall-2-deadpoint-2026/?player=3",
                cur
            )
        )
    }

    @Test
    fun `kandidat embed asli tetap diterima`() {
        assertFalse(
            pipeline.isUnusableCandidate(
                "https://dood.to/e/abc123", "https://dood.to/e/abc123", page
            )
        )
    }

    @Test
    fun `path relatif ke halaman lain tetap diterima`() {
        assertFalse(
            pipeline.isUnusableCandidate(
                "/embed/xyz", "https://anichin.moe/embed/xyz", page
            )
        )
    }

    @Test
    fun `kandidat kosong atau resolved kosong ditolak`() {
        assertTrue(pipeline.isUnusableCandidate("", "", page))
        assertTrue(pipeline.isUnusableCandidate("https://ok.ru/v/1", "", page))
    }

    @Test
    fun `kandidat sampah diproses tanpa jaringan dan menghasilkan 0 video`() = runBlocking {
        // Regression stream-in: kandidat junk (token "all_comment") harus
        // berhenti di guard isUnusableCandidate SEBELUM menyentuh jaringan,
        // dan tidak memproduksi link apa pun — tidak ada budget wall-clock
        // yang menutupinya lagi, jadi guard ini adalah satu-satunya benteng.
        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        pipeline.processLink(
            "all_comment", "label", page,
            subtitleCallback = {},
            wrappedCallback = { delivered.incrementAndGet() }
        )
        assertEquals(0, delivered.get())
    }

    @Test
    fun `currentUrl kosong tidak menyebabkan penolakan`() {
        assertFalse(
            pipeline.isUnusableCandidate("https://ok.ru/v/1", "https://ok.ru/v/1", "")
        )
    }

    // ── Regression: embed base64 hidup dibuang sebagai INVALID_URL ──

    @Test
    fun `embed code base64 yang decode ke URL embed tidak dibuang`() {
        // Anichin "Ancient God Sovereign" ep3: keenam embed hidup (play
        // .abyssplayer.com, ok.ru, morencius, rubyvidhub, turbovidhls,
        // anichin.rpmvid) dibuang sebagai INVALID_URL. Bentuk base64-nya
        // kebetulan tanpa '/', '.', '?', '#', sehingga cek token sampah
        // salah mengklasifikasinya — padahal decodeRawLink sudah mengembalikan
        // URL yang benar.
        val cases = listOf(
            "https://play.abyssplayer.com/DeFFLEWIv",
            "https://ok.ru/videoembed/15330080852658",
            "https://morencius.com/embed/gjyusjb6sjrd",
            "https://rubyvidhub.com/embed-mddp6uzp7c7u.html",
            "https://turbovidhls.com/t/6a6be5ac87d74",
        )
        for (url in cases) {
            val raw = b64("""<iframe src="$url" width="100%"></iframe>""")
            val resolved = runBlocking { pipeline.decodeRawLink(raw) }
            assertEquals(url, resolved)
            assertFalse("harus lolos guard: $url",
                pipeline.isUnusableCandidate(raw, resolved, page))
        }
    }

    @Test
    fun `base64 yang decode ke halaman sama tetap ditolak`() {
        val raw = b64("""<iframe src="${page}"></iframe>""")
        val resolved = runBlocking { pipeline.decodeRawLink(raw) }
        assertTrue(pipeline.isUnusableCandidate(raw, resolved, page))
    }

    @Test
    fun `base64 yang tidak menghasilkan URL tetap ditolak`() {
        // Tidak ada URL absolut hasil decode, jadi guard harus tetap menyaring.
        val raw = b64("dnd+fxdp5037wmt=")
        val resolved = runBlocking { pipeline.decodeRawLink(raw) }
        assertEquals("", resolved)
        assertTrue(pipeline.isUnusableCandidate(raw, resolved, page))
    }

    @Test
    fun `token sampah non-base64 tetap ditolak setelah diag guard`() {
        assertTrue(pipeline.isUnusableCandidate("all_comment", "$page/all_comment", page))
    }
}
