package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val SAMEHADAKU = GLOBAL_CONFIG.copy(
    id = "Samehadaku",
    name = "Samehadaku",
    mainUrl = "https://samehadaku.biz",
    supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),

    searchPathPattern = "{baseUrl}/?s={query}",
    isHorizontal = true,

    searchItems = "div.bsx, .listupd .bsx, div.animposx",
    searchTitle = "h2, .entry-title a, .title",
    searchHref = "a",
    searchPoster = "div.bsx img, div.animposx img, .content-thumb img",
    searchRating = ".rtng, .score, .rating",
    searchEpText = ".eps span, .epx, .bt span.epx",

    loadTitle = "h1.entry-title, h1.title, .entry-title h1",
    loadPoster = "div.thumb img, .ts-post-image",
    loadDesc = "div.entry-content p, .description p, div.description",
    loadInfoBox = "div.spe",
    loadTags = "div.genre-info a, .genres a",
    loadStatus = "div.spe span:contains(Status), .spe",
    loadTrailer = "iframe[src*=\"youtube\"], .trailer a",
    loadRecommend = "div.relat ul li, .relat article, .related-post article",

    episodeItems = "div.lstepsiode ul li, .eplister li",
    episodeHref = "a",
    episodeTitle = "a, .epl-title",
    episodeNum = ".epl-num",

    linkOptions = "option[value]",
    downloadItems = "div#downloadb li",

    mainPageLists = listOf(
        "page/" to "Latest Episodes",
        "anime/?status=&type=TV&order=update&page=" to "TV Latest",
        "anime/?status=&type=TV&order=popular&page=" to "TV Popular",
        "anime/?status=ongoing&type=TV&order=update&page=" to "TV Ongoing",
        "anime/?status=completed&type=TV&order=update&page=" to "TV Completed",
        "anime/?status=&type=&order=popular&page=" to "All Popular",
        "genres/action/page/" to "Action",
        "genres/adventure/page/" to "Adventure",
        "genres/comedy/page/" to "Comedy",
        "genres/drama/page/" to "Drama",
        "genres/fantasy/page/" to "Fantasy",
        "genres/horror/page/" to "Horror",
        "genres/isekai/page/" to "Isekai",
        "genres/romance/page/" to "Romance",
        "genres/sci-fi/page/" to "Sci-Fi",
        "genres/slice-of-life/page/" to "Slice of Life"
    ),
)
