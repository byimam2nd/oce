package com.baseprovider.extractor

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * Fixture diambil dari halaman `embed2.php?link=...` gdriveplayer.to yang
 * diambil live. Isi payload di-encode ulang di test supaya file ini tidak
 * menyimpan base64 25 KB, tapi hasil decode-nya identik dengan produksi.
 */
class GdrivePlayerPayloadTest {

    /** JS hasil decode produksi, dipotong ke bagian yang dipakai extractor. */
    private val decodedJs =
        "jwplayer.key=\"ITWMv7t88JGzI0xPwW8I0+LveiXX9SWbfdmt0ArUSyc=\"," +
            "SKEY=\"gdp_pos_c426b87882b6dbe04e2330cc8bb00d44\",RESUME=true," +
            "COLOR=\"#ff0000\"," +
            "HLS=\"hlsplaylist.php?s=bU1jUkZmNEdYNkZSQjJRVVJUSnlHaGprbEh2VC8ySUhKSGx0NWRacUlUUT0%3D&idhls=b1N5UUZqdks1UnRNcGZrZUJUdG9jQXdRaTE4UDUyMTJtb0NXMVlrdng5eVZPemFob1dTanFmbm1QTE5TVXpUcg%3D%3D.m3u8\"," +
            "SWARM=\"1kSg2cO7dhQhr3w6V_MzfSY4t6Nm8NYd6\",AUTO=false," +
            "MP4BASE=\"\",MP4_720=false;" +
            "function gdpBase(){return {file:HLS,type:\"hls\",primary:\"html5\"}};" +
            "tracks=[{file:SUB,label:\"English\",kind:\"captions\"}];" +
            "SUB=\"subproxy.php?u=aHR0cDovL3N1YnRpdGxlLmdkcml2ZXBsYXllci51cy9zdWJ0aXRsZS9tbWVwMjU2cG9saS5zcnQ&t=d3d4b1233cc8da9db333c02b048dc1a5\";"

    /** Kunci + payload dibuat dari `decodedJs` di atas, lalu XOR balik. */
    private fun buildHtml(js: String, key: String): String {
        val k = key.toByteArray(Charsets.ISO_8859_1)
        val raw = js.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(raw.size) { i -> (raw[i].toInt() xor k[i % k.size].toInt()).toByte() }
        val b64 = Base64.getEncoder().encodeToString(xored)
        return "<script>(function(){var k=\"$key\";var q=atob(\"$b64\");})()</script>"
    }

    @Test
    fun `payload XOR ter-decode kembali ke JS yang sama`() {
        val html = buildHtml(decodedJs, "gdpea01e6e4a4ce98")
        assertEquals(decodedJs, GdrivePlayerPayload.decodeJs(html))
    }

    @Test
    fun `hlsplaylist diambil dan jadi URL absolut`() {
        val html = buildHtml(decodedJs, "gdpea01e6e4a4ce98")
        val hls = GdrivePlayerPayload.hlsUrl(GdrivePlayerPayload.decodeJs(html), "https://gdriveplayer.to")
        assertTrue("harus absolut", hls.startsWith("https://gdriveplayer.to/hlsplaylist.php?"))
        assertTrue("harus minta .m3u8", hls.endsWith(".m3u8"))
    }

    @Test
    fun `subtitle diambil dari subproxy`() {
        val html = buildHtml(decodedJs, "gdpea01e6e4a4ce98")
        val sub = GdrivePlayerPayload.subtitleUrl(GdrivePlayerPayload.decodeJs(html), "https://gdriveplayer.to")
        assertNotNull(sub)
        assertTrue(sub!!.startsWith("https://gdriveplayer.to/subproxy.php?"))
    }

    @Test
    fun `halaman error gdriveplayer tidak menghasilkan link - guard root cause 0 link`() {
        // Halaman yang OCE terima saat ini: 3.4 KB error page tanpa payload.
        val errorPage = """<div class="gdp-err"><div class="gdp-err-box">
            <div class="gdp-err-t">Video cannot be played</div></div></div>"""
        assertEquals("", GdrivePlayerPayload.decodeJs(errorPage))
        assertEquals("", GdrivePlayerPayload.hlsUrl(errorPage, "https://gdriveplayer.to"))
    }

    @Test
    fun `base64 rusak tidak melempar exception`() {
        val html = """<script>var k="abc";atob("!!!bukan-base64!!!")</script>"""
        assertEquals("", GdrivePlayerPayload.decodeJs(html))
    }

    @Test
    fun `kunci tanpa payload tidak melempar exception`() {
        assertEquals("", GdrivePlayerPayload.decodeJs("""<script>var k="gdpea01e6e6";</script>"""))
    }

    @Test
    fun `payload ada tapi tanpa HLS ditolak - guard link sampah`() {
        val js = "var COLOR=\"#ff0000\";var SKEY=\"x\";"
        assertEquals("", GdrivePlayerPayload.decodeJs(buildHtml(js, "k123456")))
    }

    @Test
    fun `HLS absolut tidak didouble-slash saat digabung dengan mainUrl`() {
        val html = buildHtml(decodedJs, "gdpea01e6e4a4ce98")
        val hls = GdrivePlayerPayload.hlsUrl(GdrivePlayerPayload.decodeJs(html), "https://gdriveplayer.to")
        assertFalse("tidak boleh ada // ganda", hls.contains("to.to/"))
        assertFalse(hls.contains("//hlsplaylist"))
    }
}
