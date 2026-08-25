package com.baseprovider.core

import com.baseprovider.config.ProviderConfig
import com.baseprovider.model.DetectionReason
import com.baseprovider.model.DetectionResult
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

/**
 * Detector movie/series berbasis konten.
 *
 * Prioritas deteksi (dari user review):
 * 1. Configured selector menemukan episode yang lolos validasi kualitas → SERIES
 * 2. Season container → SERIES
 * 3. detectEpisodeLinks fallback dengan pola sah → SERIES
 * 4. Strong TV URL marker → SERIES
 * 5. Player tab + tanpa episode sah → MOVIE
 * 6. Fallback: tidak ada episode & tidak ada sinyal → MOVIE
 *
 * Validasi kualitas episode: jangan percaya jumlah — percaya kualitas href.
 */
object MovieSeriesDetector {

    /** Pola episode kuat — sama dengan STRONG_EPISODE_URL_REGEX di SelectorResolver. */
    private val STRONG_EP_PAT = Regex(
        """(?i)(?:/eps/|/episode/|/ep/|-episode-|/ep-)"""
    )

    fun detect(
        url: String,
        config: ProviderConfig,
        epItems: Elements,
        hasOnPagePlayer: Boolean,
        seasonDataScript: Element?,
        fallbackEpisodeLinks: Elements,
        looksLikeMovieUrlFn: (String) -> Boolean
    ): DetectionResult {
        val hasTvPath = config.tvPathSegment.isNotBlank() && url.contains(config.tvPathSegment)
        val urlLooksTv = listOf("/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/")
            .any { url.contains(it, true) }

        // ── Validasi kualitas epItems dari configured selector ──
        val (validItems, invalidCount) = validateEpisodes(epItems, url)

        // ── P1: Configured episodes yang valid → SERIES ──
        if (validItems.isNotEmpty()) {
            return DetectionResult(false, validItems,
                DetectionReason.CONFIGURED_EPISODES_VALIDATED,
                epItems.size, validItems.size)
        }

        // ── P2: Season container → SERIES ──
        if (seasonDataScript != null) {
            return DetectionResult(false, epItems,
                DetectionReason.SEASON_CONTAINER, 0, 0)
        }

        // ── P3: Fallback episode links → SERIES ──
        if (fallbackEpisodeLinks.isNotEmpty()) {
            return DetectionResult(false, fallbackEpisodeLinks,
                DetectionReason.DETECTED_EPISODE_LINKS,
                fallbackEpisodeLinks.size, fallbackEpisodeLinks.size)
        }

        // ── P4: Strong TV URL marker → SERIES ──
        if (hasTvPath || urlLooksTv) {
            return DetectionResult(false, Elements(),
                DetectionReason.STRONG_TV_URL, 0, 0)
        }

        // ── P5: Player tab + tanpa episode sah → MOVIE ──
        val providerSupportsMovies = config.supportedTypes.any {
            it == com.lagradost.cloudstream3.TvType.Movie ||
            it == com.lagradost.cloudstream3.TvType.AnimeMovie
        }
        if (hasOnPagePlayer && providerSupportsMovies) {
            return DetectionResult(true, Elements(),
                DetectionReason.MOVIE_PLAYER_ONLY, 0, 0)
        }

        // ── P6: Fallback → MOVIE ──
        return DetectionResult(true, Elements(),
            DetectionReason.MOVIE_NO_EPISODES, 0, 0)
    }

    /**
     * Validasi kualitas elemen episode:
     * - Anchor harus punya href non-blank, bukan self-reference
     * - Href harus mengandung slug series ATAU pola episode kuat
     * Return: Pair(validElements, invalidCount)
     */
    internal fun validateEpisodes(items: Elements, url: String): Pair<Elements, Int> {
        if (items.isEmpty()) return Pair(Elements(), 0)

        val slug = extractSlug(url)
        val valid = Elements()
        var invalid = 0

        for (ep in items) {
            val a = ep.selectFirst("a[href]")
                ?: ep.takeIf { it.tagName() == "a" }
                ?: run { invalid++; continue }
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") { invalid++; continue }

            val absHref = resolveHref(href, url)
            if (absHref.trimEnd('/') == url.trimEnd('/')) { invalid++; continue }

            val slugMatch = slug.isNotBlank() &&
                absHref.contains(slug, ignoreCase = true)
            val strongPattern = STRONG_EP_PAT.containsMatchIn(absHref)

            if (slugMatch || strongPattern) valid.add(ep) else invalid++
        }
        return Pair(valid, invalid)
    }

    /** Slug terakhir dari path URL (setelah slash terakhir). */
    internal fun extractSlug(url: String): String = runCatching {
        java.net.URI(url).path?.trim('/')?.substringAfterLast('/') ?: ""
    }.getOrDefault("")

    private fun resolveHref(href: String, baseUrl: String): String {
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        return try {
            val base = java.net.URI(baseUrl)
            "${base.scheme}://${base.host}${if (base.port > 0) ":${base.port}" else ""}${
                if (href.startsWith("/")) href else "/$href"}"
        } catch (_: Exception) { href }
    }
}
