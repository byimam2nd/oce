package com.baseprovider.core

import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPaginationTest {

    private fun fakeResponse(name: String, url: String): SearchResponse =
        object : SearchResponse {
            override val name = name
            override val url = url
            override val apiName = "test"
            override var type: TvType? = TvType.TvSeries
            override var posterUrl: String? = null
            override var posterHeaders: Map<String, String>? = null
            override var id: Int? = null
            override var quality: SearchQuality? = null
            override var score: Score? = null
        }

    private val nonEmptyResults: List<SearchResponse> =
        listOf(fakeResponse("Test", "https://test.com/a"))
    private val emptyResults: List<SearchResponse> = emptyList()

    private val htmlPaged = ProviderConfig(
        id = "paged",
        mainUrl = "https://test.com",
        searchPathPattern = "{baseUrl}/page/{page}/?s={query}"
    )
    private val htmlNoPage = ProviderConfig(
        id = "nopage",
        mainUrl = "https://test.com",
        searchPathPattern = "{baseUrl}/?s={query}"
    )
    private val jsonSearch = ProviderConfig(
        id = "json",
        mainUrl = "https://test.com",
        searchPathPattern = "{baseUrl}/search/{query}",
        isJsonSearch = true
    )

    @Test
    fun `paged html with results has next`() {
        assertTrue(hasNextSearchPage(htmlPaged, nonEmptyResults))
    }

    @Test
    fun `paged html with empty results stops`() {
        assertFalse(hasNextSearchPage(htmlPaged, emptyResults))
    }

    @Test
    fun `html without page token stops even with results`() {
        assertFalse(hasNextSearchPage(htmlNoPage, nonEmptyResults))
    }

    @Test
    fun `json search always stops`() {
        assertFalse(hasNextSearchPage(jsonSearch, nonEmptyResults))
    }
}