package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val ANICHIN = GLOBAL_CONFIG.copy(
    id = "Anichin",
    name = "Anichin",
    mainUrl = "https://anichin.cafe",
    supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries),

    searchPageLimit = 3,

    searchItems = "div.listupd > article",
    searchTitle = "div.bsx h2, .tt, a[title]",
    searchHref = "a",
    searchPoster = "div.bsx img, .ts-post-image, .wp-post-image",
    searchRating = ".rtng, .score, .rating",
    searchEpText = ".eps span, .epx, .bt span.epx",

    loadTitle = "h1.entry-title, h1.title, .entry-title h1",
    loadPoster = "div.thumb img, .ts-post-image, .wp-post-image",
    loadDesc = "div.description, .entry-content, .desc",
    loadInfoBox = ".spe",
    loadRecommend = ".listupd article, .listupd .bsx, .related-post article, .relat article",

    episodeItems = ".eplister li",
    episodeHref = ".eplister li > a",
    episodeTitle = ".epl-title",
    episodeNum = ".epl-num",

    linkOptions = "option[data-index], option[value]",
    moviePathSegment = "-movie-",

    mainPageLists = listOf(
        "seri/?status=&type=&order=popular&page=" to "Popular Donghua",
        "seri/?status=&type=&order=update&page=" to "Recently Updated",
        "seri/?sub=&order=latest&page=" to "Latest Added",
        "seri/?status=ongoing&type=&order=update&page=" to "Ongoing",
        "seri/?status=completed&type=&order=update&page=" to "Completed"
    ),
)
