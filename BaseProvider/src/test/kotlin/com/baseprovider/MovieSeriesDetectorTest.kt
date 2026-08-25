package com.baseprovider

import com.baseprovider.core.MovieSeriesDetector
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression test untuk MovieSeriesDetector — melindungi heuristic
 * movie/series dari regress saat refactor di masa depan.
 *
 * Setiap test case berasal dari kasus nyata yang ditemukan di device.
 */
class MovieSeriesDetectorTest {

    private val config = jsonConfig("""
        {"id":"Test","name":"Test","mainUrl":"https://test.com",
         "supportedTypes":["Anime","TvSeries"],
         "tvPathSegment":"/anime/",
         "episodeItems":".eplister li",
         "linkOptions":"option[data-index], option[value]"}
    """)

    // Helper: build Elements from HTML snippet
    private fun eps(vararg hrefs: String) = Jsoup.parse(
        hrefs.joinToString("") { "<li><a href=\"$it\">Episode</a></li>" },
        "https://test.com"
    ).select("li")

    private fun detect(url: String, epHtml: String, hasPlayer: Boolean,
                       hasSeason: Boolean = false): com.baseprovider.model.DetectionResult {
        val doc: Document = Jsoup.parse(epHtml, url)
        val eps = doc.select(".eplister li")
        val seasonScript = if (hasSeason)
            doc.createElement("script").apply { text("{}") } else null
        return MovieSeriesDetector.detect(
            url = url, config = config, epItems = eps,
            hasOnPagePlayer = hasPlayer, seasonDataScript = seasonScript,
            fallbackEpisodeLinks = org.jsoup.select.Elements(),
            looksLikeMovieUrlFn = { !it.contains("/anime/") && it.contains("-202") }
        )
    }

    // ═══ TEST 1-2: Series dengan banyak episode + player ada → SERIES ═══

    @Test fun `thog anichin - 97 eps valid - player ada - SERIES`() {
        val urls = (1..97).map {
            "https://anichin.cafe/tales-of-herding-gods-episode-$it-subtitle-indonesia/"
        }
        val r = detect(
            url = "https://anichin.cafe/seri/tales-of-herding-gods/",
            epHtml = urls.joinToString("") { "<div class=\"eplister\"><li><a href=\"$it\">Ep</a></li></div>" },
            hasPlayer = true
        )
        assertFalse("THOG Anichin harus SERIES", r.isMovie)
        assertEquals(97, r.validEpCount)
        assertEquals(com.baseprovider.model.DetectionReason.CONFIGURED_EPISODES_VALIDATED, r.reason)
    }

    @Test fun `thog animexin - root-slug - player ada - SERIES`() {
        val urls = (1..97).map {
            "https://animexin.dev/tales-of-herding-gods-episode-$it-indonesia-english-sub/"
        }
        val r = detect(
            url = "https://animexin.dev/tales-of-herding-gods/",
            epHtml = urls.joinToString("") { "<div class=\"eplister\"><li><a href=\"$it\">Ep $it</a></li></div>" },
            hasPlayer = true
        )
        assertFalse("THOG Animexin root-slug harus SERIES", r.isMovie)
        assertTrue(r.validEpCount >= 90)
    }

    // ═══ TEST 3: Sacrifice DM21 - kontaminasi relocate → MOVIE ═══

    @Test fun `sacrifice dm21 - kontaminasi relocate - MOVIE`() {
        val r = detect(
            url = "https://cyber-junkie.com/sacrifice-2026/",
            epHtml = """
                <div class="eplister">
                    <li><a href="https://cyber-junkie.com/tv/ludwig-season-2-2026/">Ludwig S2</a></li>
                    <li><a href="https://cyber-junkie.com/country/denmark/">Denmark</a></li>
                </div>
            """.trimIndent(),
            hasPlayer = true
        )
        assertTrue("Sacrifice dengan kontaminasi harus MOVIE", r.isMovie)
        assertEquals(0, r.validEpCount)
    }

    // ═══ TEST 4: Movie tanpa episode → MOVIE ═══

    @Test fun `perfect world movie - 0 eps - MOVIE`() {
        val r = detect(
            url = "https://animexin.dev/perfect-world-movie-ninefold-the-burning-sky/",
            epHtml = "<html><body><p>No episodes here</p></body></html>",
            hasPlayer = false
        )
        assertTrue("Tanpa episode harus MOVIE", r.isMovie)
    }

    // ═══ TEST 5: Series pendek 2 eps valid + player ada → SERIES ═══

    @Test fun `series pendek - 2 eps valid - player ada - SERIES`() {
        val r = detect(
            url = "https://animexin.dev/anime/short-series/",
            epHtml = """
                <div class="eplister">
                    <li><a href="https://animexin.dev/short-series-episode-1-sub-indo/">Ep 1</a></li>
                    <li><a href="https://animexin.dev/short-series-episode-2-sub-indo/">Ep 2</a></li>
                </div>
            """.trimIndent(),
            hasPlayer = true
        )
        assertFalse("Series pendek tetap SERIES", r.isMovie)
        assertEquals(2, r.validEpCount)
    }

    // ═══ TEST 6: Series tanpa player tapi ada 12 eps valid → SERIES ═══

    @Test fun `series tanpa player - 12 eps valid - SERIES`() {
        val urls = (1..12).map {
            "https://animexin.dev/anime/test-show-episode-$it-sub-indo/"
        }
        val r = detect(
            url = "https://animexin.dev/anime/test-show/",
            epHtml = urls.joinToString("") { "<div class=\"eplister\"><li><a href=\"$it\">Ep $it</a></li></div>" },
            hasPlayer = false
        )
        assertFalse("Tanpa player tetap SERIES", r.isMovie)
        assertEquals(12, r.validEpCount)
    }

    // ═══ TEST 7: Fallback detectEpisodeLinks menemukan episode → SERIES ═══

    @Test fun `fallback deteksi episode tanpa selector - SERIES`() {
        val r = detect(
            url = "https://animexin.dev/anime/no-selector-show/",
            epHtml = """
                <html><body>
                    <a href="https://animexin.dev/no-selector-show-episode-1-sub-indo/">Ep 1</a>
                    <a href="https://animexin.dev/no-selector-show-episode-2-sub-indo/">Ep 2</a>
                </body></html>
            """.trimIndent(),
            hasPlayer = false
        )
        // Note: ini test detectEpisodeLinks fallback via URL pattern
        // Karena configured selector tidak match (.eplister li tidak ada),
        // fallback detectEpisodeLinks yang mencari
        assertFalse("Fallback menemukan episode → SERIES", r.isMovie)
    }

    // ═══ TEST 8: Fake episode links (bukan pola episode) → MOVIE ═══

    @Test fun `movie dengan fake episode links - MOVIE`() {
        val r = detect(
            url = "https://cyber-junkie.com/some-movie-2025/",
            epHtml = """
                <div class="eplister">
                    <li><a href="https://cyber-junkie.com/random-page-1/">Random 1</a></li>
                    <li><a href="https://cyber-junkie.com/random-page-2/">Random 2</a></li>
                    <li><a href="https://cyber-junkie.com/random-page-3/">Random 3</a></li>
                </div>
            """.trimIndent(),
            hasPlayer = true
        )
        assertTrue("Fake episode links harus MOVIE", r.isMovie)
    }

    // ═══ TEST 9: /tv/ URL dengan selector rusak → SERIES via TV marker ═══

    @Test fun `tv url selector rusak - SERIES via tv marker`() {
        val r = detect(
            url = "https://cyber-junkie.com/tv/broken-selector-show/",
            epHtml = "<html><body><p>No episodes found</p></body></html>",
            hasPlayer = false
        )
        // /tv/ adalah strong TV marker → SERIES meski tanpa episode
        assertFalse("TV marker harus SERIES", r.isMovie)
    }

    // ═══ TEST 10: validateEpisodes — kualitas vs jumlah ═══

    @Test fun `validateEpisodes - kualitas href menentukan bukan jumlah`() {
        // BANYAK elemen TAPI tidak ada pola episode → invalid
        val junkHtml = (1..50).joinToString("") {
            "<li><a href=\"https://test.com/page-$it/\">Page $it</a></li>"
        }
        val junkDoc = Jsoup.parse("<ul>$junkHtml</ul>", "https://test.com")
        val (validJunk, _) = MovieSeriesDetector.validateEpisodes(
            junkDoc.select("li"), "https://test.com/movie-x/"
        )
        assertEquals(0, validJunk.size)

        // SEDIKIT elemen TAPI semua punya pola episode → valid
        val realHtml = (1..3).joinToString("") {
            "<li><a href=\"https://test.com/show-episode-$it-sub-indo/\">Ep $it</a></li>"
        }
        val realDoc = Jsoup.parse("<ul>$realHtml</ul>", "https://test.com")
        val (validReal, _) = MovieSeriesDetector.validateEpisodes(
            realDoc.select("li"), "https://test.com/show/"
        )
        assertEquals(3, validReal.size)
    }

    // ── Helper ──
    private fun jsonConfig(json: String): com.baseprovider.config.ProviderConfig {
        val jsonObj = org.json.JSONObject(json.trimIndent())
        return com.baseprovider.config.fromJson("Test", jsonObj)
    }

}
