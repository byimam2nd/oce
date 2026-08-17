package com.baseprovider

import com.baseprovider.config.ExtractorConfig
import com.baseprovider.config.IdSource
import com.baseprovider.extractor.ConfigDrivenExtractor
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ConfigDrivenEngineTest {

    @Test
    fun `extractId query param`() {
        val config = ExtractorConfig(
            id = "Q",
            mainUrl = "https://q.com",
            idSource = IdSource(type = "query", param = "id"),
        )
        val cde = ConfigDrivenExtractor(config)
        assertEquals("v7c29rs", cde.extractId("https://anichin.stream/?id=v7c29rs&x=1"))
        assertEquals(null, cde.extractId("https://anichin.stream/embed/v7c29rs"))
    }

    @Test
    fun `extractId path`() {
        val config = ExtractorConfig(
            id = "P",
            mainUrl = "https://p.com",
            idSource = IdSource(type = "path"),
        )
        val cde = ConfigDrivenExtractor(config)
        assertEquals("abc123", cde.extractId("https://p.com/abc123?t=1"))
        assertEquals("xyz", cde.extractId("https://p.com/xyz/"))
    }

    @Test
    fun `extractId regex`() {
        val config = ExtractorConfig(
            id = "R",
            mainUrl = "https://r.com",
            idSource = IdSource(
                type = "regex",
                pattern = """embed-([a-zA-Z0-9]+)\.html""",
            ),
        )
        val cde = ConfigDrivenExtractor(config)
        assertEquals("uDxm1k", cde.extractId("https://r.com/embed-uDxm1k.html"))
    }

    @Test
    fun `jsonPath simple string`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        val json = """{"file": "https://cdn.com/v.m3u8"}"""
        assertEquals("https://cdn.com/v.m3u8", ex.resolveJsonPath(json, "file"))
    }

    @Test
    fun `jsonPath nested array index`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        val json = """{"sources": [{"file": "a.m3u8"}, {"file": "b.m3u8"}]}"""
        assertEquals("b.m3u8", ex.resolveJsonPath(json, "sources[1].file"))
    }

    @Test
    fun `jsonPath wildcard collects all`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        val json = """{"qualities": {"auto": {"240": {"url": "u1.m3u8"}, "480": {"url": "u2.m3u8"}}}}"""
        val result = ex.resolveJsonPath(json, "qualities.auto[].url")
        val arr = result as JSONArray
        assertEquals(2, arr.length())
        assertTrue(arr.optString(0) == "u1.m3u8" || arr.optString(1) == "u1.m3u8")
        assertTrue(arr.optString(0) == "u2.m3u8" || arr.optString(1) == "u2.m3u8")
    }

    @Test
    fun `jsonPath wildcard array sources`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        val json = """{"result": {"sources": [{"status": true, "url": "https://a.com/1.mp4"}, {"status": true, "url": "https://b.com/2.mp4"}]}}"""
        val result = ex.resolveJsonPath(json, "result.sources[].url")
        val arr = result as JSONArray
        assertEquals(2, arr.length())
        assertEquals("https://a.com/1.mp4", arr.optString(0))
        assertEquals("https://b.com/2.mp4", arr.optString(1))
    }

    @Test
    fun `jsonPath missing returns null`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        assertEquals(null, ex.resolveJsonPath("""{"a": 1}""", "b.c"))
        assertEquals(null, ex.resolveJsonPath("not json", "a"))
    }

    @Test
    fun `jsonPath single element array unwraps to string`() {
        val ex = ConfigDrivenExtractor(ExtractorConfig(id = "T", mainUrl = "https://t.com"))
        val json = """{"playback": {"payload": ["abc123"]}}"""
        assertEquals("abc123", ex.resolveJsonPath(json, "playback.payload"))
    }
}
