package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val DONGHUASTREAM = GLOBAL_CONFIG.copy(
    id = "Donghuastream",
    name = "Donghuastream",
    mainUrl = "https://donghuastream.org",
    supportedTypes = setOf(TvType.Anime),

    searchPathPattern = "{baseUrl}/pagg/{page}/?s={query}",
    searchPageLimit = 3,

    searchItems = "div.listupd > article",
    searchTitle = "div.bsx h2, .tt, a[title]",
    searchHref = "a",
    searchPoster = "div.bsx a img",

    loadTitle = "h1.entry-title, h1.title, .entry-title h1",
    loadPoster = "div.thumb > img, img.ts-post-image",
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
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=special&sub=&order=update" to "Special Anime"
    ),
)
