package com.baseprovider.network

fun String.normalizeDomain(stripWww: Boolean = false): String {
    val base = removePrefix("http://").removePrefix("https://").split("/")
        .first().lowercase()
    if (base.isBlank()) return this
    return if (stripWww) base.removePrefix("www.") else base
}

fun String.normalizeExtractorDomain(): String = normalizeDomain(stripWww =
    true)

private val DIRECT_MEDIA_EXTENSIONS = listOf(".mp4", ".m3u8", ".mkv", ".mpd")

fun String.isDirectMediaUrl(): Boolean =
    DIRECT_MEDIA_EXTENSIONS.any { contains(it, ignoreCase = true) }
