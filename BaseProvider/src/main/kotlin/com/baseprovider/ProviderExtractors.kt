package com.baseprovider

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
import org.mozilla.javascript.ScriptableObject
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    callback: (ExtractorLink) -> Unit,
    providerTag: String = "ExtractorEngine",
    callChain: String = "-"
): Boolean {
    val collectedLinks = mutableListOf<ExtractorLink>()
    val seenUrls = mutableSetOf<String>()
    val providerId = providerTag
    
    val internalCallback: (ExtractorLink) -> Unit = { link ->
        if (seenUrls.add(link.url)) { collectedLinks.add(link) }
    }

    val urlDomain = url.removePrefix("http://").removePrefix("https://").split("/").first().lowercase()
    val matchingExtractors = ProviderExtractors.list.filter { extractor ->
        val extractorDomain = extractor.mainUrl.removePrefix("http://").removePrefix("https://").replace("www.", "").lowercase()
        urlDomain.contains(extractorDomain)
    }

    if (matchingExtractors.isEmpty()) {
        logDebug(providerId, "No matching extractor for host: $urlDomain")
    } else {
        logDebug(providerId, "Matching extractors for $urlDomain: ${matchingExtractors.joinToString(", ") { it.name }}")
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
                        logFail(providerId, "Local Extractor (${extractor.name}) failed for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = extractor.name)
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
            logFail(providerId, "Global Extractor failed for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = callChain)
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
                logFail(providerId, "DeepScan found no video URLs in HTML source of $url", url = url, method = "extractLinks", type = FailureType.EMPTY_RESPONSE, selectors = callChain)
            }
        }.onFailure { e ->
            logFail(providerId, "DeepScan network failure for $url: ${e.message}", url = url, method = "extractLinks", type = FailureType.NETWORK_FAILURE, selectors = callChain)
        }
    }

    // 5. Final Report
        val extractorNames = matchingExtractors.joinToString(", ") { it.name }.ifBlank { "none" }
        val chainInfo = if (callChain == "-") extractorNames else "$callChain → $extractorNames"
        if (collectedLinks.isEmpty() && urlDomain.isNotBlank() && url.startsWith("http")) {
            val ft = if (urlDomain.contains("short.") || urlDomain.contains("shorte")) FailureType.SHORTLINK_FAILURE
                else FailureType.EXTRACTOR_FAILURE
            logFail(providerId, "All extraction methods failed to find playable links for host: $urlDomain", url = url, method = "extractLinks", type = ft, selectors = chainInfo)
        } else if (collectedLinks.isNotEmpty()) {
            logSuccess(providerId, "${collectedLinks.size} links", url = url, method = "extractLinks", selectors = chainInfo)
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
class Ultrahd : ExtractorApi() { override var name = "Ultrahd"; override var mainUrl = "https://ultrahd.to"; override val requiresReferer = true }
class Vtbe : ExtractorApi() { override var name = "Vtbe"; override var mainUrl = "https://vtbe.com"; override val requiresReferer = true }
class wishfast : ExtractorApi() { 
    override var name = "wishfast"; 
    override var mainUrl = "https://wishfast.to"; 
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val doc = response.document

        val script: String? = findPackedJsInPage(text)?.let {
            decodePackedJs(it.first, it.second, it.third)
        } ?: doc.selectFirst("script:containsData(sources:)")?.data()

        if (script != null) {
            val fileUrl = Regex("""file:\s*"(.*?m3u8.*?)"""").find(script)?.groupValues?.getOrNull(1)
            if (fileUrl != null) {
                MasterLinkGenerator.createSmartLink(this.name, fileUrl, url, callback = callback)
                return
            }
        }

        val urls = CompiledRegexPatterns.extractAllVideoUrls(text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
    }
}

class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second, packed.third)
            CompiledRegexPatterns.extractAllVideoUrls(unpacked).let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
            }
        }
    }
}

open class MegaPlay : ExtractorApi() {
    override var name = "MegaPlay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url).document
        val id = doc.selectFirst("#megaplay-player")?.attr("data-id") ?: return
        val apiUrl = "$mainUrl/stream/getSources?id=$id"
        val json = JSONObject(app.get(apiUrl).text)
        val m3u8 = json.optJSONObject("sources")?.optString("file") ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, mainUrl, callback = callback)
    }
}

open class AWSStream : ExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val hash = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
        val response = app.post(apiUrl, headers = mapOf("x-requested-with" to "XMLHttpRequest"), data = mapOf("hash" to hash, "r" to mainUrl)).text
        val json = JSONObject(response)
        val m3u8 = json.optString("videoSource")
        if (m3u8.isNotBlank()) MasterLinkGenerator.createSmartLink(this.name, m3u8, "", callback = callback)
    }
}

open class LuluStream : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val filecode = url.substringAfterLast("/")
        val doc = app.post("$mainUrl/dl", data = mapOf("op" to "embed", "file_code" to filecode, "auto" to "1", "referer" to (referer ?: ""))).document
        val script = doc.selectFirst("script:containsData(vplayer)")?.data() ?: return
        val m3u8 = Regex("""file:"(.*)"""").find(script)?.groupValues?.getOrNull(1) ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, mainUrl, callback = callback)
    }
}

class Dhcplay : VidHidePro() { override var mainUrl = "https://dhcplay.com" }
class Voe : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val script = doc.select("script").joinToString("\n") { it.data() }
        val m3u8 = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(script)?.value ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, url, callback = callback)
    }
}
class Xtwap : ExtractorApi() {
    override var name = "Xtwap"
    override var mainUrl = "https://xtwap.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val filePath = Regex(""""file":"([^"]+)"""").find(scripts)?.groupValues?.getOrNull(1) ?: return
        val m3u8 = fixUrlSmart(filePath, url)
        MasterLinkGenerator.createSmartLink(this.name, m3u8, url, callback = callback)
    }
}

open class Gdplayer : ExtractorApi() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        val script = doc.selectFirst("script:containsData(player = \"\")")?.data() ?: return
        val kaken = script.substringAfter("kaken = \"").substringBefore("\"")
        val json = JSONObject(app.get("$mainUrl/api/?${kaken}=&_=${System.currentTimeMillis()}", headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text)
        val sources = json.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val file = sources.getJSONObject(i).optString("file")
            if (file.isNotBlank()) MasterLinkGenerator.createSmartLink(this.name, file, mainUrl, callback = callback)
        }
    }
}

class Vidguardto2 : Vidguardto() { override var mainUrl = "https://listeamed.net" }
open class Vidguardto : ExtractorApi() {
    override var name = "Vidguard"
    override var mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = if (url.contains("/d/") || url.contains("/v/")) url.replace("/d/", "/e/").replace("/v/", "/e/") else url
        val doc = app.get(embedUrl).document
        val script = doc.selectFirst("script:containsData(eval)")?.data() ?: return
        val result = runJS(script)
        val json = JSONObject(result)
        val watchlink = sigDecode(json.optString("stream"))
        MasterLinkGenerator.createSmartLink(this.name, watchlink, mainUrl, callback = callback)
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=").getOrNull(1)?.split("&")?.getOrNull(0) ?: return url
        val t = sig.chunked(2).joinToString("") { (it.toInt(16) xor 2).toChar().toString() }.let {
            val padding = when (it.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            String(Base64.getDecoder().decode((it + padding).toByteArray()))
        }.dropLast(5).reversed().toCharArray().apply {
            for (i in indices step 2) { if (i + 1 < size) { this[i] = this[i + 1].also { this[i + 1] = this[i] } } }
        }.concatToString().dropLast(5)
        return url.replace(sig, t)
    }

    private fun runJS(js: String): String {
        var result = ""
        val r = Runnable {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(scope, js, "JavaScript", 1, null)
                val svg = scope.get("svg", scope)
                result = if (svg is NativeObject) NativeJSON.stringify(Context.getCurrentContext(), scope, svg, null, null).toString()
                else Context.toString(svg)
            } catch (e: Exception) { Log.e("Vidguard", "JS error: ${e.message}") }
            finally { Context.exit() }
        }
        val t = Thread(ThreadGroup("A"), r, "rhino", 8 * 1024 * 1024)
        t.start(); t.join(); t.interrupt()
        return result
    }
}
class Minochinos : ExtractorApi() { 
    override var name = "Minochinos"; 
    override var mainUrl = "https://minochinos.com"; 
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val packed = findPackedJsInPage(text)
        val unpacked = if (packed != null) {
            decodePackedJs(packed.first, packed.second, packed.third)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data() ?: return
        }
        val urls = CompiledRegexPatterns.extractAllVideoUrls(unpacked)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
    }
}
class Vidhide : ExtractorApi() { override var name = "Vidhide"; override var mainUrl = "https://vidhide.com"; override val requiresReferer = true }
class PlayPutarIn : ExtractorApi() {
    override var name = "PlayPutarIn"
    override var mainUrl = "https://play.putar.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val targetUrl = url.substringAfter("?url=").let { java.net.URLDecoder.decode(it, "UTF-8") }
        if (targetUrl.isNotBlank() && targetUrl.startsWith("http")) {
            loadExtractorWithFallbackCustom(targetUrl, url, subtitleCallback, callback = callback, providerTag = this.name, callChain = "PlayPutarIn")
        }
    }
}
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

object ProviderExtractors {
    val list = listOf(
        Dailymotion(), Odnoklassniki(), Rumble(), StreamRuby(), Svanila(), Svilla(), 
        ByseSX(), Hownetwork(), Cloudhownetwork(),
        PlayStreamplay(), AnichinStream(), AbyssPlayer(), Filedon(), BloggerVideo(),
        Ultrahd(), Vtbe(), wishfast(),
        Minochinos(), Vidhide(), ShortIcu(), PlayPutarIn(), StreamHG(),
        MegaPlay(), AWSStream(), LuluStream(), Dhcplay(), Voe(), Xtwap(), Gdplayer(), Vidguardto2(),
        Lk21PlayerPage()
    )
}

// ============================================
// REGION 6: JS PACKER DECODER
// ============================================

private val BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
private val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

private fun toBase(n: Int, base: Int): String {
    if (n == 0) return if (base == 36) "0" else "0"
    val chars = if (base == 36) BASE36_CHARS else BASE62_CHARS
    val sb = StringBuilder()
    var num = n
    while (num > 0) {
        sb.append(chars[num % base])
        num /= base
    }
    return sb.reverse().toString()
}

private fun decodePackedJs(payload: String, keywords: List<String>, base: Int): String {
    var result = payload
    for (i in keywords.size - 1 downTo 0) {
        val kw = keywords.getOrNull(i) ?: continue
        if (kw.isNotBlank()) {
            val encoded = Regex.escape(toBase(i, base))
            result = result.replace(Regex("\\b$encoded\\b"), kw)
        }
    }
    return result
}

private fun findPackedJsInPage(html: String): Triple<String, List<String>, Int>? {
    val scriptRegex = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    for (match in scriptRegex.findAll(html)) {
        val script = match.value
        if (!script.contains("function(p,a,c,k,e,d)") || !script.contains(".split")) continue
        val packedRegex = Regex("""}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|\'""", setOf(RegexOption.DOT_MATCHES_ALL))
        val m = packedRegex.find(script) ?: continue
        val payloadRaw = m.groupValues[1].replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/")
        val base = m.groupValues[2].toIntOrNull() ?: 36
        val kwStr = m.groupValues[4]
        val keywords = kwStr.split("|")
        return Triple(payloadRaw, keywords, base)
    }
    return null
}

// ============================================
// REGION 7: EXTRACTORS (ADDITIONAL)
// ============================================

open class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"; override var mainUrl = "https://rubyvidhub.com"; override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: return
        val response = app.post("$mainUrl/dl", data = mapOf("op" to "embed", "file_code" to id, "auto" to "1"), referer = referer)
        var urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        if (urls.isEmpty()) {
            val decoded = findPackedJsInPage(response.text)?.let { (p, k, b) -> decodePackedJs(p, k, b) } ?: response.text
            val fileMatch = Regex("""file\s*:\s*"([^"]+)""").find(decoded)
            if (fileMatch != null) {
                val fileUrl = fileMatch.groupValues[1]
                if (fileUrl.startsWith("http")) {
                    MasterLinkGenerator.createSmartLink(this.name, fileUrl, mainUrl, callback = callback)
                    return
                }
            }
            urls = CompiledRegexPatterns.extractAllVideoUrls(decoded)
        }
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, mainUrl, callback = callback) }
    }
}

class AnichinStream : ExtractorApi() {
    override var name = "AnichinStream"
    override var mainUrl = "https://anichin.stream"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Extract video ID from URL (?id=xxx)
        val id = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1) ?: return
        val videoUrl = "$mainUrl/hls/$id.m3u8"
        MasterLinkGenerator.createSmartLink(this.name, videoUrl, referer ?: mainUrl, callback = callback)
    }
}

class PlayStreamplay : ExtractorApi() {
    override var name = "PlayStreamplay"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("ads") && !src.contains("ads?")) {
                loadExtractorWithFallbackCustom(fixUrlSmart(src, url), url, subtitleCallback, callback = callback, providerTag = name, callChain = "PlayStreamplay")
            }
        }
    }
}

class AbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )
        val doc = app.get(url, headers = headers).document
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val response = app.post("https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".trimIndent().toRequestBody("application/json".toMediaType())
        ).text
        val json = JSONObject(response).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val src = sources.getJSONObject(i)
            if (src.optBoolean("status", false)) {
                MasterLinkGenerator.createSmartLink(this.name, src.getString("url"), "https://playhydrax.com", callback = callback)
            }
        }
    }
}

class Filedon : ExtractorApi() {
    override var name = "Filedon"
    override var mainUrl = "https://filedon.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        if (urls.isNotEmpty()) {
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach { MasterLinkGenerator.createSmartLink(this.name, it, url, callback = callback) }
        }
    }
}

class BloggerVideo : ExtractorApi() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        doc.select("video source[src], video[src], iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains("youtube"))) {
                loadExtractorWithFallbackCustom(src, url, subtitleCallback, callback = callback, providerTag = name, callChain = "BloggerVideo")
            }
        }
    }
}

// ============================================
// REGION 8: LK21 PLAYER.JS DECRYPTION
// ============================================

private var cachedLk21Scope: Scriptable? = null
private var cachedPlayerJsText: String? = null

suspend fun decryptLk21PlayerUrl(encrypted: String): String? {
    if (encrypted.isBlank() || encrypted.startsWith("http")) return null
    return runCatching {
        val ctx = Context.enter()
        try {
            var scope = cachedLk21Scope
            if (scope == null) {
                scope = ctx.initStandardObjects()
                ctx.optimizationLevel = -1
                ScriptableObject.putProperty(scope, "window", scope)
                ScriptableObject.putProperty(scope, "globalThis", scope)
                ScriptableObject.putProperty(scope, "navigator", ctx.newObject(scope))
                ScriptableObject.putProperty(scope, "location", ctx.newObject(scope))
                ScriptableObject.putProperty(scope, "document", ctx.newObject(scope))
                val polyfill = """
                    var setTimeout = function(){};
                    var clearTimeout = function(){};
                    var console = {log:function(){},warn:function(){},error:function(){}};
                    var atob = function(s) {
                        try {
                            var b = java.util.Base64.getDecoder().decode(new java.lang.String(s).getBytes("ISO-8859-1"));
                            return new java.lang.String(b, 0, b.length, "ISO-8859-1");
                        } catch(e) { return ''; }
                    };
                """.trimIndent()
                ctx.evaluateString(scope, polyfill, "polyfill", 1, null)
                val js = cachedPlayerJsText ?: run {
                    val text = app.get("https://assets.lk21.party/js/player.js?v=4").text
                    cachedPlayerJsText = text; text
                }
                ctx.evaluateString(scope, js, "player.js", 1, null)
                cachedLk21Scope = scope
            }
            val fn = scope.get("_L", scope) as org.mozilla.javascript.Function
            val result = fn.call(ctx, scope, scope, arrayOf(encrypted))
            Context.toString(result)
        } finally { Context.exit() }
    }.getOrNull()
}

class Lk21PlayerPage : ExtractorApi() {
    override var name = "Lk21Player"
    override var mainUrl = "https://playeriframe.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractorWithFallbackCustom(src, url, subtitleCallback, callback = callback, providerTag = "Lk21Player", callChain = "Lk21Player")
            }
        }
    }
}
