package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val GLOBAL_CONFIG = ProviderConfig(
    id = "GLOBAL",
    name = "Base HTML Provider",
    mainUrl = "https://example.com",
    lang = "id",
    supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie, TvType.AsianDrama),

    searchPathPattern = "{baseUrl}/page/{page}/?s={query}",
    mainPagePathPattern = "{baseUrl}/{data}{page}",
    moviePathSegment = "/movie/",
    tvPathSegment = "/anime/",
    episodeDataUrlPattern = "{url}",

    searchPageLimit = 2,
    reverseEpisodes = true,
    isJsonSearch = false,
    useDocumentLarge = false,
    cacheTtlMinutes = 5L,
    isHorizontal = false,
    refererPlayerMode = "current_url",
    iframeSelectors = "iframe",

    globalHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    ),

    watchButtons = ".play-button, .watch-now, .btn-watch",
    seasonContainer = ".tvseason, #season-data",
    imdbExternal = "a[href*='imdb.com/title/']",
    tmdbExternal = "a[href*='themoviedb.org/']",
    iframeTag = "iframe",

    attrImage = listOf("data-original", "data-src", "data-lazy-src", "data-litespeed-src", "src", "content"),
    attrHref = listOf("href"),
    attrValue = listOf("value", "data-index", "data-id", "data-url", "data-link", "data-litespeed-src"),
    iframeSources = listOf("src", "data-src", "data-link", "data-litespeed-src"),

    dubKeyword = "dub",
    ongoingKeyword = "Ongoing",
    episodeKeyword = "Episode",
    seriesKeyword = "Series",
    comingSoonKeywords = "Coming Soon",

    mainPageLists = listOf(
        "trending/page/" to "Sedang Tren",
        "terbaru/page/" to "Update Terbaru"
    ),
)
