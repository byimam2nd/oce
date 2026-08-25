package com.baseprovider.model

import org.jsoup.select.Elements

/** Alasan keputusan movie/series — untuk telemetri dan debugging. */
enum class DetectionReason {
    /** Configured selector menemukan episode yang lolos validasi kualitas. */
    CONFIGURED_EPISODES_VALIDATED,

    /** Season container (JSON/script) ditemukan → pasti series. */
    SEASON_CONTAINER,

    /** detectEpisodeLinks fallback menemukan episode dengan pola sah. */
    DETECTED_EPISODE_LINKS,

    /** URL mengandung marker TV kuat (/tv/, /seri/, dll). */
    STRONG_TV_URL,

    /** Player tab ada tapi tidak ada episode sah → film. */
    MOVIE_PLAYER_ONLY,

    /** Tidak ada episode, tidak ada player → fallback film. */
    MOVIE_NO_EPISODES
}

data class DetectionResult(
    val isMovie: Boolean,
    val effectiveEpItems: Elements,
    val reason: DetectionReason,
    /** Jumlah elemen sebelum validasi. */
    val rawEpCount: Int,
    /** Jumlah elemen setelah validasi kualitas. */
    val validEpCount: Int
)
