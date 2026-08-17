package com.baseprovider.config

import com.lagradost.api.Log
import org.json.JSONObject

private const val PARSER_TAG = "ExtractorConfigParser"

/**
 * Key JSON yang dikenali parser untuk [ExtractorConfig], [IdSource],
 * [ExtractorVariant], dan tiap step type. Dipakai utk mendeteksi typo.
 */
val KNOWN_EXTRACTOR_KEYS: Set<String> = setOf(
    // identity
    "id", "name", "mainUrl", "requiresReferer", "cached",
    "idSource", "variants", "steps", "videoReferer", "outputFilter",
    // idSource
    "type", "param", "pattern", "group", "selector", "attr",
    // variant
    "headers", "referer", "userAgent",
    // step (shared)
    "url", "data", "jsonBody", "store", "filter", "universal",
    "startMarker", "endMarker", "template", "source", "path",
    "decodeUnicode", "base", "urlReplace",
    // step type selector
    "step",
)

private fun warnUnknownKeys(scope: String, json: JSONObject, known: Set<String>) {
    val unknown = json.keys().asSequence()
        .filterNot { it in known }
        .toList()
    if (unknown.isNotEmpty()) {
        Log.w(PARSER_TAG, "$scope unknown/unused keys (mungkin typo): ${unknown.joinToString()}")
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

private fun parseVariant(json: JSONObject): ExtractorVariant {
    warnUnknownKeys("Variant", json, KNOWN_EXTRACTOR_KEYS)
    return ExtractorVariant(
        name = json.optString("name", "default"),
        headers = jsonObjectToMap(json.optJSONObject("headers")),
        referer = json.optString("referer", ""),
        userAgent = json.optString("userAgent", ""),
    )
}

private fun parseIdSource(json: JSONObject?): IdSource? {
    if (json == null) return null
    warnUnknownKeys("idSource", json, KNOWN_EXTRACTOR_KEYS)
    return IdSource(
        type = json.optString("type", "none"),
        param = json.optString("param", ""),
        pattern = json.optString("pattern", ""),
        group = json.optInt("group", 1),
        selector = json.optString("selector", ""),
        attr = json.optString("attr", "src"),
    )
}

private fun parseStep(json: JSONObject): ExtractorStep? {
    val step = json.optString("step", "")
    if (step.isBlank()) {
        Log.w(PARSER_TAG, "Step without \"step\" key (mungkin typo): ${json.toString().take(80)}")
        return null
    }
    warnUnknownKeys("Step($step)", json, KNOWN_EXTRACTOR_KEYS)
    return when (step) {
        "fetch" -> ExtractorStep.Fetch(
            url = json.optString("url", "{url}"),
            referer = json.optString("referer", ""),
            headers = jsonObjectToMap(json.optJSONObject("headers")),
            store = json.optString("store", "response"),
            urlReplace = jsonObjectToMap(json.optJSONObject("urlReplace")),
        )
        "postForm" -> ExtractorStep.PostForm(
            url = json.optString("url", ""),
            data = jsonObjectToMap(json.optJSONObject("data")),
            referer = json.optString("referer", ""),
            headers = jsonObjectToMap(json.optJSONObject("headers")),
            store = json.optString("store", "response"),
        )
        "postJson" -> ExtractorStep.PostJson(
            url = json.optString("url", ""),
            jsonBody = json.optString("jsonBody", ""),
            referer = json.optString("referer", ""),
            headers = jsonObjectToMap(json.optJSONObject("headers")),
            store = json.optString("store", "response"),
        )
        "regex" -> ExtractorStep.Regex(
            pattern = json.optString("pattern", ""),
            group = json.optInt("group", 1),
            source = json.optString("source", "response"),
            filter = json.optString("filter", ""),
            universal = json.optBoolean("universal", false),
            decodeUnicode = json.optBoolean("decodeUnicode", false),
            store = json.optString("store", ""),
        )
        "jsonPath" -> ExtractorStep.JsonPath(
            path = json.optString("path", ""),
            source = json.optString("source", "response"),
            filter = json.optString("filter", ""),
            store = json.optString("store", ""),
        )
        "constructUrl" -> ExtractorStep.ConstructUrl(
            template = json.optString("template", ""),
            store = json.optString("store", ""),
        )
        "substring" -> ExtractorStep.Substring(
            startMarker = json.optString("startMarker", ""),
            endMarker = json.optString("endMarker", ""),
            source = json.optString("source", "response"),
            store = json.optString("store", ""),
        )
        "resolveUrl" -> ExtractorStep.ResolveUrl(
            base = json.optString("base", "{url}"),
            source = json.optString("source", ""),
        )
        else -> {
            Log.w(PARSER_TAG, "Unknown step type: $step")
            null
        }
    }
}

fun fromExtractorJson(id: String, json: JSONObject): ExtractorConfig {
    warnUnknownKeys("Config[$id]", json, KNOWN_EXTRACTOR_KEYS)

    val steps = mutableListOf<ExtractorStep>()
    val stepsArr = json.optJSONArray("steps")
    if (stepsArr != null) {
        for (i in 0 until stepsArr.length()) {
            stepsArr.optJSONObject(i)?.let { parseStep(it)?.let { s -> steps.add(s) } }
        }
    }

    val variants = mutableListOf<ExtractorVariant>()
    val variantsArr = json.optJSONArray("variants")
    if (variantsArr != null) {
        for (i in 0 until variantsArr.length()) {
            variantsArr.optJSONObject(i)?.let { v -> variants.add(parseVariant(v)) }
        }
    }

    return ExtractorConfig(
        id = id,
        name = json.optString("name", id),
        mainUrl = json.optString("mainUrl", "https://example.com"),
        requiresReferer = json.optBoolean("requiresReferer", true),
        cached = json.optBoolean("cached", false),
        idSource = parseIdSource(json.optJSONObject("idSource")),
        variants = variants.ifEmpty { listOf(ExtractorVariant()) },
        steps = steps,
        videoReferer = json.optString("videoReferer", ""),
        outputFilter = json.optString("outputFilter", "adaptive"),
    )
}