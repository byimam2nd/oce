package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val DUTAMOVIE21 = GLOBAL_CONFIG.copy(
    id = "Dutamovie21",
    name = "Dutamovie21",
    mainUrl = "https://simplycufflinks.com",
    supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime),

    useDocumentLarge = true,
    reverseEpisodes = false,
    refererPlayerMode = "series_url",
    tvPathSegment = "/series/",

    searchItems = "article.item, article.item-infinite, div#gmr-main-load article",
    searchTitle = "h2.entry-title a, .entry-title a",
    searchHref = "a",
    searchPoster = "div.content-thumbnail img, img[data-src], img[src]",

    loadTitle = "h1.entry-title, div.movie-info h1",
    loadPoster = "meta[property=\"og:image\"], div.gmr-movie-data figure img, figure.pull-left img",
    loadDesc = "div.description, div.entry-content",
    loadInfoBox = "div.content-moviedata",
    loadTags = "div.gmr-moviedata a[rel=\"category tag\"], .gmr-movie-on a",
    loadRating = "div.info-tag strong, .gmr-rating-item",
    loadTrailer = ".gmr-trailer-popup",
    loadRecommend = "div.gmr-grid article, .gmr-related-title + .row article",

    episodeItems = ".eplister li",
    episodeHref = "a",
    episodeTitle = "a, .ep-name, .epl-title",
    episodeNum = ".ep-num, .epl-num",

    linkOptions = "ul.muvipro-player-tabs li a",
    actorItems = "div.movie-cast div.cast-item, .movie-info .cast-item, .gmr-moviedata span[itemprop=actors]",
    actorName = "span[itemprop=name], .cast-name, h3",
    followLinkSelector = "a#openNow, div.links a",

    iframeSelectors = "div.embed-container iframe, .gmr-embed-responsive iframe, iframe",
    yearSelector = "div.movie-info h1",
    yearExtractorRegex = "\\\\d, (\\\\d+)",

    mainPagePathPattern = "{baseUrl}/{data}/page/{page}",

    mainPageLists = listOf(
        "category/box-office" to "Box Office",
        "category/serial-tv" to "TV Series",
        "action" to "Action",
        "adventure" to "Adventure",
        "animation" to "Animation",
        "comedy" to "Comedy",
        "crime" to "Crime",
        "drama" to "Drama",
        "fantasy" to "Fantasy",
        "horror" to "Horror",
        "mystery" to "Mystery",
        "romance" to "Romance",
        "science-fiction" to "Sci-Fi",
        "thriller" to "Thriller",
        "country/china" to "China",
        "country/indonesia" to "Indonesia",
        "country/korea" to "Korea",
        "country/philippines" to "Philippines",
        "country/thailand" to "Thailand"
    ),
)
