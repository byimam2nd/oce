package com.Donghuastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=special&sub=&order=update" to "Special Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = executeWithRetry {
            rateLimitDelay()
            app.get("$mainUrl/${request.data}$page", timeout = AutoUsedConstants.FAST_TIMEOUT).documentLarge
        }
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        val response = newHomePageResponse(HomePageList(request.name, home, false), true)
        return response
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").ifEmpty {
            this
                .selectFirst("div.bsx a")
                ?.attr("title")
                .orEmpty()
        }
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("div.bsx a img")?.extractImageAttr() ?: this.selectFirst("div.bsx img")?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
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
                                    .get("$mainUrl/pagg/$page/?s=$query", timeout = AutoUsedConstants.FAST_TIMEOUT)
                                    .documentLarge
                            }
                            document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
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

        val title =
            document.selectFirst("h1.entry-title")?.text()?.trim() ?: document.selectFirst("h1.title")?.text()?.trim()
                ?: "Unknown Title"
        var poster =
            document.selectFirst("div.thumb > img")?.extractImageAttr()
                ?: document.selectFirst("img.ts-post-image")?.extractImageAttr()
                ?: ""
        val description =
            document.selectFirst("div.entry-content")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: ""

        val typeText = document.selectFirst(".spe")?.text() ?: ""
        val isMovie = typeText.contains("Movie", ignoreCase = true) || url.contains("-movie-", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.Anime) {
            val episodes = document
                .select(".eplister li")
                .mapNotNull { info ->
                    val epLink = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val episodeText = info
                        .selectFirst(".epl-num")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                    val episodeNumber = Regex("""(\d+(?:\.\d+)?)""")
                        .find(episodeText)
                        ?.groupValues
                        ?.get(1)
                        ?.split(".")
                        ?.firstOrNull()
                        ?.toIntOrNull()
                    val episodeTitle = info.selectFirst(".epl-title")?.text()?.trim() ?: ""

                    newEpisode(epLink) {
                        this.name =
                            episodeTitle.replace(title, "", ignoreCase = true).trim().ifEmpty {
                                "Episode ${episodeNumber ?: "?"}"
                            }
                        this.episode = episodeNumber
                        this.posterUrl = info.selectFirst("a img")?.extractImageAttr()
                    }
                }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieLink) {
                this.posterUrl = poster.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = executeWithRetry {
            rateLimitDelay()
            app.get(data, timeout = AutoUsedConstants.FAST_TIMEOUT).documentLarge
        }
        val options = html.select("option[data-index]")

        coroutineScope {
            options
                .map { option ->
                    async {
                        val base64 = option.attr("value").trim()
                        if (base64.isBlank()) return@async
                        val label = option.text().trim()
                        val decodedHtml = base64Decode(base64)
                        var iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src") ?: return@async

                        iframeUrl = when {
                            iframeUrl.startsWith("//") -> "https:$iframeUrl"
                            iframeUrl.startsWith("http") -> iframeUrl
                            else -> mainUrl + iframeUrl
                        }

                        if (iframeUrl.endsWith(".mp4")) {
                            MasterLinkGenerator.createLink(label, iframeUrl, data, getQualityFromName(label))?.let {
                                callback(it)
                            }
                        } else {
                            loadExtractorWithFallback(iframeUrl, data, subtitleCallback, callback)
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
}
