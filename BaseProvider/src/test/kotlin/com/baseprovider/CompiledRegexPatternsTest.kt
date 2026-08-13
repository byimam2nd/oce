package com.baseprovider

import com.baseprovider.extractor.CompiledRegexPatterns
import org.junit.Assert.*
import org.junit.Test

class CompiledRegexPatternsTest {

    @Test
    fun `prioritizeAdaptiveUrls prefers m3u8 over mpd`() {
        val result = CompiledRegexPatterns.prioritizeAdaptiveUrls(
            listOf("https://cdn.test/video.mp4", "https://cdn.test/master.mpd", "https://cdn.test/hls/master.m3u8")
        )
        assertEquals(listOf("https://cdn.test/hls/master.m3u8"), result)
    }

    @Test
    fun `prioritizeAdaptiveUrls uses mpd when no m3u8`() {
        val result = CompiledRegexPatterns.prioritizeAdaptiveUrls(
            listOf("https://cdn.test/video.mp4", "https://cdn.test/master.mpd")
        )
        assertEquals(listOf("https://cdn.test/master.mpd"), result)
    }

    @Test
    fun `prioritizeAdaptiveUrls keeps all when no adaptive`() {
        val urls = listOf("https://cdn.test/a.mp4", "https://cdn.test/b.mp4")
        assertEquals(urls, CompiledRegexPatterns.prioritizeAdaptiveUrls(urls))
    }

    @Test
    fun `prioritizeAdaptiveUrls deduplicates`() {
        val result = CompiledRegexPatterns.prioritizeAdaptiveUrls(
            listOf("https://cdn.test/video.mp4", "https://cdn.test/video.mp4")
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `prioritizeAdaptiveUrls empty input`() {
        assertTrue(CompiledRegexPatterns.prioritizeAdaptiveUrls(emptyList()).isEmpty())
    }
}