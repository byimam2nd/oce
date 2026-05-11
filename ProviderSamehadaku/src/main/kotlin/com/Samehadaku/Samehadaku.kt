package com.Samehadaku

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

// Import Master Configuration
import com.Samehadaku.SamehadakuConstants.CONFIG_NAMES
import com.Samehadaku.SamehadakuConstants.CONFIG_MAIN_URLS
import com.Samehadaku.SamehadakuConstants.CONFIG_SERIES_URLS
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_URLS
import com.Samehadaku.SamehadakuConstants.CONFIG_LANGS
import com.Samehadaku.SamehadakuConstants.CONFIG_SUPPORTED_TYPES
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_PATH_PATTERNS
import com.Samehadaku.SamehadakuConstants.CONFIG_MAIN_PAGE_PATH_PATTERNS
import com.Samehadaku.SamehadakuConstants.CONFIG_MOVIE_PATH_SEGMENTS
import com.Samehadaku.SamehadakuConstants.CONFIG_TV_PATH_SEGMENTS
import com.Samehadaku.SamehadakuConstants.CONFIG_EPISODE_DATA_URL_PATTERNS
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_PAGE_LIMITS
import com.Samehadaku.SamehadakuConstants.CONFIG_REVERSE_EPISODES
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_IS_JSON
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_ROOTS
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_TITLES
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_HREFS
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_POSTERS
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_POSTER_PREFIXES
import com.Samehadaku.SamehadakuConstants.CONFIG_SEARCH_JSON_TYPES
import com.Samehadaku.SamehadakuConstants.CONFIG_GLOBAL_HEADERS
import com.Samehadaku.SamehadakuConstants.CONFIG_MAIN_PAGE_LISTS
import com.Samehadaku.SamehadakuConstants.FOLLOW_LINK_SELECTOR
import com.Samehadaku.SamehadakuConstants.CONFIG_HREF_CLEAN_REGEXPS
import com.Samehadaku.SamehadakuConstants.CONFIG_HREF_CLEAN_REPLACES
import com.Samehadaku.SamehadakuConstants.SEARCH_ITEMS
import com.Samehadaku.SamehadakuConstants.SEARCH_TITLE
import com.Samehadaku.SamehadakuConstants.SEARCH_HREF
import com.Samehadaku.SamehadakuConstants.SEARCH_POSTER
import com.Samehadaku.SamehadakuConstants.SEARCH_RATING
import com.Samehadaku.SamehadakuConstants.SEARCH_EP_TEXT
import com.Samehadaku.SamehadakuConstants.LOAD_TITLE
import com.Samehadaku.SamehadakuConstants.LOAD_POSTER
import com.Samehadaku.SamehadakuConstants.LOAD_BANNER
import com.Samehadaku.SamehadakuConstants.LOAD_DESC
import com.Samehadaku.SamehadakuConstants.LOAD_INFO_BOX
import com.Samehadaku.SamehadakuConstants.LOAD_TAGS
import com.Samehadaku.SamehadakuConstants.LOAD_RATING
import com.Samehadaku.SamehadakuConstants.LOAD_STATUS
import com.Samehadaku.SamehadakuConstants.LOAD_QUALITY
import com.Samehadaku.SamehadakuConstants.LOAD_TRAILER
import com.Samehadaku.SamehadakuConstants.LOAD_RECOMMEND
import com.Samehadaku.SamehadakuConstants.EPISODE_ITEMS
import com.Samehadaku.SamehadakuConstants.EPISODE_HREF
import com.Samehadaku.SamehadakuConstants.EPISODE_TITLE
import com.Samehadaku.SamehadakuConstants.EPISODE_NUM
import com.Samehadaku.SamehadakuConstants.EPISODE_DESC
import com.Samehadaku.SamehadakuConstants.EPISODE_TIME
import com.Samehadaku.SamehadakuConstants.LINK_OPTIONS
import com.Samehadaku.SamehadakuConstants.DOWNLOAD_ITEMS
import com.Samehadaku.SamehadakuConstants.ACTOR_ITEMS
import com.Samehadaku.SamehadakuConstants.ACTOR_NAME
import com.Samehadaku.SamehadakuConstants.ATTR_TITLE
import com.Samehadaku.SamehadakuConstants.ATTR_IMAGE
import com.Samehadaku.SamehadakuConstants.ATTR_HREF
import com.Samehadaku.SamehadakuConstants.ATTR_VALUE
import com.Samehadaku.SamehadakuConstants.ATTR_CONTENT
import com.Samehadaku.SamehadakuConstants.BLOAT_REGEX
import com.Samehadaku.SamehadakuConstants.DEFAULT_TIMEOUT
import com.Samehadaku.SamehadakuConstants.VAL_REFERER
import com.Samehadaku.SamehadakuConstants.STR_DUB
import com.Samehadaku.SamehadakuConstants.STR_ONGOING
import com.Samehadaku.SamehadakuConstants.STR_EPISODE
import com.Samehadaku.SamehadakuConstants.STR_SERIES
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_IS_HORIZONTAL
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_YEAR_EXTRACTOR
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_YEAR_SELECTOR
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_REFERER_PLAYER
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_IFRAME_SELECTORS

/**
 * 🚀 ULTIMATE HTML SCRAPING ENGINE - VERSION 12.0 (INDUSTRIAL EDITION)
 * 
 * Perubahan V12:
 * 1. Configuration Caching: Akses konfigurasi O(1) untuk efisiensi CPU.
 * 2. Modular Scraper Pipeline: load() dipecah menjadi sub-modul untuk resiliensi.
 * 3. Semantic UI Engine: Kata kunci UI (Dub/Ongoing) kini dikelola via Constants.
 * 4. Contextual Logging: Debugging lebih presisi dengan log level modul.
 */

open class Samehadaku : MainAPI() {
    
    // Cache untuk mempercepat akses konfigurasi (O(1))
    private val configCache = mutableMapOf<Int, String>()
    private val configListCache = mutableMapOf<Int, List<String>>()

    protected val providerId: String by lazy { 
        this::class.java.simpleName.replace("Provider", "").replace(Regex("[^a-zA-Z0-9]"), "")
    }

    override var name = getCached(CONFIG_NAMES, "Base HTML Provider")
    override var mainUrl = getCached(CONFIG_MAIN_URLS, "https://example.com")
    open var seriesUrl = getCached(CONFIG_SERIES_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }
    open var searchUrl = getCached(CONFIG_SEARCH_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }

    override val hasMainPage = true
    override var lang = getCached(CONFIG_LANGS, "id")
    override val hasDownloadSupport = true
    override val usesWebView = true
    
    override val supportedTypes = getCached(CONFIG_SUPPORTED_TYPES, "Anime,AnimeMovie,TvSeries,Movie,AsianDrama")
        .split(",").mapNotNull { type -> 
            runCatching { TvType.entries.find { it.name.equals(type.trim(), true) } }.getOrNull() 
        }.toSet()

    open var searchPathPattern = getCached(CONFIG_SEARCH_PATH_PATTERNS, "{baseUrl}/page/{page}/?s={query}")
    open var mainPagePathPattern = getCached(CONFIG_MAIN_PAGE_PATH_PATTERNS, "{baseUrl}/{data}{page}")
    open var moviePathSegment = getCached(CONFIG_MOVIE_PATH_SEGMENTS, "/movie/")
    open var tvPathSegment = getCached(CONFIG_TV_PATH_SEGMENTS, "/anime/")
    open var episodeDataUrlPattern = getCached(CONFIG_EPISODE_DATA_URL_PATTERNS, "{url}")
    open var searchPageLimit = getCached(CONFIG_SEARCH_PAGE_LIMITS, "2").toIntOrNull() ?: 2
    open var reverseEpisodes = getCached(CONFIG_REVERSE_EPISODES, "true").toBoolean()
    open var hrefCleanRegexp = getCached(CONFIG_HREF_CLEAN_REGEXPS, "")
    open var hrefCleanReplace = getCached(CONFIG_HREF_CLEAN_REPLACES, "")
    open var isJsonSearch = getCached(CONFIG_SEARCH_IS_JSON, "false").toBoolean()
    open var searchJsonRoot = getCached(CONFIG_SEARCH_JSON_ROOTS, "data")
    open var searchJsonTitle = getCached(CONFIG_SEARCH_JSON_TITLES, "title")
    open var searchJsonHref = getCached(CONFIG_SEARCH_JSON_HREFS, "slug")
    open var searchJsonPoster = getCached(CONFIG_SEARCH_JSON_POSTERS, "poster")
    open var searchJsonPosterPrefix = getCached(CONFIG_SEARCH_JSON_POSTER_PREFIXES, "")
    open var searchJsonType = getCached(CONFIG_SEARCH_JSON_TYPES, "type")

    open var globalHeaders: Map<String, String> = getCached(CONFIG_GLOBAL_HEADERS, "User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .split("|").associate { val parts = it.split("="); if (parts.size == 2) parts[0] to parts[1] else "" to "" }.filter { it.key.isNotBlank() }

    override val mainPage = mainPageOf(*resolveMainPageList().toTypedArray())

    // UI Keywords Cached
    private val ongoingKeyword by lazy { getCached(STR_ONGOING, "Ongoing") }
    private val dubKeyword by lazy { getCached(STR_DUB, "dub") }
    private val seriesKeyword by lazy { getCached(STR_SERIES, "Series") }
    private val episodeKeyword by lazy { getCached(STR_EPISODE, "Episode") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return runCatching {
            val baseUrl = if (request.name.contains(seriesKeyword, true) && seriesUrl.isNotBlank()) seriesUrl else mainUrl
            val url = if (request.data.startsWith("http")) { 
                val d = request.data.replace("{page}", page.toString())
                val pagePattern = Regex("""(/page/|page=)$page(\b|/|$)""")
                if (!pagePattern.containsMatchIn(d)) { 
                    if (d.endsWith("/page/")) "${d}$page" 
                    else { val conn = if (d.contains("?")) "&" else "?"; "${d}${conn}page=$page" } 
                } else d
            } else { 
                mainPagePathPattern.replace("{baseUrl}", baseUrl).replace("{data}", request.data).replace("{page}", page.toString()) 
            }

            val document = executeWithRetry { rateLimitDelay(); app.get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders).documentLarge }
            val isHorizontal = getCached(CONFIG_HOOK_IS_HORIZONTAL, "false").toBoolean() && request.name.contains("Episode Terbaru", true)
            val home = document.selectSafeList(SEARCH_ITEMS).mapNotNull { runCatching { it.toSearchResult(url) }.getOrNull() }
            newHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
        }.getOrElse { e -> 
            logError(providerId, "MainPage Failure: ${e.message}")
            newHomePageResponse(request.name, emptyList(), false) 
        }
    }
  
    private fun Element.toSearchResult(baseUrl: String? = null): SearchResponse? {
        return runCatching {
            val base = baseUrl ?: mainUrl
            val titleEl = this.selectSafe(SEARCH_TITLE) ?: this.parent()?.selectSafe(SEARCH_TITLE) ?: this.selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.attrSafe(ATTR_TITLE) ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX)
            val hrefEl = this.selectSafe(SEARCH_HREF) ?: this.selectFirst("a") ?: this.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            val cleanRegex = hrefCleanRegexp; val cleanReplace = hrefCleanReplace
            if (cleanRegex.isNotBlank() && cleanReplace.isNotBlank()) { href = href.replace(Regex(cleanRegex), cleanReplace) }
            val poster = this.selectSafe(SEARCH_POSTER)?.safeExtractImage(ATTR_IMAGE); val rating = this.selectSafe(SEARCH_RATING)?.text(); val eps = this.selectSafe(SEARCH_EP_TEXT)?.text()?.safeExtractEpNum()
            val isMovie = (moviePathSegment.isNotBlank() && href.contains(moviePathSegment)) || href.contains("movie", true)
            val type = if (isMovie) TvType.Movie else if (supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
            newAnimeSearchResponse(title, href, type) { this.posterUrl = poster; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.score = Score.from10(rating)
                addDubStatus(dubExist = this@toSearchResult.text().contains(dubKeyword, true), subExist = true, subEpisodes = eps) }
        }.getOrNull()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = if (searchUrl.isNotBlank()) searchUrl else mainUrl; val refer = app.get(mainUrl).url
        if (isJsonSearch) { return runCatching {
                val url = searchPathPattern.replace("{baseUrl}", baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
                val response = app.get(url, referer = refer, headers = globalHeaders).text; val root = JSONObject(response)
                val items = if (searchJsonRoot.isBlank()) root.getJSONArray("results") else root.getJSONArray(searchJsonRoot)
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) { val item = items.getJSONObject(i)
                    val title = item.optString(searchJsonTitle).safeCleanBloat(item.optString(searchJsonTitle), BLOAT_REGEX)
                    val slug = item.optString(searchJsonHref); var pUrl = item.optString(searchJsonPoster)
                    if (!pUrl.startsWith("http") && searchJsonPosterPrefix.isNotBlank()) pUrl = searchJsonPosterPrefix + pUrl
                    val isTv = item.optString(searchJsonType).contains("series", true) || item.optString(searchJsonType).contains("tv", true)
                    var finalUrl = if (isTv) "$seriesUrl/$slug" else "$mainUrl/$slug"
                    results.add(newAnimeSearchResponse(title, finalUrl, if (isTv) TvType.TvSeries else TvType.Movie) { this.posterUrl = pUrl; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) } })
                }
                results
            }.getOrElse { e -> logError(providerId, "JSON Search Failed: ${e.message}"); emptyList() }
        }
        return coroutineScope { (1..searchPageLimit).map { page -> async { runCatching { rateLimitDelay(); val url = searchPathPattern.replace("{baseUrl}", baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
                        val document = app.get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders, referer = refer).documentLarge
                        document.selectSafeList(SEARCH_ITEMS).mapNotNull { runCatching { it.toSearchResult(url) }.getOrNull() } }.getOrElse { emptyList() } } }.awaitAll().flatten().distinctBy { it.url } }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse { return loadRecursive(url, 0) }

    private suspend fun loadRecursive(url: String, depth: Int): LoadResponse {
        val response = executeWithRetry { rateLimitDelay(); app.get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders) }
        val document = response.documentLarge; val currentUrl = response.url
        if (depth < 2) { val follow = getCachedList(FOLLOW_LINK_SELECTOR)
            if (follow.isNotEmpty()) { val nextAnchor = document.selectSafe(follow); val nextHref = nextAnchor?.attr("href")
                if (!nextHref.isNullOrBlank()) { val nextUrl = fixUrlSmart(nextHref, currentUrl); if (nextUrl != currentUrl && nextUrl != url) return loadRecursive(nextUrl, depth + 1) } } }

        // Pipeline: Metadata
        val metadata = extractMetadata(document, currentUrl)
        
        // Pipeline: Recommendations & Actors
        val recommendations = document.selectSafeList(LOAD_RECOMMEND).mapNotNull { it.toSearchResult(currentUrl) }
        val actors = document.selectSafeList(ACTOR_ITEMS).mapNotNull { val n = it.selectSafe(ACTOR_NAME)?.text() ?: ""; val p = it.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: ""; if (n.isNotBlank()) Actor(n, p) else null }
        
        // Pipeline: Episode Processing
        val epItems = document.selectSafeList(EPISODE_ITEMS); val seasonDataScript = document.selectFirst("script#season-data")
        val isMovie = (seasonDataScript == null && document.selectFirst(".tvseason") == null) && ((moviePathSegment.isNotBlank() && currentUrl.contains(moviePathSegment)) || epItems.isEmpty())
        val type = if (isMovie) TvType.Movie else if (supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
        val tracker = runCatching { APIHolder.getTracker(listOf(metadata.title), TrackerType.getTypes(type), metadata.year, true) }.getOrNull()

        if (isMovie) {
            val watchUrl = fixUrlSmart(document.selectSafe(listOf(".play-button", ".watch-now", ".btn-watch"))?.attr("href"), currentUrl).ifBlank { currentUrl }
            return newMovieLoadResponse(metadata.title, url, type, episodeDataUrlPattern.replace("{url}", watchUrl)) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner
                this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }; this.year = metadata.year; this.score = Score.from10(metadata.rating)
                this.recommendations = recommendations; this.comingSoon = metadata.statusText?.contains("Coming Soon", true) ?: false
                addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) }
        } else { 
            val episodes = extractEpisodes(document, currentUrl, seasonDataScript, epItems, metadata.poster)
            return if (type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie) {
                newAnimeLoadResponse(metadata.title, url, type) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addEpisodes(DubStatus.Subbed, episodes); addTrailer(metadata.trailer); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()) }
            } else { newTvSeriesLoadResponse(metadata.title, url, type, episodes) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) } }
        }
    }

    private fun extractMetadata(document: Document, currentUrl: String): MetadataPackage {
        val rawTitle = document.selectSafe(LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX)
        val poster = document.selectSafe(LOAD_POSTER)?.safeExtractImage(ATTR_IMAGE) ?: ""
        val banner = document.selectSafe(LOAD_BANNER)?.safeExtractImage(ATTR_IMAGE)
        val description = document.selectSafe(LOAD_DESC)?.text()?.trim() ?: ""
        val infoText = document.selectSafeList(LOAD_INFO_BOX).text()
        val year = infoText.safeExtractYear() ?: run {
            val selector = getCached(CONFIG_HOOK_YEAR_SELECTOR, ""); val regexStr = getCached(CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) { Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull() } else null
        }
        val statusText = document.selectSafe(LOAD_STATUS)?.text()
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description, 
            year = year, statusText = statusText,
            tags = document.selectSafeList(LOAD_TAGS).map { it.text() },
            rating = document.selectSafe(LOAD_RATING)?.text(),
            status = if (statusText?.contains(ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attrSafe(ATTR_HREF)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectFirst("a[href*='themoviedb.org/']")?.attrSafe(ATTR_HREF)?.split("/")?.lastOrNull()?.toIntOrNull(),
            trailer = document.selectSafe(LOAD_TRAILER)?.let { if (it.tagName() == "iframe") it.safeExtractImage(ATTR_IMAGE) else it.attrSafe(ATTR_HREF) }
        )
    }

    private fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${episodeKeyword} ${ep.optInt("episode_no")}" }) } } } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectSafe(EPISODE_HREF) ?: ep.selectFirst("a") ?: return@runCatching null
                val href = episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectSafe(EPISODE_TITLE) ?: ep.selectFirst("a")
                val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectSafe(EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
                val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectSafe(EPISODE_DESC)?.text()?.trim()
                    this.runTime = ep.selectSafe(EPISODE_TIME)?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster } }.getOrNull() }) }
        return if (reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val response = executeWithRetry { rateLimitDelay(); app.get(data, timeout = DEFAULT_TIMEOUT, headers = globalHeaders) }
            val document = response.documentLarge; val currentUrl = response.url
            val attrValueSelectors = getCachedList(ATTR_VALUE)
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()

            // AGGRESSIVE GATHERING V12
            getCachedList(LINK_OPTIONS).forEach { selector ->
                document.select(selector).forEach { container ->
                    val anchors = container.select("a")
                    if (anchors.isNotEmpty()) anchors.forEach { a -> allPossibleLinks.add(a.attr("href") to a.text()) }
                    else { val raw = container.attrSafe(attrValueSelectors) ?: container.attr("href") ?: ""; if (raw.isNotBlank()) allPossibleLinks.add(raw to container.text()) }
                }
            }

            getCachedList(DOWNLOAD_ITEMS).forEach { selector ->
                document.select(selector).forEach { container ->
                    container.select("a").forEach { a -> val href = a.attr("href"); if (href.isNotBlank()) allPossibleLinks.add(href to a.text()) }
                }
            }

            document.select("iframe").forEach { el ->
                listOf("src", "data-src", "data-link").forEach { attr -> val s = el.attr(attr); if (s.isNotBlank()) allPossibleLinks.add(s to null) }
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() }.map { (raw, label) -> async { runCatching {
                    val decodedRaw = if (!raw.startsWith("http") && !raw.startsWith("//") && !raw.startsWith("/") && raw.safeIsBase64()) {
                        val dec = raw.safeDecode()
                        if (dec.contains("iframe")) Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: raw
                        else if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) dec else raw
                    } else raw

                    val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify().unpackPacked()
                    if (fixedUrl.isBlank()) return@runCatching

                    val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, callback) }.getOrDefault(false)
                    if (!okDirect) {
                        val refererMode = getCached(CONFIG_HOOK_REFERER_PLAYER, "current_url")
                        val refererForPlayer = if (refererMode == "series_url") "$seriesUrl/" else currentUrl
                        val playerDoc = app.get(fixedUrl, referer = refererForPlayer, headers = globalHeaders).document
                        val iframeSelectors = getCachedList(CONFIG_HOOK_IFRAME_SELECTORS)
                        val iframeSrc = iframeSelectors.asSequence().mapNotNull { playerDoc.selectFirst(it)?.attr("src") }.firstOrNull() ?: return@runCatching
                        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
                        val refererForExtractor = getBaseUrl(fixedUrl)
                        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, callback) }.getOrDefault(false)
                        if (!okRecursive && (finalIframe.contains(".mp4") || finalIframe.contains(".m3u8") || finalIframe.contains(".mkv") || finalIframe.contains(".mpd"))) {
                            MasterLinkGenerator.createSmartLink(label ?: name, finalIframe, refererForExtractor, callback = callback)
                        }
                    }
                }.getOrElse { e -> logDebug(providerId, "Link Processor Error: ${e.message}") } } }.awaitAll()
            }
            true
        }.getOrElse { e -> logError(providerId, "LoadLinks Critical Failure: ${e.message}"); false }
    }

    // --- HIGH-STABILITY CONFIG ENGINE (V12.1) ---

    private fun getCached(list: List<String>, default: String): String {
        return configCache.getOrPut(list.hashCode()) { resolveConfig(list, default) }
    }

    private fun getCachedList(list: List<String>): List<String> {
        return configListCache.getOrPut(list.hashCode()) { resolveConfigList(list) }
    }

    private fun resolveConfig(list: List<String>, default: String): String {
        for (item in list) { if (item.contains(":::")) { val owners = item.substringBefore(":::").split(","); if (owners.contains(providerId)) { val v = item.substringAfter(":::"); if (v.isBlank()) break; return v } } }
        for (item in list) { if (item.startsWith("GLOBAL:::")) return item.substringAfter(":::"); if (!item.contains(":::")) return item }
        return default
    }

    private fun resolveConfigList(list: List<String>): List<String> {
        val result = mutableListOf<String>(); for (item in list) { if (item.contains(":::")) { val owners = item.substringBefore(":::").split(","); if (owners.contains(providerId)) { val v = item.substringAfter(":::"); if (v.isNotBlank()) result.add(v) } } }
        if (result.isNotEmpty()) return result
        for (item in list) { val v = if (item.contains(":::")) { if (item.startsWith("GLOBAL:::")) item.substringAfter(":::") else continue } else item; if (v.isNotBlank()) result.add(v) }
        return result
    }

    private fun resolveMainPageList(): List<Pair<String, String>> {
        val raw = getCached(CONFIG_MAIN_PAGE_LISTS, "trending/page/|Sedang Tren")
        return raw.split(";").mapNotNull { val parts = it.split("|"); if (parts.size == 2) parts[0] to parts[1] else null }
    }

    private fun Element.selectSafe(selectors: List<String>): Element? {
        if (selectors.isEmpty()) return null
        // Pass 1: Provider Specific
        for (s in selectors) { if (!s.contains(":::")) continue
            val owners = s.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
                val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val el = this.selectFirst(sel); if (el != null) return el }
            }
        }
        // Pass 2: Global Fallback
        for (s in selectors) {
            val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
            if (sel.isNotBlank()) { val el = this.selectFirst(sel); if (el != null) return el }
        }
        return null
    }

    private fun Element.selectSafeList(selectors: List<String>): org.jsoup.select.Elements {
        if (selectors.isEmpty()) return org.jsoup.select.Elements()
        // Pass 1: Provider Specific
        for (s in selectors) { if (!s.contains(":::")) continue
            val owners = s.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
                val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val els = this.select(sel); if (els.isNotEmpty()) return els }
            }
        }
        // Pass 2: Global Fallback
        for (s in selectors) {
            val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
            if (sel.isNotBlank()) { val els = this.select(sel); if (els.isNotEmpty()) return els }
        }
        return org.jsoup.select.Elements()
    }

    private fun Element.attrSafe(attributes: List<String>): String? {
        // Pass 1: Provider Specific
        for (a in attributes) { if (!a.contains(":::")) continue
            val owners = a.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
                val attrN = a.substringAfter(":::"); val v = this.attr(attrN); if (v.isNotBlank()) return v
            }
        }
        // Pass 2: Global Fallback
        for (a in attributes) {
            val attrN = if (a.startsWith("GLOBAL:::")) a.substringAfter(":::") else if (!a.contains(":::")) a else continue
            val v = this.attr(attrN); if (v.isNotBlank()) return v
        }
        return null
    }

    // Helper Data Class for Pipeline
    data class MetadataPackage(
        val title: String, val poster: String, val banner: String?, val description: String,
        val year: Int?, val statusText: String?, val tags: List<String>, val rating: String?,
        val status: ShowStatus, val imdbId: String?, val tmdbId: Int?, val trailer: String?
    )
}
