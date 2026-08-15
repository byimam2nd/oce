package com.baseprovider

import com.baseprovider.model.SelectorResolver
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SelectorResolverTest {

    private val html = """
        <html><body>
          <div class="gallery-grid">
            <article>
              <div class="poster"><img src="/p1.jpg"></div>
              <figcaption><h3 class="poster-title">Movie One</h3></figcaption>
              <span class="rating-number">7.5</span>
            </article>
            <article>
              <div class="poster"><img src="/p2.jpg"></div>
              <figcaption><h3 class="poster-title">Movie Two</h3></figcaption>
            </article>
          </div>
          <div class="movie-info">
            <h1>The Invite (2026)</h1>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `selectFirst falls back to second variant when first has no match`() {
        val doc = Jsoup.parse(html)
        val el = SelectorResolver.selectFirst(
            doc,
            "div.nonexistent || div.movie-info h1",
            "test:title"
        )
        assertNotNull(el)
        assertEquals("The Invite (2026)", el!!.text())
    }

    @Test
    fun `select returns matches from first non-empty variant`() {
        val doc = Jsoup.parse(html)
        val items = SelectorResolver.select(
            doc,
            "div.noitems || div.gallery-grid article",
            "test:items"
        )
        assertEquals(2, items.size)
    }

    @Test
    fun `selectFirst keeps single-variant behavior for legacy selectors`() {
        val doc = Jsoup.parse(html)
        val el = SelectorResolver.selectFirst(doc, "h3.poster-title")
        assertEquals("Movie One", el?.text())
    }

    @Test
    fun `adaptive relocate finds element after selector change`() {
        val key = "test:adaptiveRelocate"
        // Fase save: selector lama bekerja
        val doc1 = Jsoup.parse(html)
        val el1 = SelectorResolver.selectFirst(doc1, "div.movie-info h1", key)
        assertEquals("The Invite (2026)", el1?.text())

        // Fase match: struktur berubah, selector lama gagal → relocate
        val newHtml = """
            <html><body>
              <div class="movie-detail">
                <h1>The Invite (2026)</h1>
              </div>
            </body></html>
        """.trimIndent()
        val doc2 = Jsoup.parse(newHtml)
        val el2 = SelectorResolver.selectFirst(doc2, "div.movie-info h1", key)
        assertNotNull(el2)
        assertEquals("The Invite (2026)", el2!!.text())
    }

    @Test
    fun `adaptive relocate returns empty when no fingerprint exists`() {
        val doc = Jsoup.parse(html)
        val el = SelectorResolver.selectFirst(doc, "div.no-such-thing", "test:nofp")
        assertNull(el)
    }

    @Test
    fun `detectEpisodeLinks finds episode anchors by url and text`() {
        val epHtml = """
            <html><body>
              <div class="gmr-listseries">
                <a class="button active gmr-all-serie" href="https://site.com/tv/show-2026/">Lihat Semua Episode</a>
                <a class="button" href="https://site.com/eps/show-season-1-episode-1/">S1 Eps1</a>
                <a class="button" href="https://site.com/eps/show-season-1-episode-2/">S1 Eps2</a>
                <a href="https://site.com/nonton/other/">Step 1</a>
              </div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(epHtml)
        val links = SelectorResolver.detectEpisodeLinks(
            doc, "https://site.com/tv/show-2026/"
        )
        assertEquals(2, links.size)
        assertEquals("https://site.com/eps/show-season-1-episode-1/", links[0].attr("href"))
        assertEquals("https://site.com/eps/show-season-1-episode-2/", links[1].attr("href"))
    }

    @Test
    fun `detectEpisodeLinks skips same-page and non-episode links`() {
        val epHtml = """
            <html><body>
              <a href="https://site.com/tv/show-2026/">Lihat Semua Episode</a>
              <a href="https://site.com/download/abc123">Link Download 1</a>
              <a href="https://site.com/tv/show-2026/">Show Page</a>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(epHtml)
        val links = SelectorResolver.detectEpisodeLinks(
            doc, "https://site.com/tv/show-2026/"
        )
        assertTrue(links.isEmpty())
    }
}
