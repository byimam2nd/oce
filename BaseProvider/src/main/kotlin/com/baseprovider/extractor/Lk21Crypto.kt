package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.api.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

private val lk21Lock = Any()
private var cachedLk21Scope: Scriptable? = null
private var cachedPlayerJsText: String? = null
private var cachedPlayerJsTime: Long = 0L
private const val PLAYER_JS_REFRESH_MS = 30 * 60 * 1000L

private suspend fun ensureLk21Scope(ctx: Context): Scriptable {
    val now = System.currentTimeMillis()
    synchronized(lk21Lock) {
        if (cachedLk21Scope != null && now - cachedPlayerJsTime < PLAYER_JS_REFRESH_MS) {
            return cachedLk21Scope ?: error("Scope init failed")
        }
    }

    val js = if (cachedPlayerJsText != null && now - cachedPlayerJsTime < PLAYER_JS_REFRESH_MS) {
        cachedPlayerJsText ?: error("Text null after null check")
    } else {
        app.get("https://assets.lk21.party/js/player.js?v=4").text.also { cachedPlayerJsText = it }
    }

    synchronized(lk21Lock) {
        if (cachedLk21Scope != null && now - cachedPlayerJsTime < PLAYER_JS_REFRESH_MS) {
            return cachedLk21Scope ?: error("Scope init failed")
        }
        val scope = ctx.initStandardObjects()
        ctx.optimizationLevel = -1
        ScriptableObject.putProperty(scope, "window", scope)
        ScriptableObject.putProperty(scope, "globalThis", scope)
        ScriptableObject.putProperty(scope, "navigator", ctx.newObject(scope))
        ScriptableObject.putProperty(scope, "location", ctx.newObject(scope))
        ScriptableObject.putProperty(scope, "document", ctx.newObject(scope))
        ctx.evaluateString(scope, """
            var setTimeout = function(){};
            var clearTimeout = function(){};
            var console = {log:function(){},warn:function(){},error:function(){}};
            var atob = function(s) {
                try {
                    var b = java.util.Base64.getDecoder().decode(new java.lang.String(s).getBytes("ISO-8859-1"));
                    return new java.lang.String(b, 0, b.length, "ISO-8859-1");
                } catch(e) { return ''; }
            };
        """.trimIndent(), "polyfill", 1, null)
        ctx.evaluateString(scope, js, "player.js", 1, null)
        cachedLk21Scope = scope
        cachedPlayerJsTime = now
        return scope
    }
}

suspend fun decryptLk21PlayerUrl(encrypted: String): String? {
    if (encrypted.isBlank() || encrypted.startsWith("http")) return null
    return runCatching {
        val ctx = Context.enter()
        try {
            val scope = ensureLk21Scope(ctx)
            val fn = scope.get("_L", scope) as? Function ?: return@runCatching null
            val result = fn.call(ctx, scope, scope, arrayOf(encrypted))
            Context.toString(result)
        } finally { Context.exit() }
    }.getOrElse { e -> Log.d("Lk21Crypto", "Decryption failed: ${e.message}"); null }
}
