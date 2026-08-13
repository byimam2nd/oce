package com.baseprovider.config

import com.lagradost.api.Log
import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject

private const val PARSER_TAG = "ProviderConfigParser"

/**
 * Daftar key JSON yang dikenali parser. Di-derive dari field [ProviderConfig]
 * via reflection (constructor properties), sehingga tidak bisa desync dengan
 * definisi class. Dipakai utk mendeteksi typo / key yang tak dikenal.
 *
 * - Field `by lazy` (derived, nama backing field berakhiran `$delegate`) di-exclude
 * - Field static (mis. `Companion`) di-exclude
 */
val KNOWN_CONFIG_KEYS: Set<String> by lazy {
    ProviderConfig::class.java.declaredFields
        .asSequence()
        .filter { field ->
            !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                !field.name.endsWith("\$delegate") &&
                !field.isSynthetic
        }
        .map { it.name }
        .toSet()
}

private fun warnUnknownKeys(id: String, json: JSONObject) {
    val unknown = json.keys().asSequence()
        .filterNot { it in KNOWN_CONFIG_KEYS }
        .toList()
    if (unknown.isNotEmpty()) {
        Log.w(PARSER_TAG, "Config[$id] unknown/unused keys (mungkin typo): ${unknown.joinToString()}")
    }
}

fun fromJson(id: String, json: JSONObject): ProviderConfig {
    warnUnknownKeys(id, json)
    return ProviderConfig(
        id = id,
        name = json.optString("name", id),
        mainUrl = json.optString("mainUrl", "https://example.com"),
        seriesUrl = json.optString("seriesUrl", null)?.ifBlank { null },
        searchUrl = json.optString("searchUrl", null)?.ifBlank { null },
        lang = json.optString("lang", "id"),
        supportedTypes = parseTvTypes(json.optJSONArray("supportedTypes")),
        searchPathPattern = json.optString("searchPathPattern", "{baseUrl}/page/{page}/?s={query}"),
        mainPagePathPattern = json.optString("mainPagePathPattern", "{baseUrl}/{data}{page}"),
        moviePathSegment = json.optString("moviePathSegment", "/movie/"),
        tvPathSegment = json.optString("tvPathSegment", "/anime/"),
        episodeDataUrlPattern = json.optString("episodeDataUrlPattern", "{url}"),
        searchPageLimit = json.optInt("searchPageLimit", 2),
        reverseEpisodes = json.optBoolean("reverseEpisodes", true),
        isJsonSearch = json.optBoolean("isJsonSearch", false),
        searchJsonRoot = json.optString("searchJsonRoot", "data"),
        searchJsonTitle = json.optString("searchJsonTitle", "title"),
        searchJsonHref = json.optString("searchJsonHref", "slug"),
        searchJsonPoster = json.optString("searchJsonPoster", "poster"),
        searchJsonPosterPrefix = json.optString("searchJsonPosterPrefix", ""),
        searchJsonType = json.optString("searchJsonType", "type"),
        useDocumentLarge = json.optBoolean("useDocumentLarge", false),
        cacheTtlMinutes = json.optLong("cacheTtlMinutes", 5L),
        isHorizontal = json.optBoolean("isHorizontal", false),
        mirrorUrls = jsonArrayToList(json.optJSONArray("mirrorUrls")),
        uaPool = jsonArrayToList(json.optJSONArray("uaPool")),
        refererPlayerMode = json.optString("refererPlayerMode", "current_url"),
        iframeSelectors = json.optString("iframeSelectors", "iframe"),
        qualityStripRegex = json.optString("qualityStripRegex", """\d{3,4}p|HD|SD|FHD"""),
        globalHeaders = jsonObjectToMap(json
            .optJSONObject("globalHeaders")),
        googleReferer = json.optBoolean("googleReferer", false),
        mainPageLists = parsePairsList(json.optJSONArray("mainPageLists")),
        allowedExtractors = jsonArrayToSet(json.optJSONArray("allowedExtractors")),
        dubKeyword = json.optString("dubKeyword", "dub"),
        ongoingKeyword = json.optString("ongoingKeyword", "Ongoing"),
        episodeKeyword = json.optString("episodeKeyword", "Episode"),
        seriesKeyword = json.optString("seriesKeyword", "Series"),
        comingSoonKeywords = json.optString("comingSoonKeywords", "Coming Soon"),
        searchItems = json.optString("searchItems", ""),
        searchTitle = json.optString("searchTitle", ""),
        searchHref = json.optString("searchHref", ""),
        searchPoster = json.optString("searchPoster", ""),
        searchRating = json.optString("searchRating", ""),
        searchEpText = json.optString("searchEpText", ""),
        loadTitle = json.optString("loadTitle", ""),
        loadPoster = json.optString("loadPoster", ""),
        loadBanner = json.optString("loadBanner", ""),
        loadDesc = json.optString("loadDesc", ""),
        loadInfoBox = json.optString("loadInfoBox", ""),
        loadTags = json.optString("loadTags", ""),
        loadRating = json.optString("loadRating", ""),
        loadStatus = json.optString("loadStatus", ""),
        loadTrailer = json.optString("loadTrailer", ""),
        loadRecommend = json.optString("loadRecommend", ""),
        episodeItems = json.optString("episodeItems", ""),
        episodeHref = json.optString("episodeHref", ""),
        episodeTitle = json.optString("episodeTitle", ""),
        episodeNum = json.optString("episodeNum", ""),
        episodeDesc = json.optString("episodeDesc", ""),
        episodeTime = json.optString("episodeTime", ""),
        linkOptions = json.optString("linkOptions", ""),
        downloadItems = json.optString("downloadItems", ""),
        actorItems = json.optString("actorItems", ""),
        actorName = json.optString("actorName", ""),
        watchButtons = json.optString("watchButtons", ".play-button, .watch-now, .btn-watch"),
        seasonContainer = json.optString("seasonContainer", ".tvseason, #season-data"),
        imdbExternal = json.optString("imdbExternal", "a[href*='imdb.com/title/']"),
        tmdbExternal = json.optString("tmdbExternal", "a[href*='themoviedb.org/']"),
        iframeTag = json.optString("iframeTag", "iframe"),
        followLinkSelector = json.optString("followLinkSelector", ""),
        switchVideoSelector = json.optString("switchVideoSelector", ""),
        ajaxPlayerUrl = json.optString("ajaxPlayerUrl", ""),
        selectorJsonData = json.optString("selectorJsonData", ""),
        attrImage = jsonArrayToList(
            json.optJSONArray("attrImage"),
            listOf("data-original", "data-src", "data-lazy-src", "data-litespeed-src", "src", "content")
        ),
        attrHref = jsonArrayToList(json.optJSONArray("attrHref"), listOf("href")),
        attrValue = jsonArrayToList(
            json.optJSONArray("attrValue"),
            listOf("value", "data-index", "data-id", "data-url", "data-link", "data-litespeed-src")
        ),
        iframeSources = jsonArrayToList(json.optJSONArray("iframeSources"),
            listOf("src", "data-src", "data-link", "data-litespeed-src")),
        hrefCleanRegex = validateRegex(json.optString("hrefCleanRegex", "")),
        hrefCleanReplace = json.optString("hrefCleanReplace", ""),
        yearSelector = json.optString("yearSelector", ""),
        yearExtractorRegex = validateRegex(json.optString("yearExtractorRegex", "")),
        bloatRegex = try { Regex(json.optString("bloatRegex",
            BLOAT_REGEX_DEFAULT
                .pattern)) } catch (_: Exception) { BLOAT_REGEX_DEFAULT },
    )
}

private fun parseTvTypes(arr: JSONArray?): Set<TvType> {
    if (arr == null) return emptySet()
    return (0 until arr.length()).mapNotNull { i ->
        when (arr.optString(i, "")) {
            "Movie" -> TvType.Movie
            "TvSeries" -> TvType.TvSeries
            "Anime" -> TvType.Anime
            "AnimeMovie" -> TvType.AnimeMovie
            "AsianDrama" -> TvType.AsianDrama
            "Cartoon" -> TvType.Cartoon
            "OVA" -> TvType.OVA
            else -> null
        }
    }.toSet()
}

private fun parsePairsList(arr: JSONArray?): List<Pair<String, String>> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val pair = arr.optJSONArray(i) ?: return@mapNotNull null
        if (pair.length() < 2) return@mapNotNull null
        pair.optString(0, "") to pair.optString(1, "")
    }
}

private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val keys = obj.keys()
    val map = mutableMapOf<String, String>()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = obj.optString(key, "")
    }
    return map
}

private fun jsonArrayToList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.optString(it, "") }
}

private fun jsonArrayToList(arr: JSONArray?,
    default: List<String>): List<String> {
    if (arr == null) return default
    return (0 until arr.length()).map { arr.optString(it, "") }
}

private fun jsonArrayToSet(arr: JSONArray?): Set<String> {
    if (arr == null) return emptySet()
    return (0 until arr.length()).map { arr.optString(it, "") }.toSet()
}

private fun validateRegex(pattern: String): String {
    if (pattern.isBlank()) return ""
    return try { Regex(pattern); pattern } catch (_: Exception) { "" }
}
