package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

object CompiledRegexPatterns {
    val M3U8_STREAM_INFO = Regex("#EXT-X-STREAM-INF")
    val RUMBLE_URL_PATTERN = Regex("""\"url\":\"(.*?)\"|h\":(.*?)\}""")
    val DAILYMOTION_VIDEO_URL = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
    val DAILYMOTION_SUBTITLE = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")
    val ARCHIVE_ORG_URL = Regex("""\"url\":\"(.*?)\"""")
    val UNIVERSAL_VIDEO_URL = Regex("""\"([^\"]*?\.(?:mp4|m3u8|mkv|mpd|webm|ts|mov)(?:\?[^\"]*?)?)\"""")

    val MLG_QUALITY_1080 = Regex("(1080|p1080|fhd|fullhd)", RegexOption
        .IGNORE_CASE)
    val MLG_QUALITY_720 = Regex("(720|p720|hd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_480 = Regex("(480|p480|sd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_360 = Regex("(360|p360)", RegexOption.IGNORE_CASE)

    fun extractAllVideoUrls(text: String): Set<String> {
        val urls = mutableSetOf<String>()
        UNIVERSAL_VIDEO_URL.findAll(text).forEach { match ->
            val url = match.groupValues[1].replace("\\/", "/").trim()
            if (url.startsWith("http") || url.startsWith("//")) {
                urls.add(if (url.startsWith("//")) "https:$url" else url)
            }
        }
        return urls
    }

    fun filterMasterM3u8(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val m3u8s = urls.filter { it.contains(".m3u8") || it.contains(".mpd") }
        if (m3u8s.isEmpty()) return urls.toList()
        val masters = m3u8s.filter { it.contains("master", true) || it
            .contains("manifest", true) || it.contains("playlist", true) }
        return if (masters.isNotEmpty()) masters
            .distinct() else listOf(m3u8s.first())
    }

    /** Prioritaskan HLS (m3u8), lalu DASH (mpd), baru fallback lainnya. */
    fun prioritizeAdaptiveUrls(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val m3u8s = urls.filter { it.contains(".m3u8", ignoreCase = true) }
        if (m3u8s.isNotEmpty()) {
            val masters = m3u8s.filter { it.contains("master", true) || it
                .contains("manifest", true) || it.contains("playlist", true) }
            return if (masters.isNotEmpty()) masters
                .distinct() else listOf(m3u8s.first())
        }
        val mpds = urls.filter { it.contains(".mpd", ignoreCase = true) }
        if (mpds.isNotEmpty()) return mpds.distinct()
        return urls.distinct()
    }
}
