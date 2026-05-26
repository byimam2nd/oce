package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val PENCURIMOVIE = GLOBAL_CONFIG.copy(
    id = "Pencurimovie",
    name = "Pencurimovie",
    mainUrl = "https://ww73.pencurimovie.bond",
    supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon),

    searchPathPattern = "{baseUrl}/?s={query}",
    reverseEpisodes = false,
    tvPathSegment = "/series/",
    moviePathSegment = "/movies/",

    searchItems = "div.ml-item",
    searchTitle = "a[oldtitle], a[title]",
    searchHref = "a",
    searchPoster = "a img[data-original], a img[data-src]",
    searchEpText = ".mli-eps",

    loadTitle = "div.mvic-desc h3",
    loadPoster = "div.mvic-thumb img",
    loadDesc = "div.desc p.f-desc",
    loadInfoBox = "div.mvic-info",
    loadTags = "div.mvic-info p:contains(Genre) a",
    loadRecommend = ".mlw-related .ml-item, #related-items .ml-item",

    episodeItems = "div.tvseason div.les-content a",
    episodeHref = "a",
    episodeTitle = "a",
    episodeNum = ".ep-num, .epl-num",

    linkOptions = "div.player_nav a, ul.list-server li",
    downloadItems = "",

    mainPagePathPattern = "{baseUrl}/{data}/page/{page}",

    mainPageLists = listOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "top-imdb" to "Top IMDB Movies"
    ),
)
