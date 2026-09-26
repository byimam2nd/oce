package com.baseprovider.model

import com.lagradost.cloudstream3.ShowStatus

/**
 * INTERNAL DATA CONTRACT LAYER
 */

/** Marker URL umum situs TV (path segment) yang mengindikasikan series than movie. */
val TV_LIKE_PATH_MARKERS = listOf(
    "/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/"
)

data class MetadataPackage(
    val title: String, 
    val poster: String, 
    val banner: String?, 
    val description: String,
    val year: Int?, 
    val statusText: String?, 
    val tags: List<String>, 
    val rating: String?,
    val status: ShowStatus, 
    val imdbId: String?, 
    val tmdbId: Int?, 
    val trailer: String?
)
