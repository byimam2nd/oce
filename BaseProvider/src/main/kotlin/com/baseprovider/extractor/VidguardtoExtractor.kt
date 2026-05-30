package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.Base64

class Vidguardto2 : Vidguardto() { override var mainUrl = "https://listeamed.net" }

open class Vidguardto : ExtractorApi() {
    override var name = "Vidguard"
    override var mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = if (url.contains("/d/") || url.contains("/v/")) url.replace("/d/", "/e/").replace("/v/", "/e/") else url
        val doc = app.get(embedUrl, referer = referer ?: mainUrl).document
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

    private suspend fun runJS(js: String): String = withContext(Dispatchers.Default) {
        try {
            val rhino = Context.enter()
            try {
                rhino.optimizationLevel = -1
                val scope: Scriptable = rhino.initSafeStandardObjects()
                scope.put("window", scope, scope)
                rhino.evaluateString(scope, js, "JavaScript", 1, null)
                val svg = scope.get("svg", scope)
                if (svg is NativeObject) NativeJSON.stringify(Context.getCurrentContext(), scope, svg, null, null).toString()
                else Context.toString(svg)
            } finally { Context.exit() }
        } catch (e: Exception) { Log.e("Vidguard", "JS error: ${e.message}"); "" }
    }
}
