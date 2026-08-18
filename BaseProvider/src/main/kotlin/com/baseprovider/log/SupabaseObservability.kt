package com.baseprovider.log

import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Observability Supabase: scrape_runs + scrape_steps.
 *
 * Lapisan persistensi observability — BUKAN repository entity. Semua operasi
 * fire-and-forget (tidak pernah memblokir pipeline), fail silently ke logcat.
 * Config dari env SUPABASE_URL + SUPABASE_ANON_KEY (kosong = no-op).
 *
 * Source id di-resolve via catalog sources (SELECT anon) + self-register
 * (INSERT on_conflict=code, policy 0003), hasil di-cache per proses.
 *
 * Ordering (FK integrity): run row dibuat di background. Step/endRun untuk
 * run yang masih pending menunggu (bounded, non-blocking pipeline) sampai
 * run dibuat; jika run gagal, step/endRun di-skip (degradasi observability).
 */
object SupabaseObservability {

    private val URL: String get() = System.getenv("SUPABASE_URL")
        ?: SupabaseBakedConfig.URL
    private val ANON_KEY: String get() = System.getenv("SUPABASE_ANON_KEY")
        ?: SupabaseBakedConfig.ANON_KEY
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sourceIdCache = ConcurrentHashMap<String, String>()
    private val pendingRuns =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val failedRuns = java.util.Collections
        .newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun enabled(): Boolean = URL.isNotBlank() && ANON_KEY.isNotBlank()

    private fun headers(prefer: String? = null) = buildMap {
        put("apikey", ANON_KEY)
        put("Authorization", "Bearer $ANON_KEY")
        put("Content-Type", "application/json")
        if (prefer != null) put("Prefer", prefer)
    }

    /**
     * Generate run id client-side supaya run row bisa dibuat tanpa RETURNING.
     * FIRE-AND-FORGET: runId dikembalikan segera (pipeline tidak pernah
     * diblokir), run row dibuat di background. Step/log yang menyusul menunggu
     * deferred ini (bounded) supaya FK tidak drop.
     */
    fun beginRun(
        sourceCode: String, sourceName: String, sourceMainUrl: String,
        context: String, triggeredBy: String, startUrl: String
    ): String? {
        if (!enabled()) return null
        val runId = UUID.randomUUID().toString()
        val created = CompletableDeferred<Boolean>()
        pendingRuns[runId] = created
        scope.launch {
            val ok = runCatching {
                val sourceId = resolveSourceId(sourceCode, sourceName,
                    sourceMainUrl)
                if (sourceId == null) {
                    Log.w("OCE", "Observability: source resolve failed, run skipped")
                    false
                } else {
                    val body = org.json.JSONObject().apply {
                        put("id", runId)
                        put("source_id", sourceId)
                        put("context", context)
                        put("triggered_by", triggeredBy)
                        put("start_url", startUrl)
                        put("status", "running")
                    }
                    post("/rest/v1/scrape_runs", body)
                    true
                }
            }.getOrElse { e ->
                Log.w("OCE", "Observability: beginRun failed: ${e.message}")
                false
            }
            if (!ok) failedRuns.add(runId)
            pendingRuns.remove(runId)
            created.complete(ok)
        }
        return runId
    }

    /**
     * Tunggu (bounded) sampai run row dibuat. Return:
     * - true: run dibuat (atau sudah dibuat sebelumnya) → aman kirim FK ref.
     * - false: run gagal dibuat → caller skip (hindari FK violation).
     */
    private suspend fun awaitRunCreated(runId: String?): Boolean {
        if (runId == null) return false
        val created = pendingRuns[runId]
        if (created == null) return !failedRuns.contains(runId)
        val ok = withTimeoutOrNull(RUN_WAIT_TIMEOUT_MS) {
            created.await()
        } ?: false
        if (!ok) failedRuns.add(runId)
        return ok
    }

    /** Update run lifecycle: status/returned_early/durasi/error terminal. */
    fun endRun(
        runId: String?, status: String, returnedEarly: Boolean = false,
        durationMs: Long? = null, errorType: String? = null,
        errorMessage: String? = null
    ) {
        if (!enabled() || runId.isNullOrBlank()) return
        scope.launch {
            if (!awaitRunCreated(runId)) return@launch
            val body = org.json.JSONObject().apply {
                put("status", status)
                put("returned_early", returnedEarly)
                durationMs?.let { put("duration_ms", it.toInt()) }
                errorType?.let { put("error_type", it) }
                errorMessage?.let { put("error_message", it) }
                put("finished_at", java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()))
            }
            runCatching {
                patch("/rest/v1/scrape_runs?id=eq.$runId", body)
            }.onFailure { e ->
                Log.w("OCE", "Observability: endRun failed: ${e.message}")
            }
        }
    }

    /**
     * Satu baris per link attempt. kind = COLLECT (koleksi link) / EXTRACT
     * (percobaan extractor). Redaksi link_url otomatis di server (0003).
     */
    fun logStep(
        runId: String?, kind: String, status: String,
        linkUrl: String? = null, extractorChain: String? = null,
        durationMs: Long? = null, linksFound: Int? = null,
        errorType: String? = null
    ) {
        if (!enabled() || runId.isNullOrBlank()) return
        scope.launch {
            if (!awaitRunCreated(runId)) return@launch
            val body = org.json.JSONObject().apply {
                put("run_id", runId)
                put("kind", kind)
                put("status", status)
                linkUrl?.let { put("link_url", it) }
                extractorChain?.let { put("extractor_chain", it) }
                durationMs?.let { put("duration_ms", it.toInt()) }
                linksFound?.let { put("links_found", it) }
                errorType?.let { put("error_type", it) }
            }
            runCatching {
                post("/rest/v1/scrape_steps", body)
            }.onFailure { e ->
                Log.w("OCE", "Observability: logStep failed: ${e.message}")
            }
        }
    }

    /** COLLECT step: jumlah link mentah ditemukan di halaman episode. */
    fun logCollectStep(
        runId: String?, linksFound: Int, durationMs: Long? = null
    ) {
        logStep(runId, "COLLECT", "success", linksFound = linksFound,
            durationMs = durationMs)
    }

    /**
     * Resolve id source dari katalog (cache per proses). Self-register bila
     * belum ada: POST INSERT on_conflict=code DO NOTHING, lalu re-GET.
     */
    private suspend fun resolveSourceId(
        code: String, name: String, mainUrl: String
    ): String? {
        sourceIdCache[code]?.let { return it }
        val encodedCode = java.net.URLEncoder.encode(code, "UTF-8")
            .replace("+", "%20")

        val existing = runCatching {
            get("/rest/v1/sources?select=id&code=eq.$encodedCode")
        }.getOrNull()
        if (existing != null && existing != "[]") {
            val id = org.json.JSONArray(existing).optJSONObject(0)
                ?.optString("id")?.takeIf { it.isNotBlank() }
            if (id != null) {
                sourceIdCache[code] = id
                return id
            }
        }

        val body = org.json.JSONObject().apply {
            put("code", code)
            put("name", name)
            put("main_url", mainUrl)
        }
        runCatching {
            post("/rest/v1/sources?on_conflict=code", body,
                prefer = "resolution=ignore-duplicates")
        }
        val fresh = runCatching {
            get("/rest/v1/sources?select=id&code=eq.$encodedCode")
        }.getOrNull()
        val id = fresh?.let { org.json.JSONArray(it).optJSONObject(0)
            ?.optString("id")?.takeIf { it.isNotBlank() } }
        if (id != null) sourceIdCache[code] = id
        return id
    }

    private suspend fun get(path: String): String {
        val resp = com.lagradost.cloudstream3.app.get("$URL$path",
            headers = headers(), timeout = OBS_TIMEOUT_SECONDS)
        return resp.text
    }

    private suspend fun post(
        path: String, body: org.json.JSONObject,
        prefer: String? = null
    ) {
        com.lagradost.cloudstream3.app.post(
            "$URL$path",
            headers = headers(prefer),
            requestBody = body.toString().toRequestBody(
                "application/json".toMediaType()),
            timeout = OBS_TIMEOUT_SECONDS
        ).text
    }

    private suspend fun patch(path: String, body: org.json.JSONObject) {
        com.lagradost.cloudstream3.app.patch(
            "$URL$path",
            headers = headers(),
            requestBody = body.toString().toRequestBody(
                "application/json".toMediaType()),
            timeout = OBS_TIMEOUT_SECONDS
        ).text
    }

    private const val OBS_TIMEOUT_SECONDS = 4L
    private const val RUN_WAIT_TIMEOUT_MS = 5_000L
}