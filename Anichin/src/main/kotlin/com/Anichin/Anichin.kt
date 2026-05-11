package com.Anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.cafe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "seri/?status=&type=&order=popular&page=" to "Popular Donghua",
        "seri/?status=&type=&order=update&page=" to "Recently Updated",
        "seri/?sub=&order=latest&page=" to "Latest Added",
        "seri/?status=ongoing&type=&order=update&page=" to "Ongoing",
        "seri/?status=completed&type=&order=update&page=" to "Completed",
    )

    private val EPS_REGEX = Regex("""\d+""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = executeWithRetry {
            rateLimitDelay()
            app
                .get(
                    "$mainUrl/${request.data}$page",
                    timeout = AutoUsedConstants.DEFAULT_TIMEOUT
                ).documentLarge
        }

        val home = document.select("div.listupd > article").mapNotNull {
            runCatching { it.toSearchResult() }.getOrElse { null }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true
        )
    }

    private suspend fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("div.bsx > a")?.attr("title") ?: ""

        val href = fixUrl(
            this.selectFirst("div.bsx > a")?.attr("href")
                ?: ""
        )

        val posterUrl = fixUrlNull(
            this.selectFirst("div.bsx img")?.attr("src")
                ?: ""
        )

        val episodeSub = this.selectFirst("div.bsx span.epx")?.text()?.let { EPS_REGEX.find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, dubEpisodes = null, subEpisodes = episodeSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            (1..3)
                .map { page ->
                    async {
                        try {
                            rateLimitDelay()
                            val searchUrl = if (page ==
                                1
                            ) {
                                "$mainUrl/?s=$encodedQuery"
                            } else {
                                "$mainUrl/page/$page/?s=$encodedQuery"
                            }

                            val document = app
                                .get(
                                    searchUrl,
                                    timeout = AutoUsedConstants.DEFAULT_TIMEOUT
                                ).documentLarge

                            document.select("div.listupd > article").mapNotNull {
                                runCatching { it.toSearchResult() }.getOrElse { null }
                            }
                        } catch (e: Exception) {
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
            app
                .get(
                    url,
                    timeout = AutoUsedConstants.DEFAULT_TIMEOUT
                ).documentLarge
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1.title")?.text()?.trim()
            ?: "Unknown Title"

        val href = document.selectFirst(".eplister li > a")?.attr("href")
            ?: ""

        var poster = fixUrl(
            document.selectFirst("div.thumb img")?.attr("src")
                ?: ""
        )

        val description = document.selectFirst("div.entry-content")?.text()?.trim()
            ?: document.selectFirst("div.description")?.text()?.trim() ?: ""

        val type = document.selectFirst(".spe")?.text() ?: ""
        val isMovie = type.contains("Movie", ignoreCase = true) || url.contains("-movie-", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val statusText = document.select(".spe").text().lowercase()
        val showStatus = when {
            "ongoing" in statusText -> ShowStatus.Ongoing
            "completed" in statusText -> ShowStatus.Completed
            else -> null
        }

        return if (tvType == TvType.Anime) {
            val episodes = document
                .select(".eplister li")
                .mapNotNull { info ->
                    val href1 = info.selectFirst("a")?.attr("href") ?: ""
                    if (href1.isEmpty()) return@mapNotNull null

                    val episodeText = info
                        .selectFirst(".epl-num")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                    val episodeNumber = extractEpisodeNumber(episodeText)

                    val episodeTitle = info.selectFirst(".epl-title")?.text()?.trim() ?: ""
                    val cleanName = episodeTitle.replace(title, "", ignoreCase = true).trim()

                    val posterr = info
                        .selectFirst("div.thumb img")
                        ?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                        ?: poster

                    newEpisode(href1) {
                        this.name = cleanName.ifEmpty { episodeTitle }
                        this.episode = episodeNumber
                        this.posterUrl = posterr
                    }
                }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.showStatus = showStatus
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, href) {
                this.posterUrl = poster
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
            app
                .get(
                    data,
                    timeout = AutoUsedConstants.DEFAULT_TIMEOUT
                ).documentLarge
        }

        val options = html
            .select("option[data-index]")
            .ifEmpty { html.select("option[value]") }

        if (options.isEmpty()) return false

        var successCount = 0

        supervisorScope {
            options
                .map { option ->
                    async {
                        try {
                            val base64 = option.attr("value").trim()
                            if (base64.isBlank()) return@async

                            val label = option.text().trim()
                            val decodedHtml = base64Decode(base64)
                            val iframeUrl = Jsoup
                                .parse(decodedHtml)
                                .selectFirst("iframe")
                                ?.attr("src")
                                ?.let(::httpsify)

                            if (iframeUrl.isNullOrEmpty()) return@async

                            if (iframeUrl.endsWith(".mp4")) {
                                MasterLinkGenerator.createLink(label, iframeUrl, data, getQualityFromName(label))?.let {
                                    callback(it)
                                    successCount++
                                }
                            } else {
                                if (loadExtractorWithFallback(iframeUrl, data, subtitleCallback, callback)) {
                                    successCount++
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
        }

        // Fixed AtomicInteger comparison
        return successCount > 0
    }

    private fun httpsify(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private fun extractEpisodeNumber(text: String): Int? {
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(text)
        return numberMatch
            ?.groupValues
            ?.get(1)
            ?.split(".")
            ?.firstOrNull()
            ?.toIntOrNull()
    }
}
