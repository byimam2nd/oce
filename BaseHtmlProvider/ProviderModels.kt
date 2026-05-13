package com.basehtmlprovider

import com.lagradost.cloudstream3.ShowStatus

/**
 * INTERNAL DATA CONTRACT LAYER
 */

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
