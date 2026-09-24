package com.baseprovider

import com.baseprovider.collector.LinkCollector
import com.baseprovider.config.ProviderConfig
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class LinkCollectorTest {

    private fun config(
        linkOptions: String = "",
        downloadItems: String = "",
        switchVideoSelector: String = "",
        iframeTag: String = "",
        iframeSources: List<String> = listOf("src"),
        attrValue: List<String> = listOf("value")
    ): ProviderConfig = ProviderConfig(
        id = "TestProvider",
        linkOptions = linkOptions,
        downloadItems = downloadItems,
        switchVideoSelector = switchVideoSelector,
        iframeTag = iframeTag,
        iframeSources = iframeSources,
        attrValue = attrValue
    )

    // ── collectLinkOptions ──

    @Test
    fun `collectLinkOptions extracts option values with labels`() {
        val config = config(
            linkOptions = "select.mirror option[value]",
            attrValue = listOf("value", "data-url")
        )
        val html = """
            <select class="mirror">
                <option value="https://player.example.com/e1">Server 1</option>
                <option value="">Server kosong</option>
                <option value="https://player.example.com/e2">Server 2</option>
            </select>
        """.trimIndent()
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectLinkOptions(Jsoup.parse(html), links)
        assertEquals(2, links.size)
        assertTrue(links.contains("https://player.example.com/e1" to "Server 1"))
        assertTrue(links.contains("https://player.example.com/e2" to "Server 2"))
    }

    @Test
    fun `collectLinkOptions prefers data-url over href on nested anchors`() {
        val config = config(
            linkOptions = "div.servers li",
            attrValue = listOf("value", "data-url")
        )
        val html = """
            <div class="servers"><ul>
                <li><a href="/fallback/e1" data-url="https://embed.example.com/e1">HD</a></li>
                <li><a href="/fallback/e2">SD</a></li>
            </ul></div>
        """.trimIndent()
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectLinkOptions(Jsoup.parse(html), links)
        assertEquals(2, links.size)
        assertTrue(links.contains("https://embed.example.com/e1" to "HD"))
        assertTrue(links.contains("/fallback/e2" to "SD"))
    }

    @Test
    fun `collectLinkOptions is no-op when selector blank`() {
        val config = config()
        val html = """<select class="mirror"><option value="https://player.example.com/e1">X</option></select>"""
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectLinkOptions(Jsoup.parse(html), links)
        assertTrue(links.isEmpty())
    }

    // ── collectDownloadItems ──

    @Test
    fun `collectDownloadItems extracts anchor hrefs and skips blank`() {
        val config = config(downloadItems = "#downloadb")
        val html = """
            <div id="downloadb">
                <a href="https://dl.example.com/f1.mkv">720p</a>
                <a href="">skip me</a>
                <span>tanpa link</span>
            </div>
        """.trimIndent()
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectDownloadItems(Jsoup.parse(html), links)
        assertEquals(1, links.size)
        assertTrue(links.contains("https://dl.example.com/f1.mkv" to "720p"))
    }

    // ── collectSwitchVideoButtons ──

    @Test
    fun `collectSwitchVideoButtons extracts onclick urls and resolves relative paths`() {
        val config = config(switchVideoSelector = "div.player button[onclick]")
        val html = """
            <div class="player">
                <button onclick="switchVideo('https://cdn.example.com/hls/ep1.m3u8')">HD</button>
                <button onclick="switchVideo('/embed/ep-1')">SD</button>
                <button onclick="playOther('x')">Diabaikan</button>
                <button>Tanpa onclick</button>
            </div>
        """.trimIndent()
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectSwitchVideoButtons(
            Jsoup.parse(html),
            "https://site.example.com/watch/ep-1/",
            links
        )
        assertEquals(2, links.size)
        assertTrue(links.contains("https://cdn.example.com/hls/ep1.m3u8" to "HD"))
        assertTrue(links.contains("https://site.example.com/embed/ep-1" to "SD"))
    }

    @Test
    fun `collectSwitchVideoButtons is no-op when selector blank`() {
        val config = config()
        val html = """<button onclick="switchVideo('https://x.example.com/v.m3u8')">HD</button>"""
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectSwitchVideoButtons(
            Jsoup.parse(html),
            "https://site.example.com/watch/ep-1/",
            links
        )
        assertTrue(links.isEmpty())
    }

    // ── collectIframes ──

    @Test
    fun `collectIframes reads data-src and src and skips blank or about-blank`() {
        val config = config(
            iframeTag = "iframe",
            iframeSources = listOf("data-src", "src")
        )
        val html = """
            <iframe data-src="https://embed.example.com/e1"></iframe>
            <iframe src="about:blank"></iframe>
            <iframe src=""></iframe>
            <iframe src="https://embed.example.com/e2"></iframe>
        """.trimIndent()
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectIframes(Jsoup.parse(html), links)
        assertEquals(2, links.size)
        assertTrue(links.contains("https://embed.example.com/e1" to null))
        assertTrue(links.contains("https://embed.example.com/e2" to null))
    }

    @Test
    fun `collectIframes is no-op when selector blank`() {
        val config = config()
        val html = """<iframe src="https://embed.example.com/e1"></iframe>"""
        val links = mutableSetOf<Pair<String, String?>>()
        LinkCollector(config).collectIframes(Jsoup.parse(html), links)
        assertTrue(links.isEmpty())
    }
}
