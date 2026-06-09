package com.baseprovider

import com.lagradost.cloudstream3.TvType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderConfigParserTest {

    @Test
    fun `fromJson parses minimal config`() {
        val json = JSONObject("""{"mainUrl": "https://example.com", "supportedTypes": ["Movie"]}""")
        val config = fromJson("test", json)
        assertEquals("test", config.id)
        assertEquals("https://example.com", config.mainUrl)
        assertEquals(setOf(TvType.Movie), config.supportedTypes)
    }

    @Test
    fun `fromJson parses full config`() {
        val raw = """
        {
            "name": "TestProvider",
            "mainUrl": "https://test.com",
            "seriesUrl": "https://test.com/anime",
            "searchUrl": "https://test.com/search",
            "lang": "en",
            "supportedTypes": ["Movie", "TvSeries", "Anime"],
            "searchPathPattern": "/search/{query}",
            "mainPagePathPattern": "/{data}",
            "reverseEpisodes": false,
            "isHorizontal": true,
            "searchItems": ".poster",
            "loadTitle": "h1.entry-title",
            "globalHeaders": {"Referer": "https://test.com"},
            "mirrorUrls": ["https://mirror1.com", "https://mirror2.com"]
        }
        """.trimIndent()
        val json = JSONObject(raw)
        val config = fromJson("fulltest", json)
        assertEquals("TestProvider", config.name)
        assertEquals("https://test.com", config.mainUrl)
        assertEquals("https://test.com/anime", config.seriesUrl)
        assertEquals("en", config.lang)
        assertEquals(setOf(TvType.Movie, TvType.TvSeries, TvType.Anime), config.supportedTypes)
        assertEquals(".poster", config.searchItems)
        assertEquals("h1.entry-title", config.loadTitle)
        assertEquals(mapOf("Referer" to "https://test.com"), config.globalHeaders)
        assertEquals(listOf("https://mirror1.com", "https://mirror2.com"), config.mirrorUrls)
        assertTrue(config.isHorizontal)
        assertFalse(config.reverseEpisodes)
    }

    @Test
    fun `fromJson sets defaults for missing fields`() {
        val json = JSONObject("""{"mainUrl": "https://defaults.com", "supportedTypes": ["Movie"]}""")
        val config = fromJson("defaults", json)
        assertEquals("defaults", config.name)
        assertEquals("id", config.lang)
        assertTrue(config.reverseEpisodes)
        assertEquals(2, config.searchPageLimit)
        assertEquals("", config.searchItems)
    }

    @Test
    fun `fromJson handles empty supportedTypes`() {
        val json = JSONObject("""{"mainUrl": "https://empty.com"}""")
        val config = fromJson("empty", json)
        assertTrue(config.supportedTypes.isEmpty())
    }

    @Test
    fun `fromJson handles bloatRegex override`() {
        val json = JSONObject("""{"mainUrl": "https://bloat.com", "supportedTypes": ["Movie"], "bloatRegex": "test|regex"}""")
        val config = fromJson("bloat", json)
        assertEquals("test|regex", config.bloatRegex.pattern)
    }

    @Test
    fun `fromJson handles invalid regex gracefully`() {
        val json = JSONObject("""{"mainUrl": "https://invalid-regex.com", "supportedTypes": ["Movie"], "yearExtractorRegex": "[invalid"}""")
        val config = fromJson("invalid-regex", json)
        assertEquals("", config.yearExtractorRegex)
    }
}
