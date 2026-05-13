package com.Donghuastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.nio.charset.StandardCharsets

/**
 * 🛠️ PROVIDER EXTRACTORS LAYER - V10.0 (STABLE)
 * 
 * Lapisan ini bertanggung jawab untuk mengekstrak link video langsung dari berbagai
 * host (misal: OkRu, Dailymotion, Byse) menggunakan sistem Deep Scanning.
 */

private val INFER_TYPE = ExtractorLinkType.VIDEO

// ============================================
// REGION 1: REGEX PATTERNS (DATA EXTRACTION)
// ============================================

object CompiledRegexPatterns {
    val M3U8_STREAM_INFO = Regex("#EXT-X-STREAM-INF")
    val RUMBLE_URL_PATTERN = Regex("""\"url\":\"(.*?)\"|h\":(.*?)\}""")
    val DAILYMOTION_VIDEO_URL = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
    val DAILYMOTION_SUBTITLE = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")
    val ARCHIVE_ORG_URL = Regex("""\"url\":\"(.*?)\"""")
    val UNIVERSAL_VIDEO_URL = Regex("""\"([^\"]*?\.(?:mp4|m3u8|mkv|mpd|webm|ts|mov)(?:\?[^\"]*?)?)\"""")

    val MLG_QUALITY_1080 = Regex("(1080|p1080|fhd|fullhd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_720 = Regex("(720|p720|hd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_480 = Regex("(480|p480|sd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_360 = Regex("(360|p360)", RegexOption.IGNORE_CASE)

    /**
     * Mengekstrak seluruh URL video yang tersembunyi di dalam teks/kode sumber.
     */
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

    /**
     * Memfilter master playlist dari kumpulan URL m3u8 untuk efisiensi player.
     */
    fun filterMasterM3u8(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val m3u8s = urls.filter { it.contains(".m3u8") || it.contains(".mpd") }
        if (m3u8s.isEmpty()) return urls.toList()
        val masters = m3u8s.filter { it.contains("master", true) || it.contains("manifest", true) || it.contains("playlist", true) }
        return if (masters.isNotEmpty()) masters.distinct() else listOf(m3u8s.first())
    }
}

// ============================================
// REGION 2: MASTER LINK GENERATOR & REFINER
// ============================================

object MasterLinkGenerator {
    /**
     * Membuat objek ExtractorLink standar dengan deteksi kualitas otomatis.
     */
    suspend fun createSmartLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val isAdaptive = url.contains(".m3u8") || url.contains(".mpd")
        val safeHeaders = headers ?: emptyMap()

        callback(newExtractorLink(
            source = source,
            name = source, 
            url = url,
            type = if (url.contains(".mpd")) ExtractorLinkType.DASH else if (isAdaptive) ExtractorLinkType.M3U8 else INFER_TYPE
        ) {
            this.quality = quality ?: detectQualityFromUrl(url)
            this.referer = referer ?: ""
            this.headers = safeHeaders
        })
    }

    /**
     * Membersihkan nama server dan mengelompokkan link m3u8.
     */
    fun refineAndDeliver(links: List<ExtractorLink>, finalCallback: (ExtractorLink) -> Unit) {
        val seenM3u8Sources = mutableSetOf<String>()
        links.forEach { link ->
            val isM3u8 = link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.DASH
            if (isM3u8) {
                if (seenM3u8Sources.add(link.source)) {
                    val refinedName = link.source.replace(Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE), "").trim()
                    finalCallback(ExtractorLink(source = link.source, name = refinedName, url = link.url, referer = link.referer, quality = Qualities.Unknown.value, type = link.type, headers = link.headers, extractorData = null))
                }
            } else {
                val qualityLabel = if (link.quality > 0) "${link.quality}p" else ""
                val cleanSource = link.source.replace(Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE), "").trim()
                finalCallback(ExtractorLink(source = link.source, name = "$cleanSource $qualityLabel".trim(), url = link.url, referer = link.referer, quality = link.quality, type = link.type, headers = link.headers, extractorData = null))
            }
        }
    }

    private fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            CompiledRegexPatterns.MLG_QUALITY_1080.containsMatchIn(urlLower) -> 1080
            CompiledRegexPatterns.MLG_QUALITY_720.containsMatchIn(urlLower) -> 720
            CompiledRegexPatterns.MLG_QUALITY_480.containsMatchIn(urlLower) -> 480
            CompiledRegexPatterns.MLG_QUALITY_360.containsMatchIn(urlLower) -> 360
            else -> 480
        }
    }

    fun getQualityFromName(name: String?): Int {
        if (name == null) return 480
        val n = name.lowercase()
        return when {
            n.contains("1080") || n.contains("fhd") -> 1080
            n.contains("720") || n.contains("hd") -> 720
            n.contains("480") || n.contains("sd") -> 480
            else -> 360
        }
    }
}

// ============================================
// REGION 3: LOAD EXTRACTOR WITH FALLBACK
// ============================================

/**
 * Inti dari sistem pemrosesan link.
 * 1. Mencoba extractor yang terdaftar di proyek.
 * 2. Mencoba sistem extractor global CloudStream.
 * 3. Melakukan Deep Scan jika tidak ditemukan link yang valid.
 */
suspend fun loadExtractorWithFallbackCustom(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    headers: Map<String, String>? = null,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val collectedLinks = mutableListOf<ExtractorLink>()
    val seenUrls = mutableSetOf<String>()
    val providerId = "ExtractorEngine" // Tag internal untuk log
    
    val internalCallback: (ExtractorLink) -> Unit = { link ->
        if (seenUrls.add(link.url)) { collectedLinks.add(link) }
    }

    val urlDomain = url.removePrefix("http://").removePrefix("https://").split("/").first().lowercase()
    val matchingExtractors = DonghuastreamEkstraktors.list.filter { extractor ->
        val extractorDomain = extractor.mainUrl.removePrefix("http://").removePrefix("https://").replace("www.", "").lowercase()
        urlDomain.contains(extractorDomain)
    }

    // 1. Jalankan extractor lokal yang cocok secara paralel (max 3 thread)
    if (matchingExtractors.isNotEmpty()) {
        coroutineScope {
            val semaphore = Semaphore(3)
            matchingExtractors.forEach { extractor ->
                launch { semaphore.withPermit { 
                    runCatching { 
                        extractor.getUrl(url, referer, subtitleCallback, internalCallback) 
                    }.onFailure { e -> 
                        logDebug(providerId, "Local Extractor (${extractor.name}) failed for $url: ${e.message}")
                    }
                } }
            }
        }
    }

    // 2. Gunakan sistem extractor inti CloudStream jika lokal gagal
    if (collectedLinks.isEmpty()) { 
        runCatching { 
            loadExtractor(url, referer, subtitleCallback, internalCallback) 
        }.onFailure { e -> 
            logDebug(providerId, "Global Extractor failed for $url: ${e.message}")
        }
    }
    
    // 3. Direct Link Generation
    if (collectedLinks.isEmpty() && (url.contains(".mp4") || url.contains(".m3u8") || url.contains(".mkv") || url.contains(".mpd"))) {
        MasterLinkGenerator.createSmartLink("Direct", url, referer, headers = headers, callback = internalCallback)
    }

    // 4. Deep Scanning: Mencari link video di dalam kode HTML host
    if (collectedLinks.isEmpty()) {
        runCatching {
            val response = app.get(url, referer = referer, headers = headers ?: emptyMap()).text
            val urls = CompiledRegexPatterns.extractAllVideoUrls(response)
            val filtered = CompiledRegexPatterns.filterMasterM3u8(urls)
            if (filtered.isNotEmpty()) {
                filtered.forEach { videoUrl ->
                    MasterLinkGenerator.createSmartLink("DeepScan", videoUrl, url, headers = headers, callback = internalCallback)
                }
            } else {
                logDebug(providerId, "DeepScan found no video URLs in HTML source of $url")
            }
        }.onFailure { e ->
            logDebug(providerId, "DeepScan network failure for $url: ${e.message}")
        }
    }

    // 5. Final Report if still empty
    if (collectedLinks.isEmpty() && urlDomain.isNotBlank() && url.startsWith("http")) {
        logFail(providerId, "All extraction methods failed to find playable links for host: $urlDomain", url = url)
    }

    MasterLinkGenerator.refineAndDeliver(collectedLinks, callback)
    return collectedLinks.isNotEmpty()
}

// ============================================
// REGION 4: EXTRACTOR CLASSES (LOCAL HOSTS)
// ============================================

class Dailymotion : ExtractorApi() {
    override var name = "Dailymotion"; override var mainUrl = "https://dailymotion.com"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url).text
        val urls = CompiledRegexPatterns.extractAllVideoUrls(res)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, null, callback = callback) }
        CompiledRegexPatterns.DAILYMOTION_SUBTITLE.findAll(res).forEach { subtitleCallback.invoke(SubtitleFile(it.groupValues[1], it.groupValues[2].replace("\\/", "/"))) }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"; override var mainUrl = "https://rumble.com"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()?.substringAfter("{\"mp4")?.substringBefore("\"evt\":{") ?: return
        CompiledRegexPatterns.RUMBLE_URL_PATTERN.findAll(scriptData).forEach { match ->
            val cleanedUrl = match.groupValues[1].replace("\\/", "/")
            if (cleanedUrl.contains("rumble.com") && cleanedUrl.endsWith(".m3u8")) {
                MasterLinkGenerator.createSmartLink(this.name, cleanedUrl, referer, callback = callback)
            }
        }
    }
}

open class Odnoklassniki : ExtractorApi() {
    override var name = "OkRu"; override var mainUrl = "https://odnoklassniki.ru"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val videoReq = app.get(embedUrl).text.replace("\\&quot;", "\"").replace("\\\\", "\\")
        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1) ?: return
        tryParseJson<List<OkRuVideo>>(videosStr)?.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            MasterLinkGenerator.createSmartLink(this.name, videoUrl, "$mainUrl/", MasterLinkGenerator.getQualityFromName(video.name), callback = callback)
        }
    }
    data class OkRuVideo(@JsonProperty("name") val name: String, @JsonProperty("url") val url: String)
}

open class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"; override var mainUrl = "https://rubyvidhub.com"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: return
        val response = app.post("$mainUrl/dl", data = mapOf("op" to "embed", "file_code" to id, "auto" to "1"), referer = referer)
        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, mainUrl, callback = callback) }
    }
}

open class ByseSX : ExtractorApi() {
    override var name = "Byse"; override var mainUrl = "https://byse.sx"; override val requiresReferer = true
    private fun b64UrlDecode(s: String): ByteArray { val fixed = s.replace('-', '+').replace('_', '/'); return Base64.getDecoder().decode(fixed + "=".repeat((4 - fixed.length % 4) % 4)) }
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try { val code = URI(url).path.trimEnd('/').substringAfterLast('/'); val base = URI(url).let { "${it.scheme}://${it.host}" }; val details = app.get("$base/api/videos/$code/embed/details").parsedSafe<ByseDetailsRoot>() ?: return
            val embedFrameUrl = details.embedFrameUrl; val embedBase = URI(embedFrameUrl).let { "${it.scheme}://${it.host}" }; val embedCode = URI(embedFrameUrl).path.trimEnd('/').substringAfterLast('/'); val headers = mapOf("referer" to embedFrameUrl, "x-embed-parent" to url)
            val playback = app.get("$embedBase/api/videos/$embedCode/embed/playback", headers = headers).parsedSafe<BysePlaybackRoot>()?.playback ?: return
            val key = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1]); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64UrlDecode(playback.iv)))
            val jsonStr = String(cipher.doFinal(b64UrlDecode(playback.payload)), StandardCharsets.UTF_8).let { if (it.startsWith("\uFEFF")) it.substring(1) else it }; tryParseJson<BysePlaybackDecrypt>(jsonStr)?.sources?.forEach { MasterLinkGenerator.createSmartLink(name, it.url, mainUrl, headers = mapOf("Referer" to base), callback = callback) } } catch (_: Exception) {}
    }
    data class ByseDetailsRoot(val id: Long, val code: String, val title: String, @JsonProperty("poster_url") val posterUrl: String, val description: String, @JsonProperty("embed_frame_url") val embedFrameUrl: String)
    data class BysePlaybackRoot(val playback: BysePlayback); data class BysePlayback(val algorithm: String, val iv: String, val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
    data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>); data class BysePlaybackSource(val quality: String, val label: String, val url: String)
}

open class Hownetwork : ExtractorApi() {
    override var name = "Hownetwork"; override var mainUrl = "https://stream.hownetwork.xyz"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try { val id = url.substringAfter("id="); val response = app.post("$mainUrl/api2.php?id=$id", data = mapOf("r" to "", "d" to mainUrl), referer = url, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
            JSONObject(response).optString("file").let { MasterLinkGenerator.createSmartLink(this.name, it, it, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"), callback = callback) } } catch (_: Exception) {}
    }
}

// --- Library Extensions & Aliases ---
class Svanila : StreamRuby() { override var name = "svanila"; override var mainUrl = "https://streamruby.net" }
class Svilla : StreamRuby() { override var name = "svilla"; override var mainUrl = "https://streamruby.com" }
class Cloudhownetwork : Hownetwork() { override var mainUrl = "https://cloud.hownetwork.xyz" }
class PlayStreamplay : ExtractorApi() { override var name = "PlayStreamplay"; override var mainUrl = "https://playstreamplay.com"; override val requiresReferer = true }
class Ultrahd : ExtractorApi() { override var name = "Ultrahd"; override var mainUrl = "https://ultrahd.to"; override val requiresReferer = true }
class Vtbe : ExtractorApi() { override var name = "Vtbe"; override var mainUrl = "https://vtbe.com"; override val requiresReferer = true }
class wishfast : ExtractorApi() { override var name = "wishfast"; override var mainUrl = "https://wishfast.to"; override val requiresReferer = true }

class Minochinos : ExtractorApi() { override var name = "Minochinos"; override var mainUrl = "https://minochinos.com"; override val requiresReferer = true }
class Vidhide : ExtractorApi() { override var name = "Vidhide"; override var mainUrl = "https://vidhide.com"; override val requiresReferer = true }
class ShortIcu : ExtractorApi() { 
    override var name = "ShortIcu"
    override var mainUrl = "https://short.icu"
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val finalUrl = response.url
        if (finalUrl != url) {
            // If redirected, try loading extractor for the final URL
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }
        
        // Deep Scan the response text just in case
        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { videoUrl ->
            MasterLinkGenerator.createSmartLink(this.name, videoUrl, finalUrl, callback = callback)
        }
    }
}

// ============================================
// REGION 5: EXTRACTORS REGISTRY
// ============================================

object DonghuastreamEkstraktors {
    val list = listOf(
        Dailymotion(), Odnoklassniki(), Rumble(), StreamRuby(), Svanila(), Svilla(), 
        ByseSX(), Hownetwork(), Cloudhownetwork(),
        PlayStreamplay(), Ultrahd(), Vtbe(), wishfast(),
        Minochinos(), Vidhide(), ShortIcu()
    )
}
