package com.Pencurimovie

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

class Pencurimovie : MainAPI() {
    override var mainUrl = "https://ww73.pencurimovie.bond"
    override var name = "Pencurimovie"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)
    
    companion object {
        private const val MAX_LINKS = 15
        private const val MAX_FOUND = 8
        private const val MAX_DEPTH = 2
    }
    
    private val allowedDomains = listOf("voe", "do7go", "dhcplay", "listeamed", "hglink", "dsvplay", "streamwish", "dood", "filemoon", "mixdrop", "vidhide")
    private val dynamicDomains = ConcurrentHashMap.newKeySet<String>()
    
    private fun learnDomain(url: String) {
        try {
            val host = URI(url).host ?: return
            if (host.contains(".") && !host.contains("google") && !host.contains("facebook") && !host.contains("doubleclick") && !host.contains("cloudflare") && !host.contains("analytics")) {
                dynamicDomains.add(host)
            }
        } catch (_: Exception) {}
    }
    
    private fun isValidVideoHost(url: String): Boolean {
        val host = try { URI(url).host } catch (e: Exception) { return false }
        return allowedDomains.any { host.contains(it) } || dynamicDomains.any { host.contains(it) }
    }
    
    private fun normalizeLink(url: String): String = url.substringBefore("#")
    
    private fun decryptAES(encrypted: String, key: String, iv: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            String(cipher.doFinal(java.util.Base64.getDecoder().decode(encrypted)))
        } catch (_: Exception) { null }
    }
    
    private fun isVideoUrl(url: String): Boolean = url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv") || url.contains(".webm")

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "top-imdb" to "Top IMDB Movies",
        "country/malaysia" to "Malaysia Movies",
        "country/indonesia" to "Indonesia Movies",
        "country/india" to "India Movies",
        "country/japan" to "Japan Movies",
        "country/thailand" to "Thailand Movies",
        "country/china" to "China Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Pencurimovie")
            app.get("$mainUrl/${request.data}/page/$page", timeout = 5000L).document
        }
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        val response = newHomePageResponse(HomePageList(request.name, home, false), true)
        return response
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("oldtitle").substringBefore("(").ifEmpty { this.select("a").attr("title").substringBefore("(") }
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("a img").attr("data-original").ifEmpty { this.select("a img").attr("data-src") }.ifEmpty { this.select("a img").attr("src") })
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(this@toSearchResult.select("span.mli-quality").text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Pencurimovie")
            app.get("${mainUrl}?s=$query", timeout = 10000L).document
        }
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Pencurimovie")
            app.get(url, timeout = 10000L).document
        }
        
        val title = document.selectFirst("div.mvic-desc h3")?.text()?.trim()?.substringBefore("(") ?: "Unknown Title"
        val poster = document.select("meta[property=og:image]").attr("content").ifEmpty { document.selectFirst("div.mvic-thumb img")?.attr("src").orEmpty() }
        val description = document.selectFirst("div.desc p.f-desc")?.text()?.trim() ?: ""
        val tvtag = if (url.contains("series")) TvType.TvSeries else TvType.Movie
        val genre = document.select("div.mvic-info p:contains(Genre)").select("a").map { it.text() }
        val year = document.select("div.mvic-info p:contains(Release)").select("a").text().toIntOrNull()

        return if (tvtag == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tvseason").forEach { info ->
                val season = info.select("strong").text().substringAfter("Season").trim().toIntOrNull()
                info.select("div.les-content a").forEach { it ->
                    val epText = it.text().substringAfter("Episode").substringBefore("-").trim()
                    episodes.add(newEpisode(it.attr("href")) {
                        this.episode = extractEpisodeNumber(epText)
                        this.name = it.text().substringAfter("-").trim()
                        this.season = season
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.tags = genre; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = description; this.tags = genre; this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = executeWithRetry {
                rateLimitDelay(moduleName = "Pencurimovie")
                app.get(data, timeout = 10000L).document
            }
            val links = mutableSetOf<String>()
            
            document.select("iframe").forEach {
                val src = it.attr("data-src").ifEmpty { it.attr("src") }
                if (src.startsWith("http") && isValidVideoHost(src)) links.add(normalizeLink(fixUrl(src)))
            }
            
            document.select("[src], [data-src], [data-link], a[href]").forEach {
                val link = it.attr("data-src").ifEmpty { it.attr("data-link") }.ifEmpty { it.attr("src") }.ifEmpty { it.attr("href") }
                if (link.startsWith("http") && isValidVideoHost(link)) links.add(normalizeLink(link))
            }
            
            Regex("""https?://[^\s'"]+""").findAll(document.html()).map { it.value }.filter { isValidVideoHost(it) }.forEach { links.add(normalizeLink(it)) }
            
            val found = AtomicInteger(0)
            links.sortedByDescending { when { it.contains(".m3u8") -> 5; it.contains("embed") -> 4; else -> 1 } }.take(MAX_LINKS).amap { link ->
                if (found.get() >= MAX_FOUND) return@amap
                learnDomain(link)
                deepResolve(link, link).distinct().forEach { realUrl ->
                    if (found.get() < MAX_FOUND && extractVideo(realUrl, data, subtitleCallback, callback)) found.incrementAndGet()
                }
            }
            return true
        } catch (_: Exception) { return false }
    }
    
    private suspend fun deepResolve(url: String, referer: String?, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()
        val results = mutableSetOf<String>(url)
        try {
            val res = app.get(url, headers = mapOf("Referer" to (referer ?: url)), allowRedirects = true, timeout = 10000L)
            Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""").findAll(res.text).forEach { if (isVideoUrl(it.value)) results.add(it.value) }
            Regex("""file["']?\s*:\s*["']([^"']+)["']""").findAll(res.text).forEach { if (isVideoUrl(it.groupValues[1])) results.add(it.groupValues[1]) }
            
            Regex("""<iframe[^>]*src=["']([^"']+)["']""").findAll(res.text).forEach { 
                val iframeUrl = fixUrl(it.groupValues[1])
                if (iframeUrl.startsWith("http") && isValidVideoHost(iframeUrl)) results.addAll(deepResolve(iframeUrl, url, depth + 1))
            }
        } catch (_: Exception) {}
        return results.map { normalizeLink(it) }.distinct()
    }
    
    private suspend fun extractVideo(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return loadExtractorWithFallback(url = url, referer = referer, subtitleCallback = subtitleCallback, callback = callback)
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(text)
        return numberMatch?.groupValues?.get(1)?.split(".")?.firstOrNull()?.toIntOrNull()
    }
}
