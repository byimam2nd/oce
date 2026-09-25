package com.baseprovider

import com.baseprovider.config.ProviderConfig
import com.baseprovider.core.AdaptiveRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRouterTest {

    private val config = ProviderConfig(
        id = "test",
        name = "Test",
        mainUrl = "https://a.test",
        seriesUrl = "https://series.test",
        searchUrl = "https://a.test",
        mirrorUrls = listOf("https://www.a.test", "https://b.test")
    )

    @Test
    fun `allowedHosts dedupe and strip www prefix`() {
        val hosts = AdaptiveRouter.allowedHosts(config)
        assertEquals(3, hosts.size)
        assertTrue("a.test" in hosts)
        assertFalse("www.a.test" in hosts)
        assertTrue("series.test" in hosts)
        assertTrue("b.test" in hosts)
    }

    @Test
    fun `hostOf handles null, blank and invalid input`() {
        assertNull(AdaptiveRouter.hostOf(null))
        assertNull(AdaptiveRouter.hostOf(""))
        assertNull(AdaptiveRouter.hostOf("not a url"))
        assertEquals("204.3.234.75",
            AdaptiveRouter.hostOf("https://204.3.234.75/country/korea/"))
    }

    @Test
    fun `isOffHost only true for foreign hosts`() {
        val hosts = AdaptiveRouter.allowedHosts(config)
        assertTrue(AdaptiveRouter.isOffHost(
            "https://rebahinxxi.cumapemula.xyz/", hosts))
        assertFalse(AdaptiveRouter.isOffHost(
            "https://a.test/horror/", hosts))
        assertFalse(AdaptiveRouter.isOffHost(
            "https://b.test/horror/", hosts))
        assertFalse(AdaptiveRouter.isOffHost(null, hosts))
    }

    @Test
    fun `row fallback not triggered when host is allowed`() {
        val fb = AdaptiveRouter.mainPageFallback(
            "horror", "https://a.test/horror/",
            AdaptiveRouter.allowedHosts(config),
            mapOf("horror" to "thriller"))
        assertNull(fb)
    }

    @Test
    fun `row fallback picked when row redirects off-host`() {
        val fb = AdaptiveRouter.mainPageFallback(
            "horror", "https://rebahinxxi.cumapemula.xyz/",
            AdaptiveRouter.allowedHosts(config),
            mapOf("horror" to "thriller"))
        assertEquals("thriller", fb)
    }

    @Test
    fun `row fallback null without mapping or blank fallback`() {
        val hosts = AdaptiveRouter.allowedHosts(config)
        assertNull(AdaptiveRouter.mainPageFallback(
            "horror", "https://other.test/", hosts, mapOf()))
        assertNull(AdaptiveRouter.mainPageFallback(
            "horror", "https://other.test/", hosts, mapOf("horror" to " ")))
    }

    @Test
    fun `search url builder replaces tokens for primary and fallback`() {
        assertEquals("https://a.test/?s=avengers&paged=2",
            AdaptiveRouter.buildSearchUrl("{baseUrl}/?s={query}&paged={page}",
                "https://a.test", "avengers", 2))
        assertEquals("https://a.test/page/1/?s=avengers",
            AdaptiveRouter.buildSearchUrl("{baseUrl}/page/{page}/?s={query}",
                "https://a.test", "avengers", 1))
    }
}