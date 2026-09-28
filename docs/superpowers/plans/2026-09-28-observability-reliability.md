# Observability Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Supabase observability layer able to tell success from HTTP rejection, record run-level error classification, and stop the provider registry from going permanently stale.

**Architecture:** Three independent defects in the same subsystem, all found by reading the code on 2026-09-28. Task 1 is the load-bearing one: every writer treats "no exception thrown" as "insert succeeded", so a rejected payload is reported as success and never retried. Task 2 and Task 3 are consequences of the same blind spot — data that is never written, and reference data that is never refreshed. All three are patches to existing functions; no new module, no new dependency.

**Tech Stack:** Kotlin, OkHttp via CloudStream's NiceHttp wrapper (`com.lagradost.cloudstream3.app.*`), PostgREST/Supabase, JUnit4 for unit tests, GitHub Actions for the only test run.

**Spec:** This document is the spec. There is no separate design doc; the evidence for each defect is quoted inline at the task it fixes.

## Global Constraints

- **No local Gradle.** `./gradlew` is forbidden in this environment. Every "run the test" step below means: commit, push to `origin` and `private`, then `gh run watch <id> --exit-status`. There is no local compile step and no local test run.
- **Unit tests only run in CI**, via `./gradlew :BaseProvider:testDebugUnitTest` inside `ci-cd.yml`. A test is "verified" only when a CI run is green.
- One logical change per commit. Commit messages start with `fix(`, `feat(`, `refactor(`, or `chore(`.
- Never edit files under `ProviderNama/`. Shared code lives in `BaseProvider/`.
- Preserve existing public signatures unless a task says otherwise. Callers outside the changed file must keep compiling.
- No new dependencies. Everything here uses OkHttp, org.json, and JUnit4, all already on the classpath.
- Kotlin string templates interpolate only the identifier after `$`. `"$d.field"` yields `toString(d) + ".field"`. Use `"$d" + d.field` or `"${d.field}"`. A scanner for the bad form lives in `OCE-skills/development/SKILL.md`.

## Review Focus

Five input classes or failure modes the plan implies that individual task tests are most likely to miss:

1. **PostgREST rejects a payload with a non-2xx status but a readable body** (`PGRST204` unknown column, `409` on conflict, `413` batch too large). Nothing throws; only `.code` reveals the failure. Task 1's tests must assert on status codes, not on exceptions.
2. **A batch of rows where only some rows are invalid.** PostgREST is all-or-nothing per statement, so the whole array fails and every row in it is lost. There is no unit test for this: it needs a live PostgREST, and the plan forbids depending on one. Task 1 Step 6 covers it with a terminal `Log.e` naming the lost row count, so the loss is at least reportable — verified in Step 8 against the live endpoint, not by an automated test.
3. **A run whose steps were all dropped** (queue overflow at `MAX_STEP_QUEUE`, or `awaitRunCreated` timing out). Task 2 must not invent an error type where there is no evidence of a failure.
4. **`plugin_version` absent because `OCE_VERSION` was not set at build time** (`SupabaseBakedConfig.PLUGIN_VERSION` blank). The fallback must not flip `pluginVersionSupported = false` for this reason, or one local build disables the column for the whole process lifetime.
5. **A provider whose domain changed while the app was not running.** No request path is involved; the DB row is simply wrong from the moment the new build ships. Task 3's test must use a config value that differs from the stored row.

---

## File Structure

- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/Logging.kt` — batch writer for the `logs` table. Owns `postBatch`, the `plugin_version` strip fallback, and the "insert ok" log line.
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/SupabaseObservability.kt` — writer for `scrape_runs` and `scrape_steps`. Owns `post`, `postArray`, `patch`, `postOrStrip`, `flushSteps`, and `resolveSourceId`.
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/core/ProviderScrapper.kt` — the only two call sites of `endRun` (lines 419 and 432).
- Create: `BaseProvider/src/test/kotlin/com/baseprovider/SupabaseWriteOutcomeTest.kt` — status-code classification logic (Task 1).
- Create: `BaseProvider/src/test/kotlin/com/baseprovider/DeriveRunErrorTypeTest.kt` — run-level error-type derivation (Task 2).
- Create: `BaseProvider/src/test/kotlin/com/baseprovider/SourceUrlSyncTest.kt` — registry drift comparison (Task 3).

Existing tests to keep green throughout: `SupabaseBakedConfigTest`, `SupabaseObservabilityTest`, `AdaptiveHeaderProbeRejectLabelTest`, `AndroidCompatApiTest`.

---

### Task 1: Make the Supabase writers detect HTTP rejection

**Files:**
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/Logging.kt` (`postBatch`, ~line 194; `flushSupabaseBatch`, ~line 164)
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/SupabaseObservability.kt` (`post` line 344, `postArray` line 449, `patch` line 357, `postOrStrip` line 125, `flushSteps` ~line 405)
- Test: `BaseProvider/src/test/kotlin/com/baseprovider/SupabaseWriteOutcomeTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `internal fun isWriteOk(code: Int): Boolean` in package `com.baseprovider.log`, used by Tasks 2 and 3 if they need to assert on write success. `internal fun deriveRunErrorType(steps: List<String?>): String?` is produced by Task 2, not this task.

**Background — the evidence.** `OCE/network/HttpClient.kt:98` already states the rule in a comment written by this project:

```kotlin
// app.get (NiceHttp) tidak throw pada status error — cek secara eksplisit.
if (r.code >= 400) {
```

`Logging.kt:194` violates that rule:

```kotlin
private suspend fun postBatch(rows: List<org.json.JSONObject>): Boolean =
    runCatching {
        ...
        com.lagradost.cloudstream3.app.post(...).text
        true                      // <-- unconditional
    }.getOrElse { e -> ...; false }
```

`SupabaseObservability.kt:344` and `:449` end in `.text` and discard the status; `postOrStrip` (`:128`) then judges success with `runCatching { post(path, body) }.isSuccess`, and its own KDoc records the faulty assumption: *"[post] mengembalikan Unit, jadi sukses judged lewat isSuccess."* `flushSteps` (`:427`) does the same. Consequence: any non-2xx is reported as success, the `plugin_version` strip fallback can never fire, and the line `"Supabase log insert ok: N rows"` prints for a batch that was entirely rejected.

- [ ] **Step 1: Write the failing test**

Create `BaseProvider/src/test/kotlin/com/baseprovider/SupabaseWriteOutcomeTest.kt`:

```kotlin
package com.baseprovider

import com.baseprovider.log.isWriteOk
import com.baseprovider.log.withPluginVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseWriteOutcomeTest {

    @Test
    fun `2xx dianggap sukses`() {
        assertTrue(isWriteOk(200))
        assertTrue(isWriteOk(201))
        assertTrue(isWriteOk(204))
        assertTrue(isWriteOk(299))
    }

    @Test
    fun `error PostgREST dianggap gagal walau body terbaca`() {
        // PGRST204 kolom tidak dikenal: tidak ada exception, tapi batch ditolak.
        assertFalse(isWriteOk(400))
        assertFalse(isWriteOk(401))
        assertFalse(isWriteOk(403))
        assertFalse(isWriteOk(404))
        assertFalse(isWriteOk(409))
        assertFalse(isWriteOk(413))
        assertFalse(isWriteOk(500))
        assertFalse(isWriteOk(503))
    }

    @Test
    fun `3xx tidak dianggap sukses`() {
        // Redirect lewat proxy: PostgREST tidak menulis apa pun.
        assertFalse(isWriteOk(301))
        assertFalse(isWriteOk(302))
    }

    @Test
    fun `PLUGIN_VERSION kosong tidak menambahkan kolom plugin_version`() {
        // Build lokal/CI tidak meng-set OCE_VERSION. Kalau payload tetap
        // membawa plugin_version kosong, PostgREST menolak seluruh batch dan
        // fallback sempat menyalakan diri karena alasan yang salah.
        val body = withPluginVersion(org.json.JSONObject().put("level", "FAIL"))
        assertFalse(body.has("plugin_version"))
    }
}
```

- [ ] **Step 2: Push and confirm the test fails**

```bash
git add BaseProvider/src/test/kotlin/com/baseprovider/SupabaseWriteOutcomeTest.kt
git commit -m "test(observability): kunci klasifikasi status jadi bisa diuji"
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: red with `Unresolved reference: isWriteOk` and, for the last test, `Cannot access 'withPluginVersion': it is private in 'SupabaseObservability'`. Both are the intended red — the behaviour under test does not exist yet.

- [ ] **Step 3: Add the classifier**

In `Logging.kt`, add near the other private helpers (before `postBatch`):

```kotlin
/**
 * PostgREST tidak melempar exception pada status error — balasannya tetap
 * 2xx-readable body dengan `.code` >= 400. Prinsip yang sama sudah dipakai di
 * `network/HttpClient.kt`: "NiceHttp tidak throw pada status error — cek
 * secara eksplisit". Tanpa cek ini, batch yang ditolak dilaporkan sukses dan
 * tidak pernah di-retry.
 */
internal fun isWriteOk(code: Int): Boolean = code in 200..299
```

In `SupabaseObservability.kt`, widen `withPluginVersion` so the blank-version case is testable:

```kotlin
internal fun withPluginVersion(body: org.json.JSONObject): org.json.JSONObject {
    if (!pluginVersionSupported) return body
    if (SupabaseBakedConfig.PLUGIN_VERSION.isBlank()) return body
    return body.put("plugin_version", SupabaseBakedConfig.PLUGIN_VERSION)
}
```

- [ ] **Step 4: Make `postBatch` honour the status**

Replace the body of `postBatch` in `Logging.kt`:

```kotlin
private suspend fun postBatch(rows: List<org.json.JSONObject>): Boolean {
    return try {
        val body = org.json.JSONArray().apply { rows.forEach { put(it) } }
        val response = com.lagradost.cloudstream3.app.post(
            "$SUPABASE_URL/rest/v1/logs",
            headers = mapOf(
                "apikey" to SUPABASE_ANON_KEY,
                "Authorization" to "Bearer $SUPABASE_ANON_KEY",
                "Content-Type" to "application/json",
                "Prefer" to "return=minimal"
            ),
            requestBody = body.toString().toRequestBody(
                "application/json".toMediaType())
        )
        if (isWriteOk(response.code)) true else {
            Log.e("OCE", "Supabase log insert ditolak HTTP ${response.code}: " +
                "batch ${rows.size} baris hilang")
            false
        }
    } catch (e: Exception) {
        Log.e("OCE", "Supabase log insert gagal: ${e.message}")
        false
    }
}
```

- [ ] **Step 5: Make `post`/`postArray`/`patch` return the status**

In `SupabaseObservability.kt`, change the three low-level writers so callers can see the code. `post` becomes:

```kotlin
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
```

`postArray` becomes:

```kotlin
private suspend fun postArray(
    path: String, body: org.json.JSONArray
): Int = com.lagradost.cloudstream3.app.post(
    "$URL$path",
    headers = headers(),
    requestBody = body.toString().toRequestBody(
        "application/json".toMediaType()),
    timeout = OBS_TIMEOUT_SECONDS
).code
```

`patch` becomes:

```kotlin
private suspend fun patch(path: String, body: org.json.JSONObject): Int =
    com.lagradost.cloudstream3.app.patch(
        "$URL$path",
        headers = headers(),
        requestBody = body.toString().toRequestBody(
            "application/json".toMediaType()),
        timeout = OBS_TIMEOUT_SECONDS
    ).code
```

- [ ] **Step 6: Fix `postOrStrip`, `flushSteps`, and the lost-batch path**

`postOrStrip` must judge on the code, and the KDoc that records the wrong assumption must go:

```kotlin
/**
 * POST dengan fallback: kalau ditolak & payload punya `plugin_version`, kirim
 * ulang sekali tanpa kolom itu (DB belum di-migrate), lalu stop mencoba
 * selama proses ini hidup. Return true bila akhirnya tersimpan.
 *
 * `post` mengembalikan HTTP status, bukan Unit — NiceHttp tidak melempar pada
 * status error, jadi `isSuccess` dulu berarti "selalu sukses".
 */
private suspend fun postOrStrip(
    path: String, body: org.json.JSONObject
): Boolean {
    if (attemptWrite(path) { isWriteOk(post(path, it)) }) return true
    if (!pluginVersionSupported || !body.has("plugin_version")) return false
    pluginVersionSupported = false
    body.remove("plugin_version")
    val ok = attemptWrite(path) { isWriteOk(post(path, body)) }
    if (ok) {
        Log.w("OCE", "Observability: $path ok tanpa plugin_version — " +
            "jalankan supabase/migrations/0005_logs_plugin_version.sql")
    }
    return ok
}

private suspend inline fun attemptWrite(
    path: String, write: () -> Int
): Boolean = try {
    write()
} catch (e: Exception) {
    Log.e("OCE", "Observability: $path gagal: ${e.message}")
    false
}
```

In `flushSteps`, replace `if (runCatching { postArray("/rest/v1/scrape_steps", steps) }.isSuccess) return` with:

```kotlin
val ok = try { isWriteOk(postArray("/rest/v1/scrape_steps", steps)) }
           catch (e: Exception) { Log.e("OCE", "step batch gagal: ${e.message}"); false }
if (ok) return
```

Then make the terminal case visible. After the existing `plugin_version` strip retry in `flushSteps`, add:

```kotlin
Log.e("OCE", "Observability: ${steps.length()} step hilang — " +
    "batch ditolak 2x (HTTP ditolak, cek key/RLS/network)")
```

The same terminal log belongs in `flushSupabaseBatch` in `Logging.kt`, after the stripped retry fails.

- [ ] **Step 7: Push and confirm the test passes and nothing else broke**

```bash
git add -A
git commit -m "fix(observability): periksa HTTP status, bukan cuma isSuccess

NiceHttp tidak melempar pada status error (sudah tertulis di
network/HttpClient.kt). Akibatnya postBatch selalu return true, fallback
plugin_version tidak pernah jalan, dan 'insert ok' tercetak untuk batch yang
ditolak seluruhnya."
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: green, and the log line still reports `187 tests completed` or more with `0 failed`.

- [ ] **Step 8: Verify against the live database that a rejection is now visible**

Push a deliberately bad row is not possible from the app, so verify the classifier's premise instead: confirm PostgREST does answer a non-2xx with a readable body rather than a connection error. With the anon key:

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST \
  "$SUPABASE_URL/rest/v1/logs" \
  -H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
  -H 'Content-Type: application/json' -H 'Prefer: return=minimal' \
  -d '[{"level":"SUCCESS","message":"plan verify","tidak_ada_kolom":1}]'
```

Expected: `HTTP 400` with a `PGRST204` body. That is the exact case the old code swallowed.

---

### Task 2: Populate `scrape_runs.error_type`

**Files:**
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/SupabaseObservability.kt` (add `deriveRunErrorType`; use it in the `endRun` PATCH body around line 244)
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/core/ProviderScrapper.kt` (lines 419 and 432)
- Test: `BaseProvider/src/test/kotlin/com/baseprovider/DeriveRunErrorTypeTest.kt`

**Interfaces:**
- Consumes: `isWriteOk(code: Int)` from Task 1 (no direct call; same package).
- Produces: `internal fun deriveRunErrorType(steps: List<String?>): String?` in package `com.baseprovider.log`.

**Background — the evidence.** `scrape_runs.error_type` was `0 / 1282` filled over three days and `0 / 2` after the truncate, while `scrape_steps.error_type` is `6 / 10` filled. The cause is structural, not intermittent: `endRun` accepts `errorType: String?` (`:215`) and both call sites in `ProviderScrapper.kt` — the cancellation path at `:419` and the normal path at `:432` — never pass it. The `deriveFromSteps` flag (default `true`) only lowers `status` via `deriveRunEndStatus`; it does not lower `error_type`. So the column cannot be filled by the current code at all. The information is not lost — steps carry it and join on `run_id` — but every run-level query has to re-derive it.

- [ ] **Step 1: Write the failing test**

Create `BaseProvider/src/test/kotlin/com/baseprovider/DeriveRunErrorTypeTest.kt`:

```kotlin
package com.baseprovider

import com.baseprovider.log.deriveRunErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeriveRunErrorTypeTest {

    @Test
    fun `run sukses tidak punya error type`() {
        assertNull(deriveRunErrorType(listOf(null, null)))
    }

    @Test
    fun `ambil error type dari step yang gagal`() {
        assertEquals("TIMEOUT", deriveRunErrorType(listOf(null, "TIMEOUT")))
    }

    @Test
    fun `lebih dari satu tipe pilih yang pertama non-null sesuai urutan step`() {
        // Urutan step = urutan kronologis, jadi kemunculan pertama = penyebab
        // pertama, bukan yang paling parah.
        assertEquals("NETWORK_FAILURE",
            deriveRunErrorType(listOf(null, "NETWORK_FAILURE", "TIMEOUT")))
    }

    @Test
    fun `semua step null tetap null`() {
        assertNull(deriveRunErrorType(emptyList()))
    }

    @Test
    fun `step null lalu error lalu null tetap ambil error`() {
        assertEquals("CONTENT_REMOVED",
            deriveRunErrorType(listOf(null, "CONTENT_REMOVED", null)))
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add BaseProvider/src/test/kotlin/com/baseprovider/DeriveRunErrorTypeTest.kt
git commit -m "test(observability): kunci penurunan error_type dari step"
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: red with `Unresolved reference: deriveRunErrorType`.

- [ ] **Step 3: Implement the derivation**

In `SupabaseObservability.kt`, next to `deriveRunEndStatus`:

```kotlin
/**
 * Turunkan `error_type` run dari step-nya: step pertama yang punya error type
 * adalah penyebab pertama, jadi itu yang dilaporkan. `null` kalau tidak ada
 * step yang gagal — run yang benar-benar sukses tidak boleh dapat error type.
 */
internal fun deriveRunErrorType(steps: List<String?>): String? =
    steps.firstOrNull { !it.isNullOrBlank() }
```

- [ ] **Step 4: Use it in the `endRun` PATCH**

`endRun` already keeps per-run tallies via `tallyStep`. `logStep` receives `errorType` as a parameter, so record the real signal directly rather than guessing one from `status`:

```kotlin
private val stepErrors =
    ConcurrentHashMap<String, MutableList<String?>>()

private fun tallyStepError(runId: String, errorType: String?) {
    stepErrors.computeIfAbsent(runId) { mutableListOf() }.add(errorType)
}
```

inside `logStep`, right after the existing `tallyStep(runId, kind, status)` call:

```kotlin
tallyStepError(runId, errorType)
```

Then in the `endRun` PATCH body, replace the current

```kotlin
errorType?.let { put("error_type", it) }
```

with

```kotlin
(errorType ?: deriveRunErrorType(stepErrors[runId].orEmpty()))
    ?.let { put("error_type", it) }
```

The `errorType` parameter of `endRun` is already in scope, so a caller-supplied value still wins. `stepErrors[runId]` is `MutableList<String?>?`, so `.orEmpty()` yields `List<String?>`, which is exactly what `deriveRunErrorType` takes.

Finally, in both `endRun` call sites in `ProviderScrapper.kt`, pass the type that is already known there. At `:419` the failure is a cancellation:

```kotlin
SupabaseObservability.endRun(
    runId,
    status = "failed",
    durationMs = System.currentTimeMillis() - startedAt,
    errorType = FailureType.CANCELLED.label,
    deriveFromSteps = false
)
```

At `:432` the value is intentionally left unset: when `result` is false and no step recorded an error, `deriveRunErrorType` returns `null`, which is correct — a run can fail without any step recording a type, and inventing one would be a lie in the data.

- [ ] **Step 5: Remove the tally when the run ends**

Inside `endRun`, after the PATCH is issued, drop the per-run entry so the map does not grow for the life of the process:

```kotlin
stepErrors.remove(runId)
```

- [ ] **Step 6: Push and confirm green**

```bash
git add -A
git commit -m "fix(observability): isi scrape_runs.error_type dari step

Kolomnya 0/1282 terisi selama 3 hari karena kedua pemanggil endRun tidak
pernah mengirim errorType, dan deriveFromSteps hanya menurunkan status.
Turunkan dari step pertama yang punya error type; run sukses tetap null."
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: green.

- [ ] **Step 7: Verify the column starts filling**

Wait for real traffic on the new build, then:

```sql
select count(*) as total, count(error_type) as terisi
from scrape_runs where plugin_version is not null;
```

Expected: `terisi > 0` once at least one run has a failing step. A run that succeeds end to end will legitimately show `error_type = null`, so check runs whose `status <> 'success'`.

---

### Task 3: Make the `sources` registry self-heal

**Files:**
- Modify: `BaseProvider/src/main/kotlin/com/baseprovider/log/SupabaseObservability.kt` (`resolveSourceId`, line 301)
- Test: `BaseProvider/src/test/kotlin/com/baseprovider/SourceUrlSyncTest.kt`

**Interfaces:**
- Consumes: `isWriteOk(code: Int)` from Task 1.
- Produces: `internal fun sourceUrlDrifted(stored: String?, configured: String): Boolean` in package `com.baseprovider.log`.

**Background — the evidence.** `resolveSourceId` does `get("/rest/v1/sources?select=id&code=eq.$code")` and returns the id as soon as a row exists. The `post(... on_conflict=code)` that carries `main_url` only runs when the row is **missing**, and it sends `prefer = resolution=ignore-duplicates`, so even a conflict would change nothing. The result is that `main_url` is written once and frozen forever. Measured on 2026-09-28, two of eight rows had drifted:

| code | config (source of truth) | DB `main_url` | HTTP |
|---|---|---|---|
| Anichin | `https://anichin.moe` | `https://anichin.cafe` | 000 (dead) |
| Dutamovie21 | `https://204.3.234.75` | `https://vikingsgab.com` | 000 (dead) |

Nothing user-facing breaks — the app builds URLs from the provider JSON config, not from this table — but the table is what per-domain analysis reads, and the dead `anichin.cafe` value already caused a wrong conclusion during this work. The rows were corrected by hand; without this task the next domain change repeats it.

- [ ] **Step 1: Write the failing test**

Create `BaseProvider/src/test/kotlin/com/baseprovider/SourceUrlSyncTest.kt`:

```kotlin
package com.baseprovider

import com.baseprovider.log.sourceUrlDrifted
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlSyncTest {

    @Test
    fun `domain berbeda dianggap drift`() {
        assertTrue(sourceUrlDrifted("https://anichin.cafe", "https://anichin.moe"))
        assertTrue(sourceUrlDrifted("https://vikingsgab.com", "https://204.3.234.75"))
    }

    @Test
    fun `domain sama dengan selisih slash akhir tidak dianggap drift`() {
        assertFalse(sourceUrlDrifted("https://anichin.moe", "https://anichin.moe/"))
        assertFalse(sourceUrlDrifted("https://anichin.moe/", "https://anichin.moe"))
    }

    @Test
    fun `huruf besar tidak dianggap drift`() {
        assertFalse(sourceUrlDrifted("https://Anichin.MOE", "https://anichin.moe"))
    }

    @Test
    fun `baris kosong tidak dianggap drift`() {
        // Config selalu punya mainUrl; DB kosong = belum diisi, bukan salah.
        assertFalse(sourceUrlDrifted(null, "https://anichin.moe"))
        assertFalse(sourceUrlDrifted("", "https://anichin.moe"))
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add BaseProvider/src/test/kotlin/com/baseprovider/SourceUrlSyncTest.kt
git commit -m "test(observability): kunci deteksi drift URL registry"
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: red with `Unresolved reference: sourceUrlDrifted`.

- [ ] **Step 3: Implement the comparison**

In `SupabaseObservability.kt`:

```kotlin
/**
 * `main_url` di tabel `sources` ditulis sekali saat baris dibuat lalu tidak
 * pernah disegarkan, jadi domain yang ganti tertinggal selamanya. Bandingkan
 * secara normalisasi: trailing slash dan huruf besar bukan drift.
 */
internal fun sourceUrlDrifted(stored: String?, configured: String): Boolean {
    val have = stored?.trim().orEmpty()
    if (have.isEmpty()) return false
    val norm = { s: String -> s.trim().trimEnd('/').lowercase() }
    return norm(have) != norm(configured)
}
```

- [ ] **Step 4: Refresh the row when it drifted**

In `resolveSourceId`, select `main_url` alongside `id` and PATCH the drift. Replace the `select=id&code=eq.$encodedCode` lookup's parsed result handling and add the refresh:

```kotlin
val existing = runCatching {
    get("/rest/v1/sources?select=id,main_url&code=eq.$encodedCode")
}.getOrNull()
if (existing != null && existing != "[]") {
    val row = org.json.JSONArray(existing).optJSONObject(0)
    val id = row?.optString("id")?.takeIf { it.isNotBlank() }
    if (id != null) {
        val stored = row?.optString("main_url")?.takeIf { it.isNotBlank() }
        if (sourceUrlDrifted(stored, mainUrl)) {
            refreshSourceUrl(encodedCode, mainUrl)
        }
        sourceIdCache[code] = id
        return id
    }
}
```

and add next to it:

```kotlin
/**
 * Selaraskan `main_url` dengan config. Dipanggil hanya saat berbeda, satu kali
 * per `code` per proses (cache mencegahnya berulang), jadi tidak ada write loop.
 * Kegagalan diamkan: `main_url` hanya metadata analisis, bukan jalur request.
 */
private suspend fun refreshSourceUrl(encodedCode: String, mainUrl: String) {
    runCatching {
        val body = org.json.JSONObject().put("main_url", mainUrl)
        patch("/rest/v1/sources?code=eq.$encodedCode", body)
    }.onFailure { e ->
        Log.w("OCE", "Observability: refresh main_url $encodedCode gagal: ${e.message}")
    }
}
```

Note `patch` now returns `Int` (Task 1 Step 5); ignore it deliberately, per the comment above.

- [ ] **Step 5: Push and confirm green**

```bash
git add -A
git commit -m "fix(observability): segarkan sources.main_url saat config berubah

resolveSourceId langsung return begitu baris ada, jadi main_url beku selamanya
meski domain provider ganti. Dua dari 8 baris sudah basi (anichin.cafe dan
vikingsgab.com, keduanya HTTP 000). Sekarang PATCH satu kali saat berbeda."
git push origin master && git push private master
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <id> --exit-status
```

Expected: green.

- [ ] **Step 6: Verify no write loop**

```sql
select code, main_url, updated_at from sources order by code;
```

Expected: every `main_url` matches the provider JSON config, and repeated runs do not advance `updated_at` — the cache means one refresh per process at most.

---

## Out of Scope

Found while investigating, deliberately not in this plan. Each needs its own decision.

- **No retention policy for `logs`.** It held 159,108 rows / 50 MB in three days (~16 MB/day). A free-tier Supabase project has a 500 MB database ceiling, so roughly 30 days of history exhausts it. Options: a scheduled GitHub Actions job that deletes rows older than N days, or partitioning. This is the one out-of-scope item that will eventually bite.
- **Migration files are not fully idempotent.** `0001_init.sql` uses `create table` without `if not exists`, and `0003_observability_logging.sql` uses `create policy` without dropping first (only `0004` does the drop-then-create dance). Harmless while the tracker table is accurate, but a partially-applied file that fails halfway will not re-run.
- **Dutamovie21 has a single working endpoint.** `mirrorUrls: ["https://cyber-junkie.com"]` answers `HTTP 522`; `mainUrl: https://204.3.234.75` answers `200`. If the IP goes down there is no fallback. A second live mirror is a config change, not a code change.
- **`scrape_steps` has no `plugin_version` index** while `logs` has two and `scrape_runs` has one. The table is small so this is not urgent, but the asymmetry is unintentional.
