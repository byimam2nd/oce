package com.anichinCopy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * ANICHIN COPY - VERSION 3.0 (Powered by ULTIMATE TemplatesProvider)
 */

class AnichinCopy : MainAPI() {
    // ============================================
    // REGION 1: CONFIGURATION (URLs & Metadata)
    // ============================================
    override var mainUrl = "https://anichin.cafe"
    var seriesUrl = "https://anichin.cafe"
    var searchUrl = "https://anichin.cafe"

    override var name = "AnichinCopy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie, TvType.AsianDrama
    )

    // --- Advanced Config ---
    var searchPathPattern = "{baseUrl}/page/{page}/?s={query}"
    var searchPageLimit = 2
    var reverseEpisodes = true
    var globalHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "seri/?status=&type=&order=popular&page=" to "Popular Donghua",
        "seri/?status=&type=&order=update&page=" to "Recently Updated",
        "seri/?sub=&order=latest&page=" to "Latest Added",
        "seri/?status=ongoing&type=&order=update&page=" to "Ongoing",
        "seri/?status=completed&type=&order=update&page=" to "Completed",
    )

    companion object {
        const val TAG = "AnichinCopy"
        const val DEFAULT_TIMEOUT = 10000L

        // --- Search & Home Selectors ---
        val SEARCH_ITEMS =
            listOf("article", "div.listupd > article", "div.listupd div.bs", "div.animposx", "article figure", ".listupd .bsx", ".item", ".post-item")
        val SEARCH_TITLE = listOf("h2", "h3", ".title", "a[title]", ".entry-title", "div.tt", "div.lftinfo h2")
        val SEARCH_HREF = listOf("a")
        val SEARCH_POSTER = listOf("img")
        val SEARCH_RATING = listOf(".rating", ".score", ".num-rating", "span.rating", "div.rating i")
        val SEARCH_EP_TEXT = listOf(".ep", ".episode", ".epx", "span.episode", "span.epx")

        // --- Detail Load Selectors ---
        val LOAD_TITLE =
            listOf("h1.entry-title", "h1.title", ".entry-title", ".post-title", "div.movie-info h1", "div.infox h1", "h1[itemprop=headline]")
        val LOAD_POSTER =
            listOf(".thumb img", ".poster img", "div.bigcontent img", "img.ts-post-image", "meta[property=og:image]", ".wp-post-image")
        val LOAD_BANNER = listOf(".banner img", ".backdrop img", "meta[property=og:image:secure_url]")
        val LOAD_DESC =
            listOf(".entry-content", ".description", ".plot", "div.desc p", "div.description p", "div.sinopsis p", "div.entry-content p", "meta[name=description]")
        val LOAD_INFO_BOX = listOf(".spe", ".info-content", ".metadata", ".info", ".content-post", "div.infox div.spe")
        val LOAD_TAGS = listOf("div.genre-info a", "div.tag-list span", ".genre a", ".tag-list a")
        val LOAD_RATING =
            listOf(".rating", ".imdb-rating", ".score", ".rating-value", "div.info-tag strong", "div.rating strong")
        val LOAD_STATUS = listOf(".status", "div.info-content:contains(Status)", ".spe span:contains(Status)")
        val LOAD_QUALITY = listOf(".quality", ".video-quality", "span.quality", "div.info-content:contains(Quality)")
        val LOAD_TRAILER =
            listOf("iframe[src*='youtube.com/embed/']", "a[href*='youtube.com/watch']", "a[href*='youtu.be']", "ul.action-left > li:nth-child(3) > a", "div.trailer iframe")
        val LOAD_RECOMMEND =
            listOf(".recommendations article", ".related-post article", ".similar article", ".related article", "li.slider article", "div.related-series article")

        // --- Episode Selectors ---
        val EPISODE_ITEMS =
            listOf(".eplister li", ".ep-list li", ".list-episode li", ".list-ep li", "div.lstepsiode ul li", "ul#daftarepisode > li")
        val EPISODE_HREF = listOf("a")
        val EPISODE_TITLE = listOf(".epl-title", ".ep-title", ".title")
        val EPISODE_NUM = listOf(".epl-num", ".ep-num", ".num")
        val EPISODE_DESC = listOf(".epl-sub", ".ep-desc", ".ep-overview")
        val EPISODE_TIME = listOf(".epl-time", ".ep-duration")

        // --- Link & Server Selectors ---
        val LINK_OPTIONS =
            listOf("select.mirror option", ".server-list li", ".player-option", ".dropdown-menu li", "div#downloadb li", "option[data-index]", ".mobius > .mirror > option", "ul#player-list a")
        val LINK_IFRAME_GLOBAL = listOf("iframe")
        val DOWNLOAD_ITEMS =
            listOf(".dl-wrapper a", ".download-link a", ".box-download a", ".dl-list a", "a.btn-download")

        // --- Actor Selectors ---
        val ACTOR_ITEMS = listOf(".cast-item", ".actor-item", ".cast_list li")
        val ACTOR_NAME = listOf(".name", "h4", ".cast-name")

        // --- Common Attributes ---
        val ATTR_IMAGE = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset", "data-srcset")
        val ATTR_HREF = listOf("href")
        val ATTR_VALUE = listOf("value", "data-index", "data-id", "data-url", "data-link")

        private val BLOAT_REGEX = Regex(
            "(Nonton|Anime|Subtitle\\s*Indonesia|Movie|TV|Series|Lengkap|HD|Free|\\d{3,4}p|Dual\\s*Audio|TAMAT)",
            RegexOption.IGNORE_CASE
        )
    }

    // ============================================
    // REGION 2: CORE METHODS
    // ============================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = runCatching {
        val baseUrl = if (request.data.contains("series") && seriesUrl.isNotBlank()) seriesUrl else mainUrl
        val document = executeWithRetry {
            rateLimitDelay()
            app
                .get("$baseUrl/${request.data}$page", timeout = DEFAULT_TIMEOUT, headers = globalHeaders)
                .documentLarge
        }

        val home = document.selectSafeList(SEARCH_ITEMS).mapNotNull {
            runCatching { it.toSearchResult() }.getOrNull()
        }

        newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }.getOrElse {
        newHomePageResponse(request.name, emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = if (searchUrl.isNotBlank()) searchUrl else mainUrl

        return coroutineScope {
            (1..searchPageLimit)
                .map { page ->
                    async {
                        runCatching {
                            rateLimitDelay()
                            val url = searchPathPattern
                                .replace("{baseUrl}", baseUrl)
                                .replace("{page}", page.toString())
                                .replace("{query}", encodedQuery)

                            val document = app
                                .get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders)
                                .documentLarge

                            document.selectSafeList(SEARCH_ITEMS).mapNotNull {
                                runCatching { it.toSearchResult() }.getOrNull()
                            }
                        }.getOrElse { emptyList() }
                    }
                }.awaitAll()
                .flatten()
                .distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = executeWithRetry {
            rateLimitDelay()
            app.get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders).documentLarge
        }

        val rawTitle = document.selectSafe(LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX)

        val poster = document.selectSafe(LOAD_POSTER)?.safeExtractImage(ATTR_IMAGE) ?: ""
        val banner = document.selectSafe(LOAD_BANNER)?.safeExtractImage(ATTR_IMAGE)
        val description =
            document
                .selectSafe(LOAD_DESC)
                ?.let {
                    if (it.tagName() ==
                        "meta"
                    ) {
                        it.attr("content")
                    } else {
                        it.text()
                    }
                }?.trim()
                ?: ""

        val infoText = document.selectSafeList(LOAD_INFO_BOX).text()
        val year = infoText.safeExtractYear()
        val tags = document.selectSafeList(LOAD_TAGS).map { it.text() }
        val rating = document.selectSafe(LOAD_RATING)?.text()

        // Metadata Baru: Status & Quality
        val statusText = document.selectSafe(LOAD_STATUS)?.text()
        val status = if (statusText?.contains("Ongoing", true) == true) ShowStatus.Ongoing else ShowStatus.Completed
        val quality = document.selectSafe(LOAD_QUALITY)?.text()?.filter { it.isLetterOrDigit() }

        val imdbId = document
            .selectFirst("a[href*='imdb.com/title/']")
            ?.attr("href")
            ?.split("/")
            ?.filter { it.startsWith("tt") }
            ?.firstOrNull()
        val tmdbId = document
            .selectFirst("a[href*='themoviedb.org/']")
            ?.attr("href")
            ?.split("/")
            ?.lastOrNull()
            ?.toIntOrNull()

        val trailer = document.selectSafe(LOAD_TRAILER)?.let {
            if (it.tagName() ==
                "iframe"
            ) {
                it.attr("src")
            } else {
                it.attr("href")
            }
        }

        val isMovie = url.contains("/movie/") || document.selectSafeList(EPISODE_ITEMS).isEmpty()
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        val tracker = runCatching { APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true) }
            .getOrNull()
        val recommendations = document.selectSafeList(LOAD_RECOMMEND).mapNotNull { it.toSearchResult() }

        val actors = document.selectSafeList(ACTOR_ITEMS).mapNotNull {
            val name = it.selectSafe(ACTOR_NAME)?.text() ?: ""
            val pic = it.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: ""
            if (name.isNotBlank()) Actor(name, pic) else null
        }

        val posterHeaders = globalHeaders.toMutableMap().apply { put("Referer", mainUrl) }

        return if (isMovie) {
            val dataUrl = document.selectSafe(listOf(".play-button", ".watch-now", ".btn-watch"))?.attr("href") ?: url
            newMovieLoadResponse(title, url, type, dataUrl) {
                this.posterUrl = tracker?.image ?: poster
                this.backgroundPosterUrl = tracker?.cover ?: banner
                this.posterHeaders = posterHeaders
                this.plot = description
                this.tags = tags.ifEmpty { null }
                this.year = year
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                this.comingSoon = statusText?.contains("Coming Soon", true) ?: false
                this.quality = quality
                addTrailer(trailer)
                addActors(actors)
                addMalId(tracker?.malId)
                addAniListId(tracker?.aniId?.toIntOrNull())
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        } else {
            var episodes = document.selectSafeList(EPISODE_ITEMS).mapNotNull { ep ->
                runCatching {
                    val anchor = ep.selectSafe(EPISODE_HREF) ?: return@runCatching null
                    val href = anchor.attr("href")
                    val epNum = ep.selectSafe(EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum()

                    newEpisode(href) {
                        this.name =
                            ep.selectSafe(EPISODE_TITLE)?.text()?.ifBlank { "Episode ${epNum ?: "?"}" }
                                ?: "Episode ${epNum ?: "?"}"
                        this.episode = epNum
                        this.description = ep.selectSafe(EPISODE_DESC)?.text()?.trim()
                        this.runTime = ep
                            .selectSafe(EPISODE_TIME)
                            ?.text()
                            ?.filter { it.isDigit() }
                            ?.toIntOrNull()
                        this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster
                    }
                }.getOrNull()
            }

            if (reverseEpisodes) episodes = episodes.reversed()

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = tracker?.image ?: poster
                this.backgroundPosterUrl = tracker?.cover ?: banner
                this.posterHeaders = posterHeaders
                this.plot = description
                this.tags = tags.ifEmpty { null }
                this.year = year
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                this.showStatus = status
                addTrailer(trailer)
                addActors(actors)
                addMalId(tracker?.malId)
                addAniListId(tracker?.aniId?.toIntOrNull())
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val document =
                executeWithRetry {
                    rateLimitDelay()
                    app.get(data, timeout = DEFAULT_TIMEOUT, headers = globalHeaders).documentLarge
                }
            val options = document.selectSafeList(LINK_OPTIONS)
            val downloads = document.selectSafeList(DOWNLOAD_ITEMS)

            // 1. Process Links/Iframes
            if (options.isNotEmpty()) {
                coroutineScope {
                    options
                        .map { option ->
                            async {
                                runCatching {
                                    val serverData = option.attrSafe(ATTR_VALUE)?.trim() ?: ""
                                    if (serverData.isBlank()) return@runCatching

                                    val decodedUrl = if (serverData.safeIsBase64()) {
                                        runCatching {
                                            val decoded = serverData.safeDecode()
                                            if (decoded.contains("iframe")) {
                                                Jsoup
                                                    .parse(decoded)
                                                    .selectFirst("iframe")
                                                    ?.attr("src")
                                            } else {
                                                decoded
                                            }
                                        }.getOrNull() ?: serverData
                                    } else {
                                        serverData
                                    }

                                    if (!decodedUrl.isNullOrBlank()) {
                                        val fixedUrl = decodedUrl.safeHttpsify()
                                        val success = runCatching {
                                            loadExtractorWithFallback(fixedUrl, data, subtitleCallback, callback)
                                        }.getOrDefault(false)
                                        if (!success &&
                                            (
                                                fixedUrl.contains(".mp4") ||
                                                    fixedUrl.contains(".m3u8") ||
                                                    fixedUrl.contains(".mkv")
                                            )
                                        ) {
                                            MasterLinkGenerator
                                                .createLink(name, fixedUrl, data, fixedUrl.safeGetQuality())
                                                ?.let { callback(it) }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                }
            } else {
                document.selectSafeList(LINK_IFRAME_GLOBAL).mapNotNull { it.attr("src") }.forEach {
                    runCatching { loadExtractorWithFallback(it.safeHttpsify(), data, subtitleCallback, callback) }
                }
            }

            // 2. Process Manual Downloads
            downloads.forEach { dl ->
                val href = dl.attr("href")
                if (href.isNotBlank()) {
                    runCatching { loadExtractorWithFallback(href.safeHttpsify(), data, subtitleCallback, callback) }
                }
            }

            true
        }.getOrElse { false }
    }

    // ============================================
    // REGION 3: INTERNAL HELPERS
    // ============================================

    private fun Element.toSearchResult(): SearchResponse? {
        return runCatching {
            val rawTitle = this.selectSafe(SEARCH_TITLE)?.text() ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX)
            val href = fixUrl(this.selectSafe(SEARCH_HREF)?.attr("href") ?: "")
            val poster = this.selectSafe(SEARCH_POSTER)?.safeExtractImage(ATTR_IMAGE)
            val rating = this.selectSafe(SEARCH_RATING)?.text()
            val eps = this.selectSafe(SEARCH_EP_TEXT)?.text()?.safeExtractEpNum()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.posterHeaders = globalHeaders
                this.score = Score.from10(rating)
                addDubStatus(dubExist = this@toSearchResult.text().contains("dub", true), subExist = true, subEpisodes = eps)
            }
        }.getOrNull()
    }

    private fun Element.selectSafe(selectors: List<String>): Element? {
        for (selector in selectors) {
            val el = this.selectFirst(selector)
            if (el != null) return el
        }
        return null
    }

    private fun Element.selectSafeList(selectors: List<String>): org.jsoup.select.Elements {
        for (selector in selectors) {
            val els = this.select(selector)
            if (els.isNotEmpty()) return els
        }
        return org.jsoup.select.Elements()
    }

    private fun Element.attrSafe(attributes: List<String>): String? {
        for (attr in attributes) {
            val value = this.attr(attr)
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun Element.safeExtractImage(attributes: List<String>): String = try {
        attributes
            .asSequence()
            .map { attr(it) }
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.split(" ")
            ?.firstOrNull() ?: ""
    } catch (_: Exception) {
        ""
    }
}
