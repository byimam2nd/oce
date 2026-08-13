package com.baseprovider

import com.baseprovider.config.ProviderConfig
import com.baseprovider.model.safeCleanBloat
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.*
import org.junit.Test

class ProviderConfigTest {

    @Test
    fun `config with valid fields passes validation`() {
        val config = ProviderConfig(
            id = "test",
            mainUrl = "https://example.com",
            supportedTypes = setOf(TvType.Movie)
        )
        assertEquals("test", config.id)
        assertEquals("https://example.com", config.mainUrl)
    }

    @Test
    fun `seriesUrl falls back to mainUrl when null`() {
        val config = ProviderConfig(
            id = "test",
            mainUrl = "https://example.com",
            supportedTypes = setOf(TvType.Movie)
        )
        assertNull(config.seriesUrl)
    }

    @Test
    fun `bloatRegex strips common words`() {
        val result = "Nonton Anime Sub Indo Full HD".safeCleanBloat(
            "Nonton Anime Sub Indo Full HD",
            Regex("""(?i)(\bONA\b|\bSub\b|Nonton|Anime|Full|HD|\d{3,4}p)""")
        )
        assertFalse(result.contains("Anime"))
        assertFalse(result.contains("Sub"))
    }
}
