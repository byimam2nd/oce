package com.baseprovider

import com.lagradost.api.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

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
            val fn = scope.get("_L", scope) as Function
            val result = fn.call(ctx, scope, scope, arrayOf(encrypted))
            Context.toString(result)
        } finally { Context.exit() }
    }.getOrNull()
}
