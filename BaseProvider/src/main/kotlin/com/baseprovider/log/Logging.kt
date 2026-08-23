package com.baseprovider.log

import com.lagradost.api.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

object ProviderLog {
    private const val GLOBAL_PREFIX = "OCE"

    private const val SUPABASE_BATCH_SIZE = 10
    private const val SUPABASE_FLUSH_INTERVAL_MS = 2000L

    private val SUPABASE_URL: String get() = System.getenv("SUPABASE_URL")
        ?: SupabaseBakedConfig.URL
    private val SUPABASE_ANON_KEY: String get() = System.getenv("SUPABASE_ANON_KEY")
        ?: SupabaseBakedConfig.ANON_KEY

    private val supabaseBuffer = java.util.concurrent
        .ConcurrentLinkedQueue<org.json.JSONObject>()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sbJob = Job()
    private val supabaseFlushLock = Any()
    private val supabaseFlushScheduled = java.util.concurrent.atomic
        .AtomicBoolean(false)

    fun log(
        level: LogLevel, tag: String, message: String,
        error: Throwable? = null, url: String? = null,
        method: String? = null, type: FailureType? = null,
        selectors: String = "",
        stage: String? = null, extractor: String? = null,
        attempt: Int? = null, durationMs: Long? = null,
        runId: String? = null
    ) {
        val errTrace = error?.let {
            buildString {
                val cause = it.message ?: it.javaClass.simpleName
                append("\n")
                append("Cause : $cause\n")
                it.stackTrace.take(3).forEachIndexed { index, frame ->
                    append("Stack ${index + 1}: ${frame.fileName}:${frame.lineNumber}\n")
                }
            }
        } ?: ""
        val tracebackJson = error?.let { err ->
            org.json.JSONObject().apply {
                put("class", err.javaClass.name)
                put("message", err.message ?: err.javaClass.simpleName)
                put("stack", org.json.JSONArray().apply {
                    err.stackTrace.take(10).forEach { frame ->
                        put(org.json.JSONObject().apply {
                            put("file", frame.fileName)
                            put("line", frame.lineNumber)
                            put("method", frame.methodName)
                        })
                    }
                })
            }
        }
        val host = url?.let { runCatching { URI(it).host }
            .getOrElse { e -> Log.d("OCE", "URI parsing failed for $url: ${e.message}"); null } } ?: ""
        val ft = type ?: if (host.contains("short.")) FailureType
            .SHORTLINK_FAILURE else FailureType.UNKNOWN
        val hostInfo = if (host.isNotBlank()) " | host=$host" else ""
        val methodInfo = if (method != null) " | method=$method" else ""
        val typeInfo = " | type=${ft.label}"
        val selInfo = if (selectors
            .isNotBlank()) " | selectors=$selectors" else ""
        val stageInfo = if (stage != null) " | stage=$stage" else ""
        val extractorInfo = if (extractor != null) " | extractor=$extractor" else ""
        val attemptInfo = if (attempt != null) " | attempt=$attempt" else ""
        val durInfo = if (durationMs != null) " | dur=${durationMs}ms" else ""
        val logcatMsg = "[$tag]${stageInfo}${methodInfo}$typeInfo$extractorInfo$attemptInfo$durInfo${selInfo}$hostInfo | $message"
        val fullMsg = message + errTrace

        when (level) {
            LogLevel.DEBUG -> Log.d(GLOBAL_PREFIX, logcatMsg)
            LogLevel.SUCCESS -> Log.i(GLOBAL_PREFIX, logcatMsg)
            LogLevel.FAIL -> Log.w(GLOBAL_PREFIX, logcatMsg)
            LogLevel.ERROR -> Log.e(GLOBAL_PREFIX, logcatMsg)
            LogLevel.CRITICAL -> Log.e(GLOBAL_PREFIX, logcatMsg)
        }

        // SUCCESS di-upload juga: dibutuhkan sebagai penanda runtime/telemetri
        // (MovieGateSkip, EpiStats, Loaded page) agar perilaku device dapat
        // diaudit remote. DEBUG tetap lokal (volume terlalu besar).
        if (level != LogLevel.DEBUG) {
            sendToSupabase(
                level.name, tag, fullMsg, url, host, method, ft,
                selectors, stage, extractor, attempt, durationMs, runId,
                tracebackJson
            )
        }
    }

    private fun sendToSupabase(
        level: String, tag: String, message: String,
        url: String?, host: String, method: String?,
        type: FailureType, selectors: String = "",
        stage: String? = null, extractor: String? = null,
        attempt: Int? = null, durationMs: Long? = null,
        runId: String? = null, traceback: org.json.JSONObject? = null
    ) {
        if (SUPABASE_URL.isBlank() || SUPABASE_ANON_KEY.isBlank()) return
        val row = org.json.JSONObject().apply {
            put("level", level)
            put("tag", tag)
            put("message", message)
            put("host", host ?: org.json.JSONObject.NULL)
            put("url", url ?: org.json.JSONObject.NULL)
            put("method", method ?: org.json.JSONObject.NULL)
            put("failure_type", type.label)
            put("selectors", selectors.ifBlank { "" })
            put("stage", stage ?: org.json.JSONObject.NULL)
            put("extractor", extractor ?: org.json.JSONObject.NULL)
            put("attempt", attempt ?: org.json.JSONObject.NULL)
            put("duration_ms", durationMs ?: org.json.JSONObject.NULL)
            put("run_id", runId ?: org.json.JSONObject.NULL)
            put("traceback", traceback ?: org.json.JSONObject.NULL)
        }
        supabaseBuffer.add(row)
        if (supabaseBuffer.size >= SUPABASE_BATCH_SIZE) {
            synchronized(supabaseFlushLock) { flushSupabaseBatch() }
        } else if (supabaseFlushScheduled.compareAndSet(false, true)) {
            logScope.launch(sbJob) {
                kotlinx.coroutines.delay(SUPABASE_FLUSH_INTERVAL_MS)
                synchronized(supabaseFlushLock) {
                    supabaseFlushScheduled.set(false)
                    flushSupabaseBatch()
                }
            }
        }
    }

    private fun flushSupabaseBatch() {
        while (supabaseBuffer.isNotEmpty()) {
            val batch = ArrayList<org.json.JSONObject>(SUPABASE_BATCH_SIZE)
            while (batch.size < SUPABASE_BATCH_SIZE && supabaseBuffer
                    .isNotEmpty()) {
                supabaseBuffer.poll()?.let { batch.add(it) }
            }
            if (batch.isEmpty()) return
            logScope.launch(sbJob) {
                runCatching {
                    val body = org.json.JSONArray().apply {
                        batch.forEach { put(it) }
                    }
                    com.lagradost.cloudstream3.app.post(
                        "$SUPABASE_URL/rest/v1/logs",
                        headers = mapOf(
                            "apikey" to SUPABASE_ANON_KEY,
                            "Authorization" to "Bearer $SUPABASE_ANON_KEY",
                            "Content-Type" to "application/json",
                            "Prefer" to "return=minimal"
                        ),
                        requestBody = body.toString().toRequestBody(
                            "application/json".toMediaType())
                    ).text
                    Log.d("OCE", "Supabase log insert ok: ${batch.size} rows")
                }.onFailure { e ->
                    Log.e("OCE", "Supabase log insert failed: ${e.message}")
                }
            }
        }
    }
}

fun log(
    level: LogLevel, tag: String, message: String,
    error: Throwable? = null, url: String? = null,
    method: String? = null, type: FailureType? = null,
    selectors: String = "",
    stage: String? = null, extractor: String? = null,
    attempt: Int? = null, durationMs: Long? = null,
    runId: String? = null
) = ProviderLog.log(level, tag, message, error, url, method, type,
    selectors, stage, extractor, attempt, durationMs, runId)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag,
    message)
fun logFail(
    tag: String, message: String, url: String? = null,
    method: String? = null, type: FailureType? = null,
    selectors: String = "",
    stage: String? = null, extractor: String? = null,
    attempt: Int? = null, durationMs: Long? = null,
    runId: String? = null
) = log(LogLevel.FAIL, tag, message, url = url, method = method, type =
    type, selectors = selectors, stage = stage, extractor = extractor,
    attempt = attempt, durationMs = durationMs, runId = runId)
fun logError(
    tag: String, message: String, error: Throwable? = null,
    url: String? = null, method: String? = null,
    type: FailureType? = null,
    stage: String? = null, extractor: String? = null,
    attempt: Int? = null, durationMs: Long? = null,
    runId: String? = null
) = log(LogLevel.ERROR, tag, message, error, url, method, type,
    stage = stage, extractor = extractor, attempt = attempt,
    durationMs = durationMs, runId = runId)
fun logCritical(
    tag: String, message: String, error: Throwable? = null,
    url: String? = null, method: String? = null,
    type: FailureType? = null, selectors: String = "",
    stage: String? = null, extractor: String? = null,
    attempt: Int? = null, durationMs: Long? = null,
    runId: String? = null
) = log(LogLevel.CRITICAL, tag, message, error, url, method, type,
    selectors, stage, extractor, attempt, durationMs, runId)
fun logSuccess(
    tag: String, message: String, url: String? = null,
    method: String? = null, selectors: String = "",
    stage: String? = null, extractor: String? = null,
    attempt: Int? = null, durationMs: Long? = null,
    runId: String? = null
) = log(LogLevel.SUCCESS, tag, message, url = url, method = method, type =
    FailureType.SUCCESS, selectors = selectors, stage = stage,
    extractor = extractor, attempt = attempt, durationMs = durationMs,
    runId = runId)
