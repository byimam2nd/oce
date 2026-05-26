package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val LAYARKACA21 = GLOBAL_CONFIG.copy(
    id = "LayarKaca21",
    name = "LayarKaca",
    mainUrl = "https://tv10.lk21official.cc",
    seriesUrl = "https://tv10.lk21official.cc/nontondrama",
    searchUrl = "https://gudangvape.com",
    supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama),

    searchPathPattern = "{baseUrl}/?s={query}",
    useDocumentLarge = true,
    searchPageLimit = 1,
    reverseEpisodes = false,
    isJsonSearch = false,

    searchItems = "div.content-main article, div#gmr-main-load article, div.gallery-grid article",
    searchTitle = "h3, h2, a[title]",
    searchHref = "a",
    searchPoster = "div.poster img, img[data-src]",

    loadTitle = "div.movie-info h1, h1.entry-title",
    loadPoster = "div.movie-info > img, div#movie-poster img, div.movie-info div.poster img, div.poster img",
    loadDesc = "div.meta-info",
    loadInfoBox = "div.gmr-moviedata",
    loadTags = "div.tag-list span, .gmr-movie-on a",
    loadRating = "div.info-tag strong, .gmr-rating-item",
    loadTrailer = ".gmr-trailer-popup",
    loadRecommend = "div#gmr-related-load article, div.related-post article",

    moviePathSegment = "/",
    tvPathSegment = "/nontondrama/",
    episodeDataUrlPattern = "{url}",
    refererPlayerMode = "series_url",

    episodeItems = ".eplister li",
    episodeHref = "a",
    episodeTitle = "a, .ep-name, .epl-title",
    episodeNum = ".ep-num, .epl-num",

    linkOptions = "ul#player-list > li",
    actorItems = "div.movie-cast div.cast-item, .movie-info .cast-item, .gmr-moviedata span[itemprop=actors]",
    actorName = "span[itemprop=name], .cast-name, h3",
    followLinkSelector = "a#openNow, div.links a",

    iframeSelectors = "div.embed-container iframe, .gmr-embed-responsive iframe, iframe",
    ajaxPlayerUrl = "https://youlike.lk21.party/index.php",
    selectorJsonData = "script#watch-history-data",

    yearSelector = "div.movie-info h1",
    yearExtractorRegex = "\\\\d, (\\\\d+)",

    mainPageLists = listOf(
        "https://tv10.lk21official.cc/populer/page/" to "Film Terpopuler",
        "https://tv10.lk21official.cc/rating/page/" to "Film Berdasarkan IMDb Rating",
        "https://tv10.lk21official.cc/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "https://series.lk21.de/latest-series" to "Series Terbaru",
        "https://series.lk21.de/series/asian" to "Film Asian Terbaru",
        "https://tv10.lk21official.cc/latest/page/" to "Film Upload Terbaru"
    ),
)
