package com.baseprovider.log

import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

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

    /** Config URL yang terakhir disinkronkan per code, untuk mendeteksi
     *  perubahan config saat cache ID masih ada. */
    private val sourceUrlCache = ConcurrentHashMap<String, String>()
    private val pendingRuns =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val failedRuns = java.util.Collections
        .newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val runLastTouched = ConcurrentHashMap<String, Long>()
    private val trackedRunLock = Any()

    /**
     * Statistik step per run, di-tally sinkron di jalur pemanggil (logStep)
     * dan dikonsumsi oleh endRun untuk menurunkan status watchability run.
     * Tanpa ini, run yang "success" padahal SEMUA link gagal diekstrak tidak
     * terlihat — signal sebenarnya hanya siluman di scrape_steps.
     */
    private val runStepCounts =
        ConcurrentHashMap<String, MutableMap<String, Int>>()

    /** Hasil resolusi end-of-run: status final + ringkasan watchability. */
    internal data class RunResolution(
        val status: String, val summary: String?
    )

    /**
     * Turunkan status akhir run dari statistik step (pure — bisa diuji).
     * Bila tak ada satupun step yang tercatat, status caller dipertahankan.
     * Bila step ada: semua berhasil -> success; tidak ada yang berhasil ->
     * failed (dengan ringkasan); sebagian -> partial (dengan ringkasan).
     * Ringkasan memakai kolom error_message yang sudah ada (tidak butuh
     * schema baru), sehingga rincian "x/y link menghasilkan video" bisa
     * langsung diquery per run.
     */
    internal fun deriveRunEndStatus(
        collectCount: Int,
        extractSuccess: Int,
        extractFailed: Int,
        extractTimeout: Int,
        callerStatus: String
    ): RunResolution {
        val attempted = extractSuccess + extractFailed + extractTimeout
        if (attempted == 0) return RunResolution(callerStatus, null)
        val summary =
            "COLLECT=$collectCount EXTRACT=$attempted (ok=$extractSuccess " +
                "fail=$extractFailed timeout=$extractTimeout)"
        return when {
            extractSuccess > 0 && extractFailed == 0 &&
                extractTimeout == 0 -> RunResolution("success", null)
            extractSuccess == 0 -> RunResolution("failed", summary)
            else -> RunResolution("partial", summary)
        }
    }

    /**
     * Turunkan `error_type` run dari step-nya (pure — bisa diuji). Step pertama
     * yang punya error type adalah penyebab pertama, jadi itu yang dilaporkan.
     * `null` kalau tidak ada step yang gagal: run yang benar-benar sukses tidak
     * boleh dapat error type, dan string kosong dianggap "tidak ada".
     */
    internal fun deriveRunErrorType(steps: List<String?>): String? =
        steps.firstOrNull { !it.isNullOrBlank() }

    private fun tallyStep(runId: String, kind: String, status: String) {
        touchRun(runId)
        val m = runStepCounts.computeIfAbsent(runId) {
            ConcurrentHashMap()
        }
        val k = "$kind:$status"
        m[k] = (m[k] ?: 0) + 1
    }

    /**
     * Error type per step, urut kronologis. Dipakai endRun untuk mengisi
     * `scrape_runs.error_type` yang tadinya tidak pernah terisi.
     */
    private val runStepErrors =
        ConcurrentHashMap<String, MutableList<String?>>()

    private fun tallyStepError(runId: String, errorType: String?) {
        runStepErrors.computeIfAbsent(runId) { mutableListOf() }
            .add(errorType)
    }

    /**
     * Batas entry run yang ditahan per proses.
     *
     * Map per-run idealnya dibuang oleh endRun, tapi itu tidak bisa
     * diandalkan: pada cancellation endRun dipanggil dari cabang yang
     * melempar ulang, dan kalau `awaitRunCreated` menyerah, entry tidak pernah
     * dibuang. `failedRuns` bahkan tidak pernah dibuang sama sekali. Tanpa
     * batas, proses yang hidup lama menahan entry terus sampai OOM.
     *
     * Evict berbasis run yang terlama tidak disentuh, jadi run yang sedang
     * diproses tidak kehilangan statistik saat triggernya.
     */
    private fun touchRun(runId: String) {
        runLastTouched[runId] = System.currentTimeMillis()
        if (runLastTouched.size <= MAX_TRACKED_RUNS) return
        synchronized(trackedRunLock) {
            val victims = evictVictims(
                runLastTouched, MAX_TRACKED_RUNS, EVICT_TRACKED_RUNS)
            victims.forEach { forgetRun(it) }
        }
    }

    /** Buang semua jejak satu run. Tidak menyentuh [pendingRuns]. */
    private fun forgetRun(runId: String) {
        runStepCounts.remove(runId)
        runStepErrors.remove(runId)
        failedRuns.remove(runId)
        runLastTouched.remove(runId)
    }

    /**
     * Pilih run mana yang harus dibuang saat ukuran melewati [cap]: yang
     * terlama tidak disentuh, sebanyak [count] (pure — bisa diuji).
     */
    internal fun evictVictims(
        lastTouched: Map<String, Long>, cap: Int, count: Int
    ): List<String> {
        if (lastTouched.size <= cap || count <= 0) return emptyList()
        return lastTouched.entries.sortedBy { it.value }
            .take(count.coerceAtMost(lastTouched.size - cap))
            .map { it.key }
    }

    private fun enabled(): Boolean = URL.isNotBlank() && ANON_KEY.isNotBlank()

    /**
     * Sama seperti [com.baseprovider.log.ProviderLog]: `plugin_version` hanya
     * ada setelah migration 0005, dan PostgREST menolak seluruh payload kalau
     * kolomnya belum ada — yang di sini berarti `beginRun` gagal → seluruh
     * run tracking mati. Coba sekali, lalu kirim ulang tanpa kolom itu dan
     * tandai supaya request berikutnya langsung skip.
     */
    @Volatile private var pluginVersionSupported = true

    /** Tempel plugin_version ke payload, atau kembalikan body apa adanya. */
    internal fun withPluginVersion(
        body: org.json.JSONObject,
        version: String = SupabaseBakedConfig.PLUGIN_VERSION
    ): org.json.JSONObject {
        if (!pluginVersionSupported) return body
        if (version.isBlank()) return body
        return body.put("plugin_version", version)
    }

    /**
     * POST dengan fallback: kalau ditolak & payload punya `plugin_version`, kirim
     * ulang sekali tanpa kolom itu (DB belum di-migrate), lalu stop mencoba
     * selama proses ini hidup. Return true bila akhirnya tersimpan.
     *
     * [post] mengembalikan HTTP status, bukan Unit — NiceHttp tidak melempar pada
     * status error, jadi `isSuccess` lama berarti "selalu sukses".
     */
    private suspend fun postOrStrip(
        path: String, body: org.json.JSONObject
    ): Boolean {
        if (attemptWrite(path) { post(path, body) }) return true
        if (!pluginVersionSupported || !body.has("plugin_version")) return false
        pluginVersionSupported = false
        body.remove("plugin_version")
        val ok = attemptWrite(path) { post(path, body) }
        if (ok) {
            Log.w("OCE", "Observability: $path ok tanpa plugin_version — " +
                "jalankan supabase/migrations/0005_logs_plugin_version.sql")
        }
        return ok
    }

    private suspend fun attemptWrite(
        path: String, write: suspend () -> Int
    ): Boolean = try {
        isHttpOk(write())
    } catch (e: Exception) {
        Log.e("OCE", "Observability: $path gagal: ${e.message}")
        false
    }

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
                    val body = withPluginVersion(
                        org.json.JSONObject().apply {
                            put("id", runId)
                            put("source_id", sourceId)
                            put("context", context)
                            put("triggered_by", triggeredBy)
                            put("start_url", startUrl)
                            put("status", "running")
                        })
                    // false = gagal (dan retry tanpa kolom juga gagal),
                    // supaya step-nya tidak dikirim & FK tidak violated.
                    postOrStrip("/rest/v1/scrape_runs", body)
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
        // NOTE: timeout != run gagal. Run bisa saja baru dibuat sesudahnya
        // (POST lambat saat batch besar). JANGAN masukkan ke failedRuns di
        // sini, agar endRun/logStep yang dipanggil ulang (retry) masih bisa
        // menunggu dan berhasil menyelesaikan run row.
        return ok
    }

    /** Update run lifecycle: status/returned_early/durasi/error terminal. */
    fun endRun(
        runId: String?, status: String, returnedEarly: Boolean = false,
        durationMs: Long? = null, errorType: String? = null,
        errorMessage: String? = null, deriveFromSteps: Boolean = true
    ) {
        if (!enabled() || runId.isNullOrBlank()) return
        scope.launch {
            // Retry PATCH (bounded) supaya run row tidak pernah "running"
            // selamanya. Run row dibuat di background (beginRun); jika belum
            // siap saat timeout pertama, tunggu ulang, lalu patch.
            var created = awaitRunCreated(runId)
            var attempt = 0
            while (!created && attempt < END_RUN_MAX_RETRIES) {
                delay(END_RUN_RETRY_DELAY_MS)
                created = awaitRunCreated(runId)
                attempt++
            }
            // Row-nya tidak pernah ada, jadi tidak ada yang bisa di-patch. Entry
            // lokal tetap harus dibuang, kalau tidak ia tertahan sampai proses
            // berikutnya — beginRun sudah menyerah, jadi tidak akan diloq.
            if (!created) {
                forgetRun(runId)
                Log.w("OCE", "Observability: endRun menyerah untuk $runId — " +
                    "run row tidak pernah dibuat")
                return@launch
            }
            val st: Map<String, Int> = runStepCounts[runId] ?: emptyMap()
            val stepErrors = runStepErrors[runId].orEmpty()
            val resolution = if (deriveFromSteps) deriveRunEndStatus(
                collectCount = st["COLLECT:success"] ?: 0,
                extractSuccess = st["EXTRACT:success"] ?: 0,
                extractFailed = st["EXTRACT:failed"] ?: 0,
                extractTimeout = st["EXTRACT:timeout"] ?: 0,
                callerStatus = status
            ) else RunResolution(status, null)
            val body = org.json.JSONObject().apply {
                put("status", resolution.status)
                put("returned_early", returnedEarly)
                durationMs?.let { put("duration_ms", it.toInt()) }
                (errorType ?: deriveRunErrorType(stepErrors))
                    ?.let { put("error_type", it) }
                (resolution.summary ?: errorMessage)?.let {
                    put("error_message", it)
                }
                put("finished_at", java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()))
            }
            runCatching {
                val code = patch("/rest/v1/scrape_runs?id=eq.$runId", body)
                if (!isHttpOk(code)) {
                    Log.e("OCE", "Observability: endRun ditolak HTTP $code — " +
                        "run $runId tetap running di DB")
                }
            }.onFailure { e ->
                Log.w("OCE", "Observability: endRun failed: ${e.message}")
            }
            forgetRun(runId)
        }
        // P3: endRun = titik akhir run → flush sisa step segera, jangan
        // menunggu interval flusher berikutnya.
        flushWake.trySend(Unit)
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
        tallyStep(runId, kind, status)
        tallyStepError(runId, errorType)
        val body = withPluginVersion(org.json.JSONObject().apply {
            put("run_id", runId)
            put("kind", kind)
            put("status", status)
            linkUrl?.let { put("link_url", it) }
            extractorChain?.let { put("extractor_chain", it) }
            durationMs?.let { put("duration_ms", it.toInt()) }
            linksFound?.let { put("links_found", it) }
            errorType?.let { put("error_type", it) }
        })
        enqueueStep(runId, body)
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
    /**
     * Apakah `sources.main_url` di DB sudah menyimpang dari config JSON?
     *
     * Config JSON adalah sumber kebenaran; DB hanya cache metadata untuk
     * analitik. Tanpa sinkronisasi, analitik ikut analyzing URL mati — itulah
     * yang membuat anichin.cafe dan vikingsgab.com bertahan berbulan-bulan.
     *
     * Yang dianggap TIDAK drift: spasi, huruf besar, trailing slash, port
     * default (:80 http / :443 https). Yang dianggap drift: host, skema, path,
     * atau port lain. `dbMainUrl` kosong bukan drift — kasus itu ditangani
     * jalur insert, dan `configMainUrl` kosong tidak ada yang bisa disinkronkan.
     */
    internal fun sourceUrlDrifted(dbMainUrl: String?, configMainUrl: String): Boolean {
        if (configMainUrl.isBlank()) return false
        if (dbMainUrl.isNullOrBlank()) return false
        return normalizeSourceUrl(dbMainUrl) != normalizeSourceUrl(configMainUrl)
    }

    private fun normalizeSourceUrl(raw: String): String {
        val s = raw.trim().lowercase()
        val scheme = when {
            s.startsWith("https://") -> "https"
            s.startsWith("http://") -> "http"
            else -> ""
        }
        val rest = if (scheme.isEmpty()) s else s.removePrefix("$scheme://")
        val slash = rest.indexOf('/')
        val hostPort = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        val host = when {
            hostPort.startsWith("[") -> hostPort
            scheme == "https" && hostPort.endsWith(":443") ->
                hostPort.dropLast(4)
            scheme == "http" && hostPort.endsWith(":80") ->
                hostPort.dropLast(3)
            else -> hostPort
        }
        return "$scheme://$host$path".trimEnd('/')
    }

    /**
     * ID row `sources` untuk [code], atau null kalau tidak bisa dipastikan.
     *
     * `main_url` ikut diambil supaya drift URL mati di DB bisa diperbaiki di
     * tempat. Cache ID tidak langsung dikembalikan: kalau config JSON berubah
     * sejak lookup terakhir, row DB masih menyimpan URL lama dan harus dicek
     * ulang. Config yang tidak berubah tetap dilayani dari cache, jadi tidak
     * ada round-trip tambahan pada jalur panas.
     */
    private suspend fun resolveSourceId(
        code: String, name: String, mainUrl: String
    ): String? {
        sourceIdCache[code]?.let { cachedId ->
            if (!sourceUrlDrifted(sourceUrlCache[code], mainUrl)) return cachedId
        }
        val encodedCode = java.net.URLEncoder.encode(code, "UTF-8")
            .replace("+", "%20")

        val existing = runCatching {
            get("/rest/v1/sources?select=id,main_url&code=eq.$encodedCode")
        }.getOrNull()
        if (existing != null && existing != "[]") {
            val row = org.json.JSONArray(existing).optJSONObject(0)
            val id = row?.optString("id")?.takeIf { it.isNotBlank() }
            if (id != null) {
                if (sourceUrlDrifted(row.optString("main_url"), mainUrl)) {
                    refreshSourceUrl(id, mainUrl)
                }
                sourceIdCache[code] = id
                sourceUrlCache[code] = mainUrl
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
        if (id != null) {
            sourceIdCache[code] = id
            sourceUrlCache[code] = mainUrl
        }
        return id
    }

    /**
     * Tulis ulang `sources.main_url` ke nilai config terbaru. Status PATCH
     * wajib diperiksa: NiceHttp tidak melempar pada 4xx/5xx, jadi penolakan
     * PostgREST akan terlihat "sukses" dan DB diam-diam tetap menyimpan URL
     * mati — persis masalah yang diperbaiki tugas ini.
     */
    private suspend fun refreshSourceUrl(id: String, mainUrl: String) {
        val code = runCatching {
            patch("/rest/v1/sources?id=eq.$id",
                org.json.JSONObject().put("main_url", mainUrl))
        }.getOrNull()
        when {
            code == null ->
                Log.w("OCE", "Observability: sync main_url $id gagal (exception)")
            isHttpOk(code) ->
                Log.w("OCE", "Observability: sources $id main_url disinkronkan " +
                    "ke $mainUrl")
            else ->
                Log.w("OCE", "Observability: sources $id main_url ditolak " +
                    "HTTP $code — DB masih menyimpan URL lama")
        }
    }

    /**
     * Baca JSON dari PostgREST. `null` kalau statusnya bukan 2xx.
     *
     * NiceHttp tidak melempar pada status error, dan PostgREST menjawab 4xx/5xx
     * dengan body JSON yang tetap terbaca — termasuk pesan error. Tanpa cek
     * status, `JSONArray(body)` akan dipakai seolah-olah itu hasil query, dan
     * caller bisa salah mengambil kolom dari objek error.
     */
    private suspend fun get(path: String): String? {
        val resp = com.lagradost.cloudstream3.app.get("$URL$path",
            headers = headers(), timeout = OBS_TIMEOUT_SECONDS)
        if (!isHttpOk(resp.code)) {
            Log.w("OCE", "Observability: GET $path -> HTTP ${resp.code}")
            return null
        }
        return resp.text
    }

    private suspend fun post(
        path: String, body: org.json.JSONObject,
        prefer: String? = null
    ): Int = com.lagradost.cloudstream3.app.post(
        "$URL$path",
        headers = headers(prefer),
        requestBody = body.toString().toRequestBody(
            "application/json".toMediaType()),
        timeout = OBS_TIMEOUT_SECONDS
    ).code

    private suspend fun patch(path: String, body: org.json.JSONObject): Int =
        com.lagradost.cloudstream3.app.patch(
            "$URL$path",
            headers = headers(),
            requestBody = body.toString().toRequestBody(
                "application/json".toMediaType()),
            timeout = OBS_TIMEOUT_SECONDS
        ).code

    // ── P3: batch insert scrape_steps ─────────────────────────────────────
    // Step di-queue in-memory lalu di-flush sebagai SATU bulk request (JSON
    // array) agar tidak ada 1 request HTTP per step (ratusan POST per run).
    // Satu flusher coroutine; flush dipicu oleh interval ATAU antrian penuh
    // ATAU endRun. Queue bounded — bila melampaui cap, step tertua di-drop
    // (degradasi observability, bukan block).

    private val stepQueue =
        ConcurrentLinkedQueue<Pair<String, org.json.JSONObject>>()
    private val flushWake = Channel<Unit>(Channel.CONFLATED)
    private val flusherJob = AtomicReference<Job?>(null)

    private fun enqueueStep(runId: String, body: org.json.JSONObject) {
        while (stepQueue.size > MAX_STEP_QUEUE) stepQueue.poll()
        stepQueue.offer(runId to body)
        ensureFlusher()
        if (stepQueue.size >= STEP_BATCH_SIZE) flushWake.trySend(Unit)
    }

    private fun ensureFlusher() {
        if (flusherJob.get() != null) return
        val job = scope.launch {
            while (isActive) {
                runCatching {
                    select<Unit> {
                        flushWake.onReceive { Unit }
                        onTimeout(STEP_FLUSH_INTERVAL_MS) { Unit }
                    }
                    flushSteps()
                }.onFailure { e ->
                    Log.w("OCE", "Observability: step flush failed: ${e.message}")
                }
            }
        }
        if (!flusherJob.compareAndSet(null, job)) job.cancel()
    }

    private suspend fun flushSteps() {
        val batch = mutableListOf<Pair<String, org.json.JSONObject>>()
        var drained = 0
        while (drained < STEP_BATCH_SIZE) {
            stepQueue.poll()?.let { batch.add(it) } ?: break
            drained++
        }
        if (batch.isEmpty()) return

        // Group per run, tunggu (bounded) run row dibuat, drop step milik run
        // yang gagal dibuat (hindari FK violation — degradasi, sama seperti
        // perilaku single-insert sebelumnya).
        val byRun = batch.groupBy { it.first }
        val steps = org.json.JSONArray()
        for ((runId, entries) in byRun) {
            if (!awaitRunCreated(runId)) continue
            entries.forEach { (_, body) -> steps.put(body) }
        }
        if (steps.length() == 0) return
        if (attemptWrite("scrape_steps") { postArray("/rest/v1/scrape_steps", steps) })
            return
        // Sama seperti postOrStrip: kolom plugin_version belum ada di DB
        // (migration 0005). Tanpa retry ini, SEMUA step hilang — bukan cuma
        // kolom versinya — karena PostgREST menolak seluruh array.
        if (!pluginVersionSupported) {
            Log.w("OCE", "Observability: batch logStep gagal "
                + "(plugin_version tidak didukung DB)")
            return
        }
        pluginVersionSupported = false
        val stripped = org.json.JSONArray()
        for (i in 0 until steps.length()) {
            stripped.put((steps.get(i) as org.json.JSONObject)
                .remove("plugin_version"))
        }
        if (attemptWrite("scrape_steps") { postArray("/rest/v1/scrape_steps", stripped) }) {
            Log.w("OCE", "Observability: step ok tanpa plugin_version — "
                + "jalankan supabase/migrations/0005_logs_plugin_version.sql")
        } else {
            Log.e("OCE", "Observability: ${steps.length()} step hilang — "
                + "batch ditolak 2x (periksa key, RLS, atau jaringan)")
        }
    }

    private suspend fun postArray(
        path: String, body: org.json.JSONArray
    ): Int = com.lagradost.cloudstream3.app.post(
        "$URL$path",
        headers = headers(),
        requestBody = body.toString().toRequestBody(
            "application/json".toMediaType()),
        timeout = OBS_TIMEOUT_SECONDS
    ).code

    private const val OBS_TIMEOUT_SECONDS = 4L
    private const val RUN_WAIT_TIMEOUT_MS = 5_000L
    private const val END_RUN_MAX_RETRIES = 4
    private const val END_RUN_RETRY_DELAY_MS = 2_000L
    private const val STEP_BATCH_SIZE = 50
    private const val STEP_FLUSH_INTERVAL_MS = 3_000L
    private const val MAX_STEP_QUEUE = 200
    private const val MAX_TRACKED_RUNS = 128
    private const val EVICT_TRACKED_RUNS = 32
}