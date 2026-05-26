package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val INDODRAMA21 = GLOBAL_CONFIG.copy(
    id = "IndoDrama21",
    name = "IndoDrama",
    mainUrl = "http://89.124.116.48",
    supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama),

    useDocumentLarge = true,
    reverseEpisodes = false,
    refererPlayerMode = "series_url",
    tvPathSegment = "/tv/",

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

    episodeItems = "div.gmr-listseries a[href*='/eps/']",
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
        "box-office" to "Box Office",
        "category/action" to "Action",
        "category/adventure" to "Adventure",
        "category/comedy" to "Comedy",
        "category/crime" to "Crime",
        "category/documentary" to "Documentary",
        "category/drama" to "Drama",
        "category/family" to "Family",
        "category/fantasy" to "Fantasy",
        "category/horror" to "Horror",
        "category/mystery" to "Mystery",
        "category/romance" to "Romance",
        "category/science-fiction" to "Sci-Fi",
        "category/thriller" to "Thriller",
        "country/australia" to "Australia",
        "country/canada" to "Canada",
        "country/china" to "China",
        "country/france" to "France",
        "country/germany" to "Germany",
        "country/india" to "India",
        "country/indonesia" to "Indonesia",
        "country/japan" to "Japan",
        "country/korea" to "Korea",
        "country/thailand" to "Thailand",
        "country/usa" to "USA",
        "country/united-kingdom" to "United Kingdom"
    ),
)
