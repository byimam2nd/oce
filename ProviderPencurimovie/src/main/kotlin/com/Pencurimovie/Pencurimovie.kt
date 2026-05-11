package com.Pencurimovie

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
import org.jsoup.nodes.Element
import org.json.JSONObject

// Import Master Configuration
import com.Pencurimovie.PencurimovieConstants.CONFIG_NAMES
import com.Pencurimovie.PencurimovieConstants.CONFIG_MAIN_URLS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SERIES_URLS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_URLS
import com.Pencurimovie.PencurimovieConstants.CONFIG_LANGS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SUPPORTED_TYPES
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_PATH_PATTERNS
import com.Pencurimovie.PencurimovieConstants.CONFIG_MAIN_PAGE_PATH_PATTERNS
import com.Pencurimovie.PencurimovieConstants.CONFIG_MOVIE_PATH_SEGMENTS
import com.Pencurimovie.PencurimovieConstants.CONFIG_TV_PATH_SEGMENTS
import com.Pencurimovie.PencurimovieConstants.CONFIG_EPISODE_DATA_URL_PATTERNS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_PAGE_LIMITS
import com.Pencurimovie.PencurimovieConstants.CONFIG_REVERSE_EPISODES
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_IS_JSON
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_ROOTS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_TITLES
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_HREFS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_POSTERS
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_POSTER_PREFIXES
import com.Pencurimovie.PencurimovieConstants.CONFIG_SEARCH_JSON_TYPES
import com.Pencurimovie.PencurimovieConstants.CONFIG_GLOBAL_HEADERS
import com.Pencurimovie.PencurimovieConstants.CONFIG_MAIN_PAGE_LISTS
import com.Pencurimovie.PencurimovieConstants.FOLLOW_LINK_SELECTOR
import com.Pencurimovie.PencurimovieConstants.CONFIG_HREF_CLEAN_REGEXPS
import com.Pencurimovie.PencurimovieConstants.CONFIG_HREF_CLEAN_REPLACES
import com.Pencurimovie.PencurimovieConstants.SEARCH_ITEMS
import com.Pencurimovie.PencurimovieConstants.SEARCH_TITLE
import com.Pencurimovie.PencurimovieConstants.SEARCH_HREF
import com.Pencurimovie.PencurimovieConstants.SEARCH_POSTER
import com.Pencurimovie.PencurimovieConstants.SEARCH_RATING
import com.Pencurimovie.PencurimovieConstants.SEARCH_EP_TEXT
import com.Pencurimovie.PencurimovieConstants.LOAD_TITLE
import com.Pencurimovie.PencurimovieConstants.LOAD_POSTER
import com.Pencurimovie.PencurimovieConstants.LOAD_BANNER
import com.Pencurimovie.PencurimovieConstants.LOAD_DESC
import com.Pencurimovie.PencurimovieConstants.LOAD_INFO_BOX
import com.Pencurimovie.PencurimovieConstants.LOAD_TAGS
import com.Pencurimovie.PencurimovieConstants.LOAD_RATING
import com.Pencurimovie.PencurimovieConstants.LOAD_STATUS
import com.Pencurimovie.PencurimovieConstants.LOAD_QUALITY
import com.Pencurimovie.PencurimovieConstants.LOAD_TRAILER
import com.Pencurimovie.PencurimovieConstants.LOAD_RECOMMEND
import com.Pencurimovie.PencurimovieConstants.EPISODE_ITEMS
import com.Pencurimovie.PencurimovieConstants.EPISODE_HREF
import com.Pencurimovie.PencurimovieConstants.EPISODE_TITLE
import com.Pencurimovie.PencurimovieConstants.EPISODE_NUM
import com.Pencurimovie.PencurimovieConstants.EPISODE_DESC
import com.Pencurimovie.PencurimovieConstants.EPISODE_TIME
import com.Pencurimovie.PencurimovieConstants.LINK_OPTIONS
import com.Pencurimovie.PencurimovieConstants.DOWNLOAD_ITEMS
import com.Pencurimovie.PencurimovieConstants.ACTOR_ITEMS
import com.Pencurimovie.PencurimovieConstants.ACTOR_NAME
import com.Pencurimovie.PencurimovieConstants.ATTR_TITLE
import com.Pencurimovie.PencurimovieConstants.ATTR_IMAGE
import com.Pencurimovie.PencurimovieConstants.ATTR_HREF
import com.Pencurimovie.PencurimovieConstants.ATTR_VALUE
import com.Pencurimovie.PencurimovieConstants.ATTR_CONTENT
import com.Pencurimovie.PencurimovieConstants.BLOAT_REGEX
import com.Pencurimovie.PencurimovieConstants.DEFAULT_TIMEOUT
import com.Pencurimovie.PencurimovieConstants.VAL_REFERER
import com.Pencurimovie.PencurimovieConstants.STR_DUB
import com.Pencurimovie.PencurimovieConstants.STR_ONGOING
import com.Pencurimovie.PencurimovieConstants.STR_EPISODE
import com.Pencurimovie.PencurimovieConstants.STR_SERIES
import com.Pencurimovie.PencurimovieConstants.CONFIG_HOOK_IS_HORIZONTAL
import com.Pencurimovie.PencurimovieConstants.CONFIG_HOOK_YEAR_EXTRACTOR
import com.Pencurimovie.PencurimovieConstants.CONFIG_HOOK_YEAR_SELECTOR
import com.Pencurimovie.PencurimovieConstants.CONFIG_HOOK_REFERER_PLAYER
import com.Pencurimovie.PencurimovieConstants.CONFIG_HOOK_IFRAME_SELECTORS

/**
 * 🚀 ULTIMATE HTML SCRAPING ENGINE - VERSION 11.3 (100% STABILITY)
 * 
 * Engine ini mengimplementasikan ekstraksi link "Sangat Rakus" (Aggressive Extraction)
 * guna memastikan tidak ada daftar download atau mirror yang terlewatkan.
 */

open class Pencurimovie : MainAPI() {
    
    protected val providerId: String by lazy { 
        this::class.java.simpleName.replace("Provider", "").replace(Regex("[^a-zA-Z0-9]"), "")
    }

    override var name = resolveConfig(CONFIG_NAMES, "Base HTML Provider")
    override var mainUrl = resolveConfig(CONFIG_MAIN_URLS, "https://example.com")
    open var seriesUrl = resolveConfig(CONFIG_SERIES_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }
    open var searchUrl = resolveConfig(CONFIG_SEARCH_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }

    override val hasMainPage = true
    override var lang = resolveConfig(CONFIG_LANGS, "id")
    override val hasDownloadSupport = true
    override val usesWebView = true
    
    override val supportedTypes = resolveConfig(CONFIG_SUPPORTED_TYPES, "Anime,AnimeMovie,TvSeries,Movie,AsianDrama")
        .split(",").mapNotNull { type -> 
            runCatching { TvType.entries.find { it.name.equals(type.trim(), true) } }.getOrNull() 
        }.toSet()

    open var searchPathPattern = resolveConfig(CONFIG_SEARCH_PATH_PATTERNS, "{baseUrl}/page/{page}/?s={query}")
    open var mainPagePathPattern = resolveConfig(CONFIG_MAIN_PAGE_PATH_PATTERNS, "{baseUrl}/{data}{page}")
    open var moviePathSegment = resolveConfig(CONFIG_MOVIE_PATH_SEGMENTS, "/movie/")
    open var tvPathSegment = resolveConfig(CONFIG_TV_PATH_SEGMENTS, "/anime/")
    open var episodeDataUrlPattern = resolveConfig(CONFIG_EPISODE_DATA_URL_PATTERNS, "{url}")
    open var searchPageLimit = resolveConfig(CONFIG_SEARCH_PAGE_LIMITS, "2").toIntOrNull() ?: 2
    open var reverseEpisodes = resolveConfig(CONFIG_REVERSE_EPISODES, "true").toBoolean()
    open var hrefCleanRegexp = resolveConfig(CONFIG_HREF_CLEAN_REGEXPS, "")
    open var hrefCleanReplace = resolveConfig(CONFIG_HREF_CLEAN_REPLACES, "")
    open var isJsonSearch = resolveConfig(CONFIG_SEARCH_IS_JSON, "false").toBoolean()
    open var searchJsonRoot = resolveConfig(CONFIG_SEARCH_JSON_ROOTS, "data")
    open var searchJsonTitle = resolveConfig(CONFIG_SEARCH_JSON_TITLES, "title")
    open var searchJsonHref = resolveConfig(CONFIG_SEARCH_JSON_HREFS, "slug")
    open var searchJsonPoster = resolveConfig(CONFIG_SEARCH_JSON_POSTERS, "poster")
    open var searchJsonPosterPrefix = resolveConfig(CONFIG_SEARCH_JSON_POSTER_PREFIXES, "")
    open var searchJsonType = resolveConfig(CONFIG_SEARCH_JSON_TYPES, "type")

    open var globalHeaders: Map<String, String> = resolveConfig(CONFIG_GLOBAL_HEADERS, "User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .split("|").associate { val parts = it.split("="); if (parts.size == 2) parts[0] to parts[1] else "" to "" }.filter { it.key.isNotBlank() }

    override val mainPage = mainPageOf(*resolveMainPageList().toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return runCatching {
            val baseUrl = if (request.name.contains(STR_SERIES, true) && seriesUrl.isNotBlank()) seriesUrl else mainUrl
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
            val isHorizontal = resolveConfig(CONFIG_HOOK_IS_HORIZONTAL, "false").toBoolean() && request.name.contains("Episode Terbaru", true)
            val home = document.selectSafeList(SEARCH_ITEMS).mapNotNull { runCatching { it.toSearchResult(url) }.getOrNull() }
            newHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
        }.getOrElse { newHomePageResponse(request.name, emptyList(), false) }
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
                addDubStatus(dubExist = this@toSearchResult.text().contains(STR_DUB, true), subExist = true, subEpisodes = eps) }
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
            }.getOrElse { emptyList() }
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
        if (depth < 2) { val follow = resolveConfigList(FOLLOW_LINK_SELECTOR)
            if (follow.isNotEmpty()) { val nextAnchor = document.selectSafe(follow); val nextHref = nextAnchor?.attr("href")
                if (!nextHref.isNullOrBlank()) { val nextUrl = fixUrlSmart(nextHref, currentUrl); if (nextUrl != currentUrl && nextUrl != url) return loadRecursive(nextUrl, depth + 1) } } }

        val rawTitle = document.selectSafe(LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX); val poster = document.selectSafe(LOAD_POSTER)?.safeExtractImage(ATTR_IMAGE) ?: ""
        val banner = document.selectSafe(LOAD_BANNER)?.safeExtractImage(ATTR_IMAGE); val description = document.selectSafe(LOAD_DESC)?.text()?.trim() ?: ""
        val infoText = document.selectSafeList(LOAD_INFO_BOX).text()
        var year = infoText.safeExtractYear() ?: run {
            val selector = resolveConfig(CONFIG_HOOK_YEAR_SELECTOR, ""); val regexStr = resolveConfig(CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) { Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull() } else null
        }
        val tags = document.selectSafeList(LOAD_TAGS).map { it.text() }; val rating = document.selectSafe(LOAD_RATING)?.text()
        val statusText = document.selectSafe(LOAD_STATUS)?.text(); val status = if (statusText?.contains(STR_ONGOING, true) == true) ShowStatus.Ongoing else ShowStatus.Completed
        val imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attrSafe(ATTR_HREF)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull()
        val tmdbId = document.selectFirst("a[href*='themoviedb.org/']")?.attrSafe(ATTR_HREF)?.split("/")?.lastOrNull()?.toIntOrNull()
        val trailer = document.selectSafe(LOAD_TRAILER)?.let { if (it.tagName() == "iframe") it.safeExtractImage(ATTR_IMAGE) else it.attrSafe(ATTR_HREF) }
        val recommendations = document.selectSafeList(LOAD_RECOMMEND).mapNotNull { it.toSearchResult(currentUrl) }
        val actors = document.selectSafeList(ACTOR_ITEMS).mapNotNull { val n = it.selectSafe(ACTOR_NAME)?.text() ?: ""; val p = it.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: ""; if (n.isNotBlank()) Actor(n, p) else null }
        val epItems = document.selectSafeList(EPISODE_ITEMS); val seasonDataScript = document.selectFirst("script#season-data")
        val isMovie = (seasonDataScript == null && document.selectFirst(".tvseason") == null) && ((moviePathSegment.isNotBlank() && currentUrl.contains(moviePathSegment)) || epItems.isEmpty())
        val type = if (isMovie) TvType.Movie else if (supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
        val tracker = runCatching { APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true) }.getOrNull()

        if (isMovie) {
            val watchUrl = fixUrlSmart(document.selectSafe(listOf(".play-button", ".watch-now", ".btn-watch"))?.attr("href"), currentUrl).ifBlank { currentUrl }
            return newMovieLoadResponse(title, url, type, episodeDataUrlPattern.replace("{url}", watchUrl)) { this.posterUrl = tracker?.image ?: poster; this.backgroundPosterUrl = tracker?.cover ?: banner
                this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = description; this.tags = tags.ifEmpty { null }; this.year = year; this.score = Score.from10(rating)
                this.recommendations = recommendations; this.comingSoon = statusText?.contains("Coming Soon", true) ?: false
                addTrailer(trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(imdbId); addTMDbId(tmdbId?.toString()) }
        } else { 
            var episodes = mutableListOf<Episode>()
            if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
                        for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${STR_EPISODE} ${ep.optInt("episode_no")}" }) } } } }
            if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectSafe(EPISODE_HREF) ?: ep.selectFirst("a") ?: return@runCatching null
                    val href = episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectSafe(EPISODE_TITLE) ?: ep.selectFirst("a")
                    val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectSafe(EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
                    val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectSafe(EPISODE_DESC)?.text()?.trim()
                        this.runTime = ep.selectSafe(EPISODE_TIME)?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster } }.getOrNull() }) }
            if (reverseEpisodes && seasonDataScript == null) episodes = episodes.reversed().toMutableList()
            return if (type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie) {
                newAnimeLoadResponse(title, url, type) { this.posterUrl = tracker?.image ?: poster; this.backgroundPosterUrl = tracker?.cover ?: banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = description; this.tags = tags.ifEmpty { null }
                    this.year = year; this.score = Score.from10(rating); this.recommendations = recommendations; this.showStatus = status; addEpisodes(DubStatus.Subbed, episodes); addTrailer(trailer); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()) }
            } else { newTvSeriesLoadResponse(title, url, type, episodes) { this.posterUrl = tracker?.image ?: poster; this.backgroundPosterUrl = tracker?.cover ?: banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }; this.plot = description; this.tags = tags.ifEmpty { null }
                    this.year = year; this.score = Score.from10(rating); this.recommendations = recommendations; this.showStatus = status; addTrailer(trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(imdbId); addTMDbId(tmdbId?.toString()) } }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val response = executeWithRetry { rateLimitDelay(); app.get(data, timeout = DEFAULT_TIMEOUT, headers = globalHeaders) }
            val document = response.documentLarge; val currentUrl = response.url
            val attrValueSelectors = resolveConfigList(ATTR_VALUE)
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()

            // --- AGGRESSIVE GATHERING (REFINED V11.3) ---
            
            // Source A: Structured Mirrors (options, li, dsb)
            resolveConfigList(LINK_OPTIONS).forEach { selector ->
                document.select(selector).forEach { container ->
                    // Iterate all anchors inside mirrors (e.g. Samehadaku multi-server per quality)
                    val anchors = container.select("a")
                    if (anchors.isNotEmpty()) {
                        anchors.forEach { a -> allPossibleLinks.add(a.attr("href") to a.text()) }
                    } else {
                        val raw = container.attrSafe(attrValueSelectors) ?: container.attr("href") ?: ""
                        if (raw.isNotBlank()) allPossibleLinks.add(raw to container.text())
                    }
                }
            }

            // Source B: Download Items (Aggressive search in download list)
            resolveConfigList(DOWNLOAD_ITEMS).forEach { selector ->
                document.select(selector).forEach { container ->
                    container.select("a").forEach { a ->
                        val href = a.attr("href")
                        if (href.isNotBlank()) allPossibleLinks.add(href to a.text())
                    }
                }
            }

            // Source C: Direct Iframes (Common for Anime/Anichin/Samehadaku embed)
            document.select("iframe").forEach { el ->
                listOf("src", "data-src", "data-link").forEach { attr ->
                    val s = el.attr(attr); if (s.isNotBlank()) allPossibleLinks.add(s to null)
                }
            }

            // --- SMART HYBRID PROCESSOR ---
            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() }.map { (raw, label) -> async { runCatching {
                    // Cerdas: Bongkar Base64 secara agresif
                    val decodedRaw = if (!raw.startsWith("http") && !raw.startsWith("//") && !raw.startsWith("/") && raw.safeIsBase64()) {
                        val dec = raw.safeDecode()
                        if (dec.contains("iframe")) Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: raw
                        else if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) dec else raw
                    } else raw

                    val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify()
                    if (fixedUrl.isBlank()) return@runCatching

                    // Tahap 1: Coba Extraksi Langsung (Prioritas untuk Anime Providers)
                    val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, callback) }.getOrDefault(false)
                    
                    // Tahap 2: Jika gagal, penelusuran rekursif (Khusus untuk pola link pemain perantara)
                    if (!okDirect) {
                        val refererMode = resolveConfig(CONFIG_HOOK_REFERER_PLAYER, "current_url")
                        val refererForPlayer = if (refererMode == "series_url") "$seriesUrl/" else currentUrl
                        
                        val playerDoc = app.get(fixedUrl, referer = refererForPlayer, headers = globalHeaders).document
                        val iframeSelectors = resolveConfigList(CONFIG_HOOK_IFRAME_SELECTORS)
                        val iframeSrc = iframeSelectors.asSequence().mapNotNull { playerDoc.selectFirst(it)?.attr("src") }.firstOrNull() ?: return@runCatching
                        
                        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
                        val refererForExtractor = getBaseUrl(fixedUrl)
                        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, callback) }.getOrDefault(false)
                        
                        if (!okRecursive && (finalIframe.contains(".mp4") || finalIframe.contains(".m3u8") || finalIframe.contains(".mkv") || finalIframe.contains(".mpd"))) {
                            MasterLinkGenerator.createSmartLink(label ?: name, finalIframe, refererForExtractor, callback = callback)
                        }
                    }
                } } }.awaitAll()
            }
            true
        }.getOrElse { false }
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
        val raw = resolveConfig(CONFIG_MAIN_PAGE_LISTS, "trending/page/|Sedang Tren")
        return raw.split(";").mapNotNull { val parts = it.split("|"); if (parts.size == 2) parts[0] to parts[1] else null }
    }

    private fun Element.selectSafe(selectors: List<String>): Element? {
        if (selectors.isEmpty()) return null
        for (s in selectors) { val sel = if (s.contains(":::")) { val owners = s.substringBefore(":::").split(","); if (owners.contains(providerId)) s.substringAfter(":::") else continue } else s
            if (sel.isBlank()) continue; runCatching { val el = this.selectFirst(sel); if (el != null) return el }
        }
        for (s in selectors) { if (s.contains(":::") && !s.startsWith("GLOBAL:::")) continue
            val sel = if (s.contains(":::")) s.substringAfter(":::") else s
            if (sel.isBlank()) continue; runCatching { val el = this.selectFirst(sel); if (el != null) return el }
        }
        return null
    }

    private fun Element.selectSafeList(selectors: List<String>): org.jsoup.select.Elements {
        if (selectors.isEmpty()) return org.jsoup.select.Elements()
        for (s in selectors) { val sel = if (s.contains(":::")) { val owners = s.substringBefore(":::").split(","); if (owners.contains(providerId)) s.substringAfter(":::") else continue } else s
            if (sel.isBlank()) continue; runCatching { val els = this.select(sel); if (els.isNotEmpty()) return els }
        }
        for (s in selectors) { if (s.contains(":::") && !s.startsWith("GLOBAL:::")) continue
            val sel = if (s.contains(":::")) s.substringAfter(":::") else s
            if (sel.isBlank()) continue; runCatching { val els = this.select(sel); if (els.isNotEmpty()) return els }
        }
        return org.jsoup.select.Elements()
    }

    private fun Element.attrSafe(attributes: List<String>): String? {
        for (a in attributes) { val attrN = if (a.contains(":::")) { val owners = a.substringBefore(":::").split(","); if (owners.contains(providerId)) a.substringAfter(":::") else continue } else a
            if (attrN.isBlank()) continue; val v = this.attr(attrN); if (v.isNotBlank()) return v
        }
        for (a in attributes) { if (a.contains(":::") && !a.startsWith("GLOBAL:::")) continue
            val attrN = if (a.contains(":::")) a.substringAfter(":::") else a
            if (attrN.isBlank()) continue; val v = this.attr(attrN); if (v.isNotBlank()) return v
        }
        return null
    }
}
