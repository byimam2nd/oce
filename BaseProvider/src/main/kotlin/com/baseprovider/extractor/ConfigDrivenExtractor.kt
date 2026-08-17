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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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

        fun resolveTemplate(template: String): String =
            template
                .replace("{mainUrl}", config.mainUrl)
                .replace("{url}", url)
                .replace("{id}", id.orEmpty())
                .replace("{referer}", referer.orEmpty())

        fun resolveHeaders(stepHeaders: Map<String, String>): Map<String, String> {
            val merged = HashMap(variant.headers)
            merged.putAll(stepHeaders)
            if (variant.userAgent.isNotBlank()) {
                merged["User-Agent"] = variant.userAgent
            }
            return merged
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
                    executeStep(step, state)
                    if (state.videoUrls.isNotEmpty()) break
                }
            }.onFailure { e ->
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

    internal suspend fun extractId(url: String): String? {        val source = config.idSource ?: return null
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

    private suspend fun executeStep(step: ExtractorStep, state: RunState) {
        when (step) {
            is ExtractorStep.Fetch -> {
                val target = state.resolveTemplate(step.url)
                val text = if (config.cached) {
                    cachedGetText(target, referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers))
                } else {
                    app.get(target, referer = state.resolveReferer(step.referer),
                        headers = state.resolveHeaders(step.headers)).text
                }
                state.variables[step.store] = text
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
                when (val resolved = resolveJsonPath(text, step.path)) {
                    is String -> {
                        if (resolved.isNotBlank()) {
                            if (step.store.isNotBlank()) state.variables[step.store] = resolved
                            else state.videoUrls.add(resolved)
                        }
                    }
                    is JSONArray -> {
                        if (step.store.isNotBlank()) {
                            for (i in 0 until resolved.length()) {
                                val value = resolved.optString(i, "")
                                if (value.isNotBlank()) { state.variables[step.store] = value; break }
                            }
                        } else {
                            for (i in 0 until resolved.length()) {
                                val value = resolved.optString(i, "")
                                if (value.isNotBlank()) state.videoUrls.add(value)
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
                val targets = if (step.source.isNotBlank()) {
                    state.variables[step.source]?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                        ?: emptyList()
                } else {
                    state.videoUrls.toList()
                }
                val resolved = targets.map { fixUrlSmart(it, base).ifBlank { it } }
                if (step.source.isNotBlank()) {
                    resolved.firstOrNull()?.let { state.variables[step.source] = it }
                } else {
                    state.videoUrls.clear()
                    resolved.filter { it.isNotBlank() }.forEach { state.videoUrls.add(it) }
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
                        is JSONObject -> JSONArray().apply { keys().forEach { put(it) } }
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
}