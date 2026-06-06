package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.baseprovider.ConfigRegistry
import com.baseprovider.ProviderConfig

open class ProviderCloudstream : MainAPI() {

    protected val providerId: String by lazy {
        this::class.java.simpleName.replace("Provider", "").replace(Regex("[^a-zA-Z0-9]"), "")
    }

    init {
        logDebug(providerId, "Initializing BaseProvider Engine V2.2.0")
    }

    val config: ProviderConfig by lazy { ConfigRegistry.get(providerId) }

    override var name = config.name
    override var mainUrl = config.mainUrl
    private var _seriesUrl: String? = null
    open var seriesUrl: String get() = _seriesUrl ?: config.seriesUrl ?: mainUrl; set(v) { _seriesUrl = v }
    private var _searchUrl: String? = null
    open var searchUrl: String get() = _searchUrl ?: config.searchUrl ?: mainUrl; set(v) { _searchUrl = v }

    override val hasMainPage = true
    override var lang = config.lang
    override val hasDownloadSupport = true
    override val usesWebView = true

    override val supportedTypes = config.supportedTypes

    open var searchPathPattern = config.searchPathPattern
    open var mainPagePathPattern = config.mainPagePathPattern
    open var moviePathSegment = config.moviePathSegment
    open var tvPathSegment = config.tvPathSegment
    open var episodeDataUrlPattern = config.episodeDataUrlPattern
    open var searchPageLimit = config.searchPageLimit
    open var reverseEpisodes = config.reverseEpisodes
    open var isJsonSearch = config.isJsonSearch
    open var searchJsonRoot = config.searchJsonRoot
    open var searchJsonTitle = config.searchJsonTitle
    open var searchJsonHref = config.searchJsonHref
    open var searchJsonPoster = config.searchJsonPoster
    open var searchJsonPosterPrefix = config.searchJsonPosterPrefix
    open var searchJsonType = config.searchJsonType

    open var useDocumentLarge = config.useDocumentLarge
    open var cacheTtlMinutes = config.cacheTtlMinutes

    open var globalHeaders: Map<String, String> = config.globalHeaders

    override val mainPage = mainPageOf(*config.mainPageLists.toTypedArray())

    private val mapper by lazy {
        ProviderMapper(
            api = this,
            config = config,
        )
    }

    private val scrapper by lazy {
        ProviderScrapper(
            api = this,
            config = config,
            mapper = mapper
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
}
