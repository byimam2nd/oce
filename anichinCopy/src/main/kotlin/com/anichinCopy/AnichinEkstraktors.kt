package com.anichinCopy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.net.URI
import java.util.Base64

private val INFER_TYPE = ExtractorLinkType.VIDEO

// ============================================
// REGION 1: COMPILED REGEX PATTERNS
// ============================================

object CompiledRegexPatterns {
    val M3U8_STREAM_INFO = Regex("#EXT-X-STREAM-INF")
    val RUMBLE_URL_PATTERN = Regex("""\"url\":\"(.*?)\"|h\":(.*?)\}""")
    val DAILYMOTION_VIDEO_URL = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
    val DAILYMOTION_SUBTITLE = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")

    // MLG patterns
    val MLG_QUALITY_1080 = Regex("(1080|p1080|fhd|fullhd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_720 = Regex("(720|p720|hd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_480 = Regex("(480|p480|sd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_360 = Regex("(360|p360)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_240 = Regex("(240|p240|low)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_144 = Regex("(144|p144|mobile)", RegexOption.IGNORE_CASE)
    val MLG_PATH_QUALITY = Regex("/(\\d{3,4})p?/")
    val MLG_SUFFIX_QUALITY = Regex("_(\\d{3,4})")

    fun extractAllM3u8Urls(text: String, baseUrl: String? = null): Set<String> {
        val urls = mutableSetOf<String>()
        if (!text.contains("m3u8", ignoreCase = true)) return urls
        Regex("\"([^\"]*?m3u8[^\"]*?)\"").findAll(text).forEach { match ->
            val url = match.groupValues[1].trim()
            if (url.isNotEmpty()) urls.add(url)
        }
        return urls
    }
}

// ============================================
// REGION 2: EXTRACTOR HELPERS
// ============================================

object ExtractorHelpers {
    fun extractScriptFromHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("script:containsData(sources:)")?.data()
            ?: doc.selectFirst("script:containsData(file:)")?.data()
    }

    fun extractVideoFromMeta(html: String): String? {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("meta[property=og:video]")?.attr("content")
    }
}

// ============================================
// REGION 3: MASTER LINK GENERATOR
// ============================================

object MasterLinkGenerator {
    suspend fun createLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null
    ): ExtractorLink? {
        val detectedQuality = quality ?: detectQualityFromUrl(url)
        return newExtractorLink(
            source = source,
            name = source,
            url = url,
            type = INFER_TYPE
        ) {
            this.quality = detectedQuality
            if (referer != null) this.referer = referer
            this.headers = headers ?: emptyMap()
        }
    }

    private fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        if (CompiledRegexPatterns.MLG_QUALITY_1080.containsMatchIn(urlLower)) return 1080
        if (CompiledRegexPatterns.MLG_QUALITY_720.containsMatchIn(urlLower)) return 720
        if (CompiledRegexPatterns.MLG_QUALITY_480.containsMatchIn(urlLower)) return 480
        if (CompiledRegexPatterns.MLG_QUALITY_360.containsMatchIn(urlLower)) return 360
        return 480
    }
}

// ============================================
// REGION 4: LOAD EXTRACTOR WITH FALLBACK
// ============================================

suspend fun loadExtractorWithFallback(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var deliveredLinks = 0
    val trackedCallback: (ExtractorLink) -> Unit = { link ->
        deliveredLinks++
        callback(link)
    }

    // Step 1: Try library
    try {
        if (loadExtractor(url, referer, subtitleCallback, trackedCallback)) return true
    } catch (_: Exception) {
    }

    // Step 2: Try local extractors
    val urlDomain = url
        .removePrefix("http://")
        .removePrefix("https://")
        .split("/")
        .first()
        .lowercase()
    val matchingExtractors = AnichinEkstraktors.list.filter { extractor ->
        urlDomain
            .contains(
                extractor.mainUrl
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .split("/")
                    .first()
                    .lowercase()
            )
    }

    coroutineScope {
        val semaphore = Semaphore(3)
        matchingExtractors.forEach { extractor ->
            launch {
                semaphore.withPermit {
                    try {
                        extractor.getUrl(url, referer, subtitleCallback, trackedCallback)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    return deliveredLinks > 0
}

// ============================================
// REGION 5: EXTRACTOR CLASSES
// ============================================

open class Odnoklassniki : ExtractorApi() {
    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val videoReq = app
            .get(embedUrl)
            .text
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1) ?: return
        val videos = tryParseJson<List<OkRuVideo>>(videosStr) ?: return

        for (video in videos) {
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
            callback.invoke(
                newExtractorLink(this.name, this.name, videoUrl, INFER_TYPE) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                }
            )
        }
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String
    )
}

class OkRuSSL : Odnoklassniki()

class OkRuHTTP : Odnoklassniki()

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val scriptData = response.document
            .selectFirst("script:containsData(mp4)")
            ?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{") ?: return

        CompiledRegexPatterns.RUMBLE_URL_PATTERN.findAll(scriptData).forEach { match ->
            val cleanedUrl = match.groupValues[1].replace("\\/", "/")
            if (cleanedUrl.contains("rumble.com") && cleanedUrl.endsWith(".m3u8")) {
                callback.invoke(newExtractorLink(this.name, "Rumble", cleanedUrl, ExtractorLinkType.M3U8))
                return@forEach
            }
        }
    }
}

open class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: return
        val response = app
            .post("$mainUrl/dl", data = mapOf("op" to "embed", "file_code" to id, "auto" to "1"), referer = referer)
        val script = ExtractorHelpers.extractScriptFromHtml(response.text)
            ?: ExtractorHelpers.extractVideoFromMeta(response.text)?.let { "file:\"$it\"" } ?: return

        CompiledRegexPatterns.extractAllM3u8Urls(script).firstOrNull()?.let { m3u8 ->
            callback.invoke(
                newExtractorLink(this.name, this.name, m3u8, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl
                }
            )
        }
    }
}

class Svanila : StreamRuby() {
    override var name = "svanila"
    override var mainUrl = "https://streamruby.net"
}

class Svilla : StreamRuby() {
    override var name = "svilla"
    override var mainUrl = "https://streamruby.com"
}

open class Vidguardto : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(getEmbedUrl(url), referer = referer)
        val resc = res.document
            .select("script:containsData(eval)")
            .firstOrNull()
            ?.data()

        resc?.let { script ->
            try {
                val jsonStr2 = tryParseJson<SvgObject>(runJS2(script)) ?: return
                val watchlink = sigDecode(jsonStr2.stream)
                callback.invoke(newExtractorLink(this.name, name, watchlink) { this.referer = mainUrl })
            } catch (e: Exception) {
                ExtractorHelpers.extractVideoFromMeta(res.text)?.let { directM3u8 ->
                    if (directM3u8.contains(".m3u8")) {
                        callback
                            .invoke(
                                newExtractorLink("${this.name} Direct", "${this.name} Direct", directM3u8, ExtractorLinkType.M3U8) {
                                    this.referer =
                                        mainUrl
                                }
                            )
                    }
                }
            }
        }
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val t = sig
            .chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.getDecoder().decode(it + padding))
            }.dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) this[i] = this[i + 1].also { this[i + 1] = this[i] }
                }
            }.concatToString()
            .dropLast(5)
        return url.replace(sig, t)
    }

    private fun runJS2(hideMyHtmlContent: String): String {
        var result = ""
        val r = Runnable {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(scope, hideMyHtmlContent, "JavaScript", 1, null)
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, null, null).toString()
                } else {
                    Context.toString(svgObject)
                }
            } catch (_: Exception) {
            } finally {
                Context.exit()
            }
        }
        val t = Thread(ThreadGroup("A"), r, "thread_rhino", 8 * 1024 * 1024)
        t.start()
        t.join()
        t.interrupt()
        return result
    }

    private fun getEmbedUrl(url: String): String = url
        .takeIf {
            it.contains("/d/") || it.contains("/v/")
        }?.replace("/d/", "/e/")
        ?.replace("/v/", "/e/")
        ?: url

    data class SvgObject(
        val stream: String,
        val hash: String
    )
}

class Vidguardto1 : Vidguardto() {
    override val mainUrl = "https://bembed.net"
}

class Vidguardto2 : Vidguardto() {
    override val mainUrl = "https://listeamed.net"
}

class Vidguardto3 : Vidguardto() {
    override val mainUrl = "https://vgfplay.com"
}

class Dailymotion : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (url.contains("/embed/") || url.contains("/video/")) {
            url
        } else if (url.contains("geo.dailymotion.com")) {
            "$baseUrl/embed/video/${url.substringAfter("video=")}"
        } else {
            return
        }
        val id =
            URI(embedUrl).path.substringAfter("/video/").takeIf { it.matches("^[kx][a-zA-Z0-9]+$".toRegex()) } ?: return
        val response = app.get("$baseUrl/player/metadata/video/$id", referer = embedUrl).text

        CompiledRegexPatterns.DAILYMOTION_VIDEO_URL.findAll(response).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(this.name, videoUrl, embedUrl).forEach(callback)
            }
        }
    }
}

// ============================================
// REGION 6: EXTRACTORS LIST
// ============================================

object AnichinEkstraktors {
    val list = listOf(
        Dailymotion(),
        Odnoklassniki(),
        OkRuSSL(),
        OkRuHTTP(),
        Rumble(),
        StreamRuby(),
        Svanila(),
        Svilla(),
        Vidguardto(),
        Vidguardto1(),
        Vidguardto2(),
        Vidguardto3()
    )
}
