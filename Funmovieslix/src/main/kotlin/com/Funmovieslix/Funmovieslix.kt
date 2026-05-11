package com.Funmovieslix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class Funmovieslix : MainAPI() {
    override var mainUrl = "https://funmovieslix.com"
    override var name = "Funmovieslix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "category/action" to "Action Category",
        "category/science-fiction" to "Sci-Fi Category",
        "category/drama" to "Drama Category",
        "category/kdrama" to "KDrama",
        "category/crime" to "Crime Category",
        "category/fantasy" to "Fantasy Category",
        "category/mystery" to "Mystery Category",
        "category/comedy" to "Comedy Category",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = executeWithRetry {
            rateLimitDelay()
            app.get("$mainUrl/${request.data}/page/$page", timeout = AutoUsedConstants.FAST_TIMEOUT).documentLarge
        }
        val home = document.select("#gmr-main-load div.movie-card").mapNotNull {
            runCatching { it.toSearchResult() }
                .getOrNull()
        }
        val response = newHomePageResponse(HomePageList(request.name, home, false), true)
        return response
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text().ifEmpty { this.selectFirst("a")?.attr("title").orEmpty() }
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = this
            .select("a img")
            .firstOrNull()
            ?.let { img ->
                val srcSet = img.attr("srcset")
                if (srcSet.isNotBlank()) {
                    srcSet
                        .split(",")
                        .map { it.trim() }
                        .maxByOrNull {
                            it.substringAfterLast(" ").removeSuffix("w").toIntOrNull()
                                ?: 0
                        }?.substringBefore(" ")
                } else {
                    img.attr("src").ifEmpty { img.attr("data-src") }
                }
            }?.let { fixUrlNull(it.replace(Regex("-\\d+x\\d+"), "")) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = extractQuality(this@toSearchResult)
            this.score =
                Score.from10(
                    this@toSearchResult
                        .select("div.rating-stars")
                        .text()
                        .substringAfter("(")
                        .substringBefore(")")
                )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..3)
                .map { page ->
                    async {
                        try {
                            val document = executeWithRetry {
                                rateLimitDelay()
                                app
                                    .get("$mainUrl?s=$query&page=$page", timeout = AutoUsedConstants.DEFAULT_TIMEOUT)
                                    .documentLarge
                            }
                            document.select("#gmr-main-load div.movie-card").mapNotNull {
                                runCatching { it.toSearchResult() }
                                    .getOrNull()
                            }
                        } catch (_: Exception) {
                            emptyList<SearchResponse>()
                        }
                    }
                }.awaitAll()
                .flatten()
                .distinctBy { it.url }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = executeWithRetry {
            rateLimitDelay()
            app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).documentLarge
        }

        val title = (
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
        ).substringBefore("(")
            .substringBefore("-")
            .trim()
        val poster =
            document.selectFirst("div.poster img")?.extractImageAttr()
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ""
        val description =
            document.selectFirst("div.desc-box p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: ""
        val type = if (url.contains("tv")) TvType.TvSeries else TvType.Movie

        val recommendation = document.select("div.movie-grid div").mapNotNull {
            val img = it.selectFirst("img")
            val srcSet = img?.attr("srcset").orEmpty()
            val bestUrl = if (srcSet.isNotBlank()) {
                srcSet
                    .split(",")
                    .map { s ->
                        s.trim()
                    }.maxByOrNull { s ->
                        s.substringAfterLast(" ").removeSuffix("w").toIntOrNull()
                            ?: 0
                    }?.substringBefore(" ")
            } else {
                img?.attr("src")
            }
            newMovieSearchResponse(it.select("p").text(), it.select("a").attr("href"), TvType.Movie) {
                this.posterUrl =
                    fixUrlNull(bestUrl?.replace(Regex("-\\d+x\\d+"), ""))
            }
        }

        return if (type == TvType.TvSeries) {
            val episodes = document.select("div.gmr-listseries a").mapNotNull { info ->
                if (info.text().contains("All episodes", true)) return@mapNotNull null
                val ep = Regex("Eps(\\d+)")
                    .find(info.text())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                newEpisode(info.attr("href")) {
                    this.episode = ep
                    this.name = "Episode $ep"
                    this.season =
                        Regex("S(\\d+)")
                            .find(info.text())
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags =
                    document.select("div.gmr-moviedata:contains(Genre) a,span.badge").map { it.text() }
                this.year =
                    document.select("div.gmr-moviedata:contains(Year) a").text().toIntOrNull()
                addTrailer(document.select("meta[itemprop=embedUrl]").attr("content"))
                addActors(document.select("div.cast-grid a").map { it.text() })
                this.recommendations =
                    recommendation
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags =
                    document.select("div.gmr-moviedata:contains(Genre) a,span.badge").map { it.text() }
                this.year =
                    document.select("div.gmr-moviedata:contains(Year) a").text().toIntOrNull()
                addTrailer(document.select("meta[itemprop=embedUrl]").attr("content"))
                addActors(document.select("div.cast-grid a").map { it.text() })
                this.recommendations =
                    recommendation
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = executeWithRetry {
            rateLimitDelay()
            app.get(data, timeout = AutoUsedConstants.FAST_TIMEOUT).documentLarge
        }

        val urlSet = mutableSetOf<String>()

        // Strategy 1: Extract from "const embeds" in script tags
        document.select("script").map { it.data() }.firstOrNull { it.contains("const embeds") }?.let { script ->
            Regex("""https?:\\/\\/[^"]+""").findAll(script).forEach { match ->
                urlSet.add(match.value.replace("\\/", "/").replace("\\", ""))
            }
        }

        // Strategy 2: Fallback - extract iframe URLs directly from HTML
        document.select("iframe[src]").map { it.attr("src") }.filter { it.isNotBlank() }.forEach { url ->
            urlSet.add(url.replace("\\/", "/").replace("\\", ""))
        }

        // Strategy 3: Extract from data attributes
        document.select("[data-src], [data-url], [data-link]").forEach { el ->
            val link = el.attr("data-src").ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-link") }
            if (link.isNotBlank()) urlSet.add(link)
        }

        if (urlSet.isEmpty()) return false

        coroutineScope {
            urlSet
                .map { url ->
                    async {
                        try {
                            val fixedUrl = when {
                                url.startsWith("http") -> url
                                url.startsWith("//") -> "https:$url"
                                else -> mainUrl + (if (url.startsWith("/")) "" else "/") + url
                            }
                            if (!loadExtractorWithFallback(url = fixedUrl, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)) {
                                MasterLinkGenerator
                                    .createLink(source = "Funmovieslix", url = fixedUrl, referer = mainUrl)
                                    ?.let { callback(it) }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
        }
        return true
    }

    private fun Element.extractImageAttr(): String {
        val attrs = listOf(
            "data-src",
            "src",
            "data-original",
            "data-lazy-src",
            "data-srcset",
            "",
        )
        return attrs
            .asSequence()
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() }
            ?.split(" ")
            ?.firstOrNull() ?: ""
    }

    private fun extractQuality(element: Element): SearchQuality {
        val q = element.select("div.quality-badge").text().uppercase()
        return when {
            "HDTS" in q || "HDCAM" in q -> SearchQuality.HdCam
            "CAM" in q -> SearchQuality.Cam
            "HDRIP" in q || "WEBRIP" in q || "WEB-DL" in q -> SearchQuality.WebRip
            "BLURAY" in q -> SearchQuality.BlueRay
            "4K" in q -> SearchQuality.FourK
            else -> SearchQuality.HD
        }
    }
}
