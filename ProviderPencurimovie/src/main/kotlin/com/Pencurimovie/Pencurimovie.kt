package com.Pencurimovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
import com.Pencurimovie.PencurimovieConstants.CONFIG_USE_DOCUMENT_LARGE
import com.Pencurimovie.PencurimovieConstants.CONFIG_CACHE_TTL_MINUTES
import com.Pencurimovie.PencurimovieConstants.CONFIG_MAIN_PAGE_LISTS
import com.Pencurimovie.PencurimovieConstants.STR_DUB
import com.Pencurimovie.PencurimovieConstants.STR_ONGOING
import com.Pencurimovie.PencurimovieConstants.STR_EPISODE
import com.Pencurimovie.PencurimovieConstants.STR_SERIES

/**
 * 🚀 ULTIMATE HTML SCRAPING ENGINE - VERSION 2.2.0 (MODULAR EDITION)
 * 
 * Provider.kt sekarang bertindak sebagai adapter murni.
 * Seluruh logika scraping berada di PencurimovieScrapper.
 * Seluruh logika pemetaan berada di PencurimovieMapper.
 */

open class Pencurimovie : MainAPI() {
    
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
    open var isJsonSearch = getCached(CONFIG_SEARCH_IS_JSON, "false").toBoolean()
    open var searchJsonRoot = getCached(CONFIG_SEARCH_JSON_ROOTS, "data")
    open var searchJsonTitle = getCached(CONFIG_SEARCH_JSON_TITLES, "title")
    open var searchJsonHref = getCached(CONFIG_SEARCH_JSON_HREFS, "slug")
    open var searchJsonPoster = getCached(CONFIG_SEARCH_JSON_POSTERS, "poster")
    open var searchJsonPosterPrefix = getCached(CONFIG_SEARCH_JSON_POSTER_PREFIXES, "")
    open var searchJsonType = getCached(CONFIG_SEARCH_JSON_TYPES, "type")
    
    open var useDocumentLarge = getCached(CONFIG_USE_DOCUMENT_LARGE, "false").toBoolean()
    open var cacheTtlMinutes = getCached(CONFIG_CACHE_TTL_MINUTES, "5").toLongOrNull() ?: 5L

    open var globalHeaders: Map<String, String> = getCached(CONFIG_GLOBAL_HEADERS, "User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .split("|").associate { val parts = it.split("="); if (parts.size == 2) parts[0] to parts[1] else "" to "" }.filter { it.key.isNotBlank() }

    override val mainPage = mainPageOf(*resolveMainPageList().toTypedArray())

    // UI Keywords Cached
    private val ongoingKeyword by lazy { getCached(STR_ONGOING, "Ongoing") }
    private val dubKeyword by lazy { getCached(STR_DUB, "dub") }
    private val seriesKeyword by lazy { getCached(STR_SERIES, "Series") }
    private val episodeKeyword by lazy { getCached(STR_EPISODE, "Episode") }

    // Modular Components
    private val mapper by lazy {
        PencurimovieMapper(
            providerId = providerId,
            mainUrl = mainUrl,
            moviePathSegment = moviePathSegment,
            supportedTypes = supportedTypes,
            dubKeyword = dubKeyword,
            globalHeaders = globalHeaders,
            ongoingKeyword = ongoingKeyword,
            episodeKeyword = episodeKeyword,
            reverseEpisodes = reverseEpisodes,
            episodeDataUrlPattern = episodeDataUrlPattern,
            configCache = configCache
        )
    }

    private val scrapper by lazy {
        PencurimovieScrapper(
            providerId = providerId,
            mainUrl = mainUrl,
            seriesUrl = seriesUrl,
            searchUrl = searchUrl,
            searchPathPattern = searchPathPattern,
            mainPagePathPattern = mainPagePathPattern,
            useDocumentLarge = useDocumentLarge,
            globalHeaders = globalHeaders,
            isJsonSearch = isJsonSearch,
            searchJsonRoot = searchJsonRoot,
            searchJsonTitle = searchJsonTitle,
            searchJsonHref = searchJsonHref,
            searchJsonPoster = searchJsonPoster,
            searchJsonPosterPrefix = searchJsonPosterPrefix,
            searchJsonType = searchJsonType,
            searchPageLimit = searchPageLimit,
            seriesKeyword = seriesKeyword,
            moviePathSegment = moviePathSegment,
            supportedTypes = supportedTypes,
            episodeDataUrlPattern = episodeDataUrlPattern,
            mapper = mapper,
            name = name
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        scrapper.getMainPage(page, request)

    override suspend fun search(query: String): List<SearchResponse> =
        scrapper.search(query)

    override suspend fun quickSearch(query: String): List<SearchResponse>? = 
        scrapper.search(query)

    override suspend fun load(url: String): LoadResponse =
        scrapper.load(url)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        scrapper.loadLinks(data, isCasting, subtitleCallback, callback)

    // --- CONFIG BRIDGE ---

    private fun getCached(list: List<String>, default: String): String {
        return configCache.getOrPut(list.hashCode()) { resolveConfig(providerId, list, default) }
    }

    private fun getCachedList(list: List<String>): List<String> {
        return configListCache.getOrPut(list.hashCode()) { resolveConfigList(providerId, list) }
    }

    private fun resolveMainPageList(): List<Pair<String, String>> {
        val raw = getCached(CONFIG_MAIN_PAGE_LISTS, "trending/page/|Sedang Tren")
        return raw.split(";").mapNotNull { val parts = it.split("|"); if (parts.size == 2) parts[0] to parts[1] else null }
    }
}
