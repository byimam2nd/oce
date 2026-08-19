package com.baseprovider.extractor

import com.baseprovider.config.ExtractorConfig
import com.baseprovider.config.ExtractorStep
import com.baseprovider.config.ExtractorVariant
import com.baseprovider.log.*
import com.baseprovider.model.fixUrlSmart
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generic config-driven extractor — SATU sumber kebenaran berisi SEMUA metode
 * ekstraksi (fetch/post/regex/jsonPath/constructUrl/substring). Extractor baru
 * cukup menyediakan file config JSON ([com.baseprovider.config.ExtractorConfig])
 * yang memilih kombinasi steps yang dibutuhkan, tanpa menulis class Kotlin baru.
 *
 * Alur:
 *  1. Ekstrak `{id}` dari URL masuk sesuai [IdSource].
 *  2. Coba [ExtractorVariant] pertama; jalankan semua [ExtractorStep].
 *     Jika menghasilkan video URL → selesai. Jika tidak → coba variant
 *     berikutnya (header strategy fallback) sampai ada hasil.
 *  3. URL hasil dikirim via [MasterLinkGenerator.createSmartLink] dengan
 *     probe otomatis (bareHeaders=true), sesuai pipeline delivery yang sama
 *     dengan extractor lain.
 */
class ConfigDrivenExtractor(private val config: ExtractorConfig) : CachedExtractorApi() {
    override var name = config.name
    override var mainUrl = config.mainUrl
    override val requiresReferer = config.requiresReferer

    private inner class RunState(
        val url: String,
        val referer: String?,
        val id: String?,
        val variant: ExtractorVariant,
    ) {
        val variables = mutableMapOf<String, String>()
        val videoUrls = mutableSetOf<String>()

        fun resolveTemplate(template: String): String {
            val base = runCatching {
                val afterScheme = url.substringAfter("://")
                url.substringBefore("://") + "://" + afterScheme.substringBefore("/")
            }.getOrDefault(url)
            var out = template
                .replace("{mainUrl}", config.mainUrl)
                .replace("{url}", url)
                .replace("{base}", base)
                .replace("{id}", id.orEmpty())
                .replace("{referer}", referer.orEmpty())
                .replace("{ts}", System.currentTimeMillis().toString())
            variables.forEach { (key, value) ->
                out = out.replace("{$key}", value)
            }
            return out
        }

        fun resolveHeaders(stepHeaders: Map<String, String>): Map<String, String> {
            val merged = HashMap(variant.headers)
            merged.putAll(stepHeaders)
            if (variant.userAgent.isNotBlank()) {
                merged["User-Agent"] = variant.userAgent
            }
            return merged.mapValues { (_, v) -> resolveTemplate(v) }
        }

        fun resolveReferer(stepReferer: String): String? {
            val tpl = stepReferer.ifBlank { variant.referer }
            val resolved = if (tpl.isBlank()) referer else resolveTemplate(tpl)
            return resolved?.ifBlank { null }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = extractId(url)
        logDebug(name, "ConfigDriven: url=$url id=$id variants=${config.variants.size} steps=${config.steps.size}")

        for (variant in config.variants) {
            val state = RunState(url, referer, id, variant)
            runCatching {
                for (step in config.steps) {
                    executeStep(step, state, subtitleCallback, callback)
                    if (state.videoUrls.isNotEmpty()) break
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logDebug(name, "Variant '${variant.name}' failed for $url: ${e.message}")
            }
            if (state.videoUrls.isNotEmpty()) {
                logDebug(name, "Variant '${variant.name}' produced ${state.videoUrls.size} link(s) for $url")
                deliver(state, callback)
                return
            }
            logDebug(name, "Variant '${variant.name}' produced 0 links, trying next")
        }
    }

    internal fun extractId(url: String): String? {        val source = config.idSource ?: return null
        return when (source.type) {
            "query" -> runCatching {
                Regex("""[?&]${source.param}=([^&]+)""").find(url)?.groupValues?.get(1)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            "path" -> url.trimEnd('/').substringAfterLast('/').substringBefore('?')
                .takeIf { it.isNotBlank() }
            "regex" -> runCatching {
                Regex(source.pattern).find(url)?.groupValues?.getOrNull(source.group)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private suspend fun executeStep(
        step: ExtractorStep,
        state: RunState,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        when (step) {
            is ExtractorStep.Fetch -> {
                var target = state.resolveTemplate(step.url)
                step.urlReplace.forEach { (from, to) -> target = target.replace(from, to) }
                if (config.cached) {
                    state.variables[step.store] = cachedGetText(target,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers))
                } else {
                    val response = app.get(target,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers))
                    state.variables[step.store] = response.text
                    if (step.storeFinalUrl.isNotBlank()) {
                        state.variables[step.storeFinalUrl] = response.url
                    }
                }
            }
            is ExtractorStep.PostForm -> {
                val target = state.resolveTemplate(step.url)
                val data = step.data.mapValues { (_, v) -> state.resolveTemplate(v) }
                val text = if (config.cached) {
                    cachedPostText(target, data = data,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers))
                } else {
                    app.post(target, data = data,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers)).text
                }
                state.variables[step.store] = text
            }
            is ExtractorStep.PostJson -> {
                val target = state.resolveTemplate(step.url)
                val body = state.resolveTemplate(step.jsonBody)
                val text = if (config.cached) {
                    cachedPostJsonText(target, body,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers))
                } else {
                    app.post(target, headers = state.resolveHeaders(step.headers),
                        requestBody = body.toRequestBody("application/json".toMediaType())).text
                }
                state.variables[step.store] = text
            }
            is ExtractorStep.Regex -> {
                val text = state.variables[step.source].orEmpty()
                val urls = if (step.universal) {
                    CompiledRegexPatterns.extractAllVideoUrls(text)
                } else {
                    runCatching {
                        Regex(step.pattern).findAll(text)
                            .mapNotNull { it.groupValues.getOrNull(step.group) }
                            .toSet()
                    }.getOrDefault(emptySet())
                }
                val decoded = urls.map { url ->
                    val u = url.replace("\\/", "/")
                    if (step.decodeUnicode) MasterLinkGenerator.decodeUnicodeEscapes(u) else u
                }
                val filtered = decoded.filter { step.filter.isBlank() || it.contains(step.filter) }
                if (step.store.isNotBlank()) {
                    filtered.firstOrNull()?.let { state.variables[step.store] = it }
                } else {
                    filtered.forEach { state.videoUrls.add(it) }
                }
            }
            is ExtractorStep.JsonPath -> {
                val text = state.variables[step.source].orEmpty()
                val emit = { value: String ->
                    if (step.filter.isBlank() || value.contains(step.filter)) {
                        if (step.store.isNotBlank()) state.variables[step.store] = value
                        else state.videoUrls.add(value)
                    }
                }
                when (val resolved = resolveJsonPath(text, step.path)) {
                    is String -> if (resolved.isNotBlank()) emit(resolved)
                    is JSONArray -> {
                        for (i in 0 until resolved.length()) {
                            val value = resolved.optString(i, "")
                            if (value.isNotBlank()) {
                                emit(value)
                                if (step.store.isNotBlank() && state.variables.containsKey(step.store)) break
                            }
                        }
                    }
                    else -> Unit
                }
            }
            is ExtractorStep.ConstructUrl -> {
                val built = state.resolveTemplate(step.template)
                if (built.isNotBlank()) {
                    if (step.store.isNotBlank()) state.variables[step.store] = built
                    else state.videoUrls.add(built)
                }
            }
            is ExtractorStep.Substring -> {
                val text = state.variables[step.source].orEmpty()
                val start = text.indexOf(step.startMarker)
                if (start >= 0) {
                    val from = start + step.startMarker.length
                    val end = text.indexOf(step.endMarker, from)
                    if (end >= 0) {
                        val value = text.substring(from, end)
                        if (value.isNotBlank()) {
                            if (step.store.isNotBlank()) state.variables[step.store] = value
                            else state.videoUrls.add(value)
                        }
                    }
                }
            }
            is ExtractorStep.ResolveUrl -> {
                val base = state.resolveTemplate(step.base)
                if (step.source.isNotBlank()) {
                    val raw = state.variables[step.source].orEmpty()
                    val resolved = fixUrlSmart(raw, base).ifBlank { raw }
                    if (resolved.isNotBlank()) state.videoUrls.add(resolved)
                } else {
                    val resolved = state.videoUrls.map { fixUrlSmart(it, base).ifBlank { it } }
                    state.videoUrls.clear()
                    resolved.filter { it.isNotBlank() }.forEach { state.videoUrls.add(it) }
                }
            }
            is ExtractorStep.PackedJs -> {
                val text = state.variables[step.source].orEmpty()
                val decoded = findPackedJsInPage(text)?.let { (p, k, b) ->
                    decodePackedJs(p, k, b)
                } ?: text
                state.variables[step.store] = decoded
            }
            is ExtractorStep.AesGcm -> {
                val text = state.variables[step.source].orEmpty()
                runCatching {
                    val json = JSONObject(text)
                    val keyParts = when (val kp = resolveJsonPath(text, step.keyPartsPath)) {
                        is JSONArray -> {
                            val parts = mutableListOf<String>()
                            for (i in 0 until kp.length()) parts.add(kp.optString(i, ""))
                            parts
                        }
                        else -> emptyList()
                    }
                    val iv = (resolveJsonPath(text, step.ivPath) as? String).orEmpty()
                    val payload = (resolveJsonPath(text, step.payloadPath) as? String).orEmpty()
                    val decrypted = decryptAesGcm(keyParts, iv, payload)
                    state.variables[step.store] = decrypted
                }
            }
            is ExtractorStep.RhinoEval -> {
                val script = state.variables[step.source].orEmpty()
                state.variables[step.store] = runRhino(script, step.objectName)
            }
            is ExtractorStep.XorSig -> {
                val url = state.variables[step.source].orEmpty()
                val decoded = sigDecode(url)
                if (decoded.isNotBlank()) {
                    if (step.store.isNotBlank()) state.variables[step.store] = decoded
                    else state.videoUrls.add(decoded)
                }
            }
            is ExtractorStep.Delegate -> {
                val target = state.resolveTemplate(step.url)
                val resolved = if (step.queryParam.isNotBlank()) {
                    runCatching {
                        val raw = target.substringAfter("?${step.queryParam}=").substringBefore("&")
                        java.net.URLDecoder.decode(raw, "UTF-8")
                    }.getOrDefault("")
                } else target
                if (resolved.startsWith("http")) {
                    loadExtractorWithFallbackCustom(
                        resolved, state.url, subtitleCallback,
                        callback = callback,
                        providerTag = name,
                        callChain = name
                    )
                }
            }
            is ExtractorStep.Iframe -> {
                val html = state.variables[step.source].orEmpty()
                val base = state.resolveTemplate(step.base)
                val includeRe = if (step.include.isNotBlank()) Regex(step.include) else null
                runCatching {
                    org.jsoup.Jsoup.parse(html).select(step.selector).forEach { el ->
                        val src = el.attr(step.attribute)
                        if (src.isBlank()) return@forEach
                        if (step.exclude.isNotBlank() && src.contains(step.exclude)) return@forEach
                        if (includeRe != null && !includeRe.containsMatchIn(src)) return@forEach
                        val resolved = fixUrlSmart(src, base)
                        if (resolved.isNotBlank()) {
                            loadExtractorWithFallbackCustom(
                                resolved, state.url, subtitleCallback,
                                callback = callback,
                                providerTag = name,
                                callChain = name
                            )
                        }
                    }
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            is ExtractorStep.Redirect -> {
                val finalUrl = state.variables[step.source].orEmpty()
                val original = state.resolveTemplate(step.url)
                if (finalUrl.isNotBlank() && finalUrl != original) {
                    loadExtractor(finalUrl, state.url, subtitleCallback, callback)
                }
            }
            is ExtractorStep.Webview -> {
                val target = state.resolveTemplate(step.url)
                runCatching {
                    val resolver = com.lagradost.cloudstream3.network.WebViewResolver(
                        interceptUrl = Regex(step.interceptPattern),
                        additionalUrls = listOf(Regex(step.interceptPattern)),
                        useOkhttp = false,
                        timeout = step.timeoutMs
                    )
                    val interceptedUrl = app.get(target,
                        referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers),
                        interceptor = resolver).url
                    if (interceptedUrl.isNotBlank()) state.videoUrls.add(interceptedUrl)
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    /** Ambil nilai dari JSON: "file", "sources[0].file", "qualities.auto[].url". */
    internal fun resolveJsonPath(text: String, path: String): Any? {
        if (path.isBlank() || text.isBlank()) return null
        return try {
            resolveJsonPathRecursive(JSONObject(text), path.split(".").toMutableList())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Traversal JSON mendukung:
     *  - "a.b.c"          → objek bertingkat
     *  - "arr[0].file"    → indeks array
     *  - "qualities.auto[].url" → wildcard: kumpulkan `.url` dari SEMUA elemen
     *    (array) atau semua nilai (object). Hasil akhir berupa JSONArray of String.
     *    Jika wildcard menghasilkan objek bernilai tunggal (JSONArray len 1),
     *    di-unwrap jadi String langsung.
     */
    private fun resolveJsonPathRecursive(current: Any, segments: MutableList<String>): Any? {
        if (segments.isEmpty()) {
            return when (current) {
                is String -> current
                is JSONArray -> if (current.length() == 1) current.opt(0) else current
                else -> current
            }
        }
        val segment = segments.removeAt(0)
        val wildcard = Regex("""^(\w+)\[\]$""").find(segment)
        if (wildcard != null) {
            val name = wildcard.groupValues[1]
            val children = when (current) {
                is JSONObject -> {
                    val v = current.opt(name)
                    when (v) {
                        is JSONArray -> v
                        is JSONObject -> {
                            val arr = JSONArray()
                            val it = v.keys()
                            while (it.hasNext()) arr.put(v.opt(it.next()))
                            arr
                        }
                        else -> null
                    }
                }
                else -> null
            } ?: return null
            val results = JSONArray()
            for (i in 0 until children.length()) {
                val child = children.opt(i) ?: continue
                val childSegments = ArrayList(segments)
                val value = resolveJsonPathRecursive(child, childSegments)
                if (value != null) {
                    if (value is JSONArray) for (j in 0 until value.length()) results.put(value.opt(j))
                    else results.put(value)
                }
            }
            return if (results.length() == 0) null
            else if (results.length() == 1 && results.opt(0) is String) results.opt(0)
            else results
        }
        val arrayIdx = Regex("""^(\w+)\[(\d+)\]$""").find(segment)
        return if (arrayIdx != null) {
            val name = arrayIdx.groupValues[1]
            val idx = arrayIdx.groupValues[2].toInt()
            val child = when (current) {
                is JSONObject -> current.optJSONArray(name)?.opt(idx)
                else -> null
            } ?: return null
            resolveJsonPathRecursive(child, segments)
        } else {
            val child = when (current) {
                is JSONObject -> {
                    val v = current.opt(segment)
                    if (v is JSONArray && v.length() == 1) v.opt(0) else v
                }
                else -> null
            } ?: return null
            resolveJsonPathRecursive(child, segments)
        }
    }

    private suspend fun deliver(state: RunState, callback: (ExtractorLink) -> Unit) {
        val urls = when (config.outputFilter) {
            "master" -> CompiledRegexPatterns.filterMasterM3u8(state.videoUrls)
            "none" -> state.videoUrls.toList()
            else -> CompiledRegexPatterns.prioritizeAdaptiveUrls(state.videoUrls)
        }
        val videoRef = state.resolveTemplate(config.videoReferer)
            .ifBlank { null }
        urls.forEach { url ->
            MasterLinkGenerator.createSmartLink(
                name, url, videoRef,
                headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true,
                callback = callback
            )
        }
    }

    /** Decrypt AES/GCM/NoPadding: key = b64url(parts[0]) + b64url(parts[1]). */
    private fun decryptAesGcm(keyParts: List<String>, iv: String, payload: String): String {
        if (keyParts.isEmpty() || iv.isBlank() || payload.isBlank()) return ""
        return try {
            val key = keyParts.flatMap { b64UrlDecode(it).toList() }.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, b64UrlDecode(iv))
            )
            val decrypted = cipher.doFinal(b64UrlDecode(payload))
            String(decrypted, StandardCharsets.UTF_8)
                .let { if (it.startsWith("\uFEFF")) it.substring(1) else it }
        } catch (_: Exception) {
            ""
        }
    }

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.getDecoder().decode(fixed + pad)
    }

    /** Eval JS via Rhino di Dispatchers.Default, ambil objek lalu stringify. */
    private suspend fun runRhino(js: String, objectName: String): String =
        withContext(Dispatchers.Default) {
            if (js.isBlank()) return@withContext ""
            try {
                val rhino = Context.enter()
                try {
                    rhino.optimizationLevel = -1
                    val scope: Scriptable = rhino.initSafeStandardObjects()
                    scope.put("window", scope, scope)
                    rhino.evaluateString(scope, js, "JavaScript", 1, null)
                    val obj = scope.get(objectName, scope)
                    if (obj is NativeObject) NativeJSON.stringify(
                        Context.getCurrentContext(), scope, obj, null, null
                    ).toString()
                    else Context.toString(obj)
                } finally { Context.exit() }
            } catch (_: Exception) {
                ""
            }
        }

    /** Decode signature URL: xor hex → base64 → drop/reverse/swap (Vidguardto). */
    private fun sigDecode(url: String): String {
        if (url.isBlank()) return url
        val sig = url.split("sig=").getOrNull(1)?.split("&")
            ?.getOrNull(0) ?: return url
        val t = sig.chunked(2).joinToString("") { (it.toInt(16) xor 2)
            .toChar().toString() }.let {
            val padding = when (it.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            String(Base64.getDecoder().decode((it + padding).toByteArray()))
        }.dropLast(5).reversed().toCharArray().apply {
            for (i in indices step 2) { if (i + 1 < size) { this[i] =
                this[i + 1].also { this[i + 1] = this[i] } } }
        }.concatToString().dropLast(5)
        return url.replace(sig, t)
    }
}