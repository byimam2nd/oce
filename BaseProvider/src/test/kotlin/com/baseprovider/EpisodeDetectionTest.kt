package com.baseprovider

import com.baseprovider.model.SelectorResolver
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Lock perilaku [SelectorResolver.detectEpisodeLinks] setelah lapisan
 * adaptif weak-season:
 *  1. Anchor rekomendasi series "/tv/x-season-2-2026/" di halaman film
 *     TIDAK lagi dihitung episode (kasus Dutamovie21 Ludwig).
 *  2. Season URL + label "Eps N" TETAP diterima (perilaku lama utuh).
 *  3. Semua pola kuat (/eps/, -episode-, /ep-) tidak berubah.
 */
class EpisodeDetectionTest {

    private fun detect(html: String, currentUrl: String): List<String> {
        val doc = Jsoup.parse(html, currentUrl)
        return SelectorResolver.detectEpisodeLinks(doc, currentUrl)
            .map { it.attr("href") }
    }

    @Test
    fun `season-only recommendation link is skipped`() {
        val html = """
        <html><body>
          <a href="https://cyber-junkie.com/tv/ludwig-season-2-2026/">Ludwig Season 2 (2026)</a>
          <a href="https://cyber-junkie.com/eps/ludwig-season-2-episode-1/">S2 Eps1</a>
        </body></html>
        """.trimIndent()
        val out = detect(html, "https://cyber-junkie.com/unusual-deal-2025/")
        assertEquals(listOf("https://cyber-junkie.com/eps/ludwig-season-2-episode-1/"), out)
    }

    @Test
    fun `season url with eps label is still accepted`() {
        val html = """
        <html><body>
          <a href="https://x.com/show-season-2/">Eps 3</a>
        </body></html>
        """.trimIndent()
        val out = detect(html, "https://x.com/watch/")
        assertEquals(1, out.size)
    }

    @Test
    fun `strong patterns are unaffected by the new layer`() {
        val html = """
        <html><body>
          <a href="https://anichin.cafe/foo-episode-96-subtitle-indonesia/">96</a>
          <a href="https://anichin.cafe/foo-episode-96-subtitle-indonesia-2/">96</a>
          <a href="https://x.com/eps/bar/">Eps</a>
          <a href="https://y.com/ep/9/">9</a>
        </body></html>
        """.trimIndent()
        val out = detect(html, "https://anichin.cafe/seri/foo/")
        assertEquals(4, out.size)
    }

    @Test
    fun `duplicate same-number different-href entries are both detected`() {
        // Simulasi kasus Anichin THOG ep96 ganda (dua halaman sah).
        // Dedupe final ada di ProviderMapper.extractEpisodes berdasarkan HREF,
        // sehingga keduanya harus lolos deteksi dan tampil dua-duanya.
        val html = """
        <html><body><ul class="eplister">
          <li><a href="https://anichin.cafe/tales-of-herding-gods-episode-96-subtitle-indonesia/">
            <span class="epl-num">96</span><span class="epl-title">Episode 96</span></a></li>
          <li><a href="https://anichin.cafe/tales-of-herding-gods-episode-96-subtitle-indonesia-2/">
            <span class="epl-num">96</span><span class="epl-title">Episode 96</span></a></li>
        </ul></body></html>
        """.trimIndent()
        val out = detect(html, "https://anichin.cafe/seri/tales-of-herding-gods/")
        assertEquals(2, out.size)
        assertTrue(out[0] != out[1])
    }
}
