package com.baseprovider.config

import com.lagradost.cloudstream3.TvType

val ANIMASU = GLOBAL_CONFIG.copy(
    id = "Animasu",
    name = "Animasu",
    mainUrl = "https://v1.animasu.top",
    supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),

    searchPathPattern = "{baseUrl}/?s={query}",
    searchPageLimit = 1,

    searchItems = "div.listupd div.bs",
    searchTitle = "div.tt",
    searchHref = "a",
    searchPoster = "div.limit img, img[data-src], .thumb img",
    searchEpText = "span.epx",

    loadTitle = "h1[itemprop=headline], div.infox h1",
    loadPoster = "div.thumb img, .ts-post-image, div.bigcontent img",
    loadDesc = "div.sinopsis, .desc",
    loadInfoBox = "div.infox div.spe",
    loadTags = "span:contains(Genre:) a",
    loadStatus = "span:contains(Status:) font",
    loadRecommend = ".listupd article, .listupd .bsx, .related-post article, .relat article",

    episodeItems = "ul#daftarepisode > li",
    episodeHref = "a",
    episodeTitle = "a",
    episodeNum = ".ep-num, .epl-num",

    linkOptions = ".mobius > .mirror > option",

    hrefCleanRegex = "^https?://[^/]+/(?:nonton-anime-|anime-|)([a-zA-Z0-9-]+)(?:-episode-.*|-movie.*|)/?$",
    hrefCleanReplace = "https://v1.animasu.top/anime/$1",

    mainPagePathPattern = "{baseUrl}/pencarian/?{data}&halaman={page}",

    mainPageLists = listOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler"
    ),
)
