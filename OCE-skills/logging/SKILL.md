---
name: oce-logging
description: OCE Logging — Supabase observability, FailureType classification, log querying
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: logging
---

# OCE Logging

## Purpose

Observability OCE: pipeline logging ke Supabase, klasifikasi `FailureType`, dan cara query log produksi untuk debugging.

## When to Use

- Debugging error yang dilaporkan user
- Verifikasi apakah fix benar-benar bekerja di produksi
- Health check provider/extractor
- Menambah logging ke kode baru

## When NOT to Use

- Syntax/compile error (→ `development`)
- Verifikasi selector (→ `selector-checker`)

## Aturan Golden: Hanya Log Terbaru

Log lama **tidak** dipakai untuk menyimpulkan bug masih ada. Aturan ini mencegah sebagian besar kesimpulan salah:

1. Tentukan waktu deploy/CI hijau dari fix terakhir
2. Query **sejak** waktu itu
3. `created_at` yang muncul = bug aktif **atau** user masih di APK lama
4. Bug lama yang punya signature identik tapi sudah di-fix → **abaikan**

**CI hijau ≠ user sudah update.** Beta untuk orang berbeda released di waktu berbeda. Tanpa `plugin_version` di DB, log newest pun bisa berasal dari build lama. Verifikasi adoption dulu sebelum menyimpulkan fix gagal.

## Log Architecture

```
logSuccess / logFail / logError / logCritical
  → ProviderLog.log()
    → if (level != DEBUG) sendToSupabase()   // Logging.kt:101
      → batch buffer: 10 baris / flush tiap 2 detik
      → POST /rest/v1/logs
logDebug() → Logcat saja. TIDAK PERNAH masuk Supabase.
```

Telegram sudah dihapus. Jangan menambahkannya kembali.

`logSuccess` **juga** di-upload (penanda runtime/telemetri). Karena itu pesan high-frequency yang isinya konstan harus `logDebug` — `buildList()` pernah menyumbang ~45% volume Supabase hanya karena `logSuccess`.

## Supabase Tables

### `logs`
| Column | Type | Catatan |
|--------|------|---------|
| run_id | text | join ke `scrape_runs.id` |
| level | text | `SUCCESS`, `FAIL`, `ERROR`, `CRITICAL` (tanpa `DEBUG`) |
| tag | text | Nama provider/extractor |
| message | text | Deskripsi error |
| method | text | Nama fungsi |
| failure_type | text | Label `FailureType` (lihat tabel) |
| host | text | Host website |
| url | text | Request URL |
| selectors | text | Selector yang dipakai |
| traceback | text | Stack trace JSON |
| stage | text | `SCRAPE`, `SEARCH`, `COLLECT`, `EXTRACT`, `SELECT`, `PROBE`, `VERIFY` |
| extractor | text | Domain extractor (lihat "Atribusi Versi") |
| plugin_version | text | versionCode plugin = epoch menit |
| attempt | int | Nomor retry |
| duration_ms | int | Durasi |
| created_at | timestamptz | Waktu |

> `stage` = **fase kerja**, bukan nama provider. Urutan: `SCRAPE` (load halaman)
> → `SEARCH` (cari), `COLLECT` (kumpulkan link dari halaman episode),
> `SELECT` (pilih & playlist), `EXTRACT` (ambil URL video), `PROBE` (cek
> header/3002), `VERIFY` (validasi link). `NULL` = call site lupa mengisinya —
> cek `grep -rn 'stage = "' BaseProvider/` untuk daftar call site yang sudah
> benar.

### `scrape_runs`
`id` (uuid), `source_id` (**UUID sumber data, bukan provider slug**), `series_id`, `episode_id`, `context` (`SCRAPE`/`LOAD`/`SEARCH`), `triggered_by`, `start_url`, `status` (`success`/`failed`/`partial`), `returned_early`, `started_at`, `finished_at`, `duration_ms`, `error_type`, `error_message`, `plugin_version`.

> Untuk tahu provider mana, join `scrape_runs.start_url` atau `logs.tag` — `source_id` bukan slug.

### `scrape_steps`
`id` (uuid), `run_id`, `kind` (`COLLECT`/`EXTRACT`), `link_url`, `extractor_chain`, `status` (`success`/`failed`/`timeout`), `duration_ms`, `links_found`, `error_type`, `created_at`, `plugin_version`.

## Atribusi Versi: "fix tidak berefek" atau "user belum update?"

`plugin_version` = versionCode plugin = `OCE_VERSION` = **epoch menit** (mis. `29843214`),
identik dengan versionCode di `ci-cd.yml` (beta) dan `release.yml` (stable). Ditempel
ke `SupabaseBakedConfig.PLUGIN_VERSION` oleh `generateSupabaseConfig`, lalu dikirim
ke ketiga tabel di atas. Kolomnya **NULL** untuk log dari build lama (sebelum 83a6d8a).

```sql
-- Adoption: build mana yang aktif, berapa run, kapan terakhir terlihat?
select plugin_version, count(*) as runs, max(started_at) as last_seen
  from scrape_runs where plugin_version is not null
 group by 1 order by 3 desc;

-- Error milik satu build tertentu
select created_at, level, tag, failure_type, message
  from logs where plugin_version = '29843214' and level <> 'SUCCESS'
 order by created_at desc limit 30;
```

**Selalu cross-check ini sebelum menyimpulkan "fix saya tidak bekerja".** Kasus nyata
(2026-09-28): fix sudah hijau di CI, tapi signature log-nya masih muncul di produksi
3,5 jam kemudian — penyebabnya tag release tertinggal 59 commit, bukan fix-nya salah
(konteksnya di `build-deploy`). Tanpa `plugin_version` itu baru ketahuan lewat
investigasi manual.

> Auto-detect project ref via `sb.py` sudah **mati**: `SupabaseBakedConfig.kt`
> di-check-in dengan string kosong (nilainya secret CI), jadi tidak ada
> `*.supabase.co` yang bisa ditemukan → `IndexError`. Pakai ref eksplisit
> `cjjopuwhpcuoaoifhcfj`, atau set `SB_REF` di environment.

### Migration & degradasi (penting)

Skema: `supabase/migrations/0005_logs_plugin_version.sql`. Cara apply: workflow
`apply-migrations.yml` (bukan paste manual) — detail & aturan secret di `build-deploy`.

Karena **PostgREST menolak seluruh payload kalau satu kolom tidak ada** — itu berarti
observability buta total, bukan kehilangan satu field — plugin punya fallback: kalau
post gagal dan payload punya `plugin_version`, kirim ulang sekali tanpa kolom itu,
lalu stop mencoba selama proses hidup. Gejalanya di logcat: `ok tanpa plugin_version`.
Artinya **DDL-nya belum di-apply**, bukan log-nya rusak.

## FailureType Classification

```kotlin
enum class FailureType(val label: String) {
    SUCCESS("SUCCESS"),              // Tidak ada error
    UNKNOWN("N/A"),                  // Fallback: log tanpa type=
    SELECTOR_FAILURE("SELECTOR"),    // Selector tidak match
    EXTRACTOR_FAILURE("EXTRACTOR"),  // Extractor gagal
    SHORTLINK_FAILURE("SHORTLINK"),  // short.icu dll
    NETWORK_FAILURE("NETWORK"),      // HTTP/network error
    CLOUDFLARE_FAILURE("CLOUDFLARE"),// Cloudflare challenge
    EMPTY_RESPONSE("EMPTY"),         // Server return 0 bytes
    INVALID_IFRAME("IFRAME"),        // Iframe tidak bisa di-resolve
    METADATA_FAILURE("METADATA"),    // Gagal parse metadata/poster
    CANCELLED("CANCELLED"),          // Job dibatalkan
    HTTP_FAILURE("HTTP"),            // Non-2xx/3xx dari probe
    INVALID_URL("URL"),              // URL kosong / master malformed
    CONTENT_REMOVED("REMOVED"),      // 404: konten dihapus upstream
    TIMEOUT("TIMEOUT")
}
```

- **`N/A` = `UNKNOWN`** — muncul saat `log()` tidak menerima `type =`. Bukan SQL NULL, bukan nilai khusus. Selalu isi `type`, `method`, `stage`, `extractor` saat logging failure.
- **`REMOVED` = `CONTENT_REMOVED`** — konten hilang di upstream (404), **bukan** bug OCE. Jangan Contact User dengan ini.
- **`extractor` = domain, bukan nama extractor.** DeepScan/global fallback isi
  `urlDomain` (mis. `fembed`), bukan `callChain` default `"-"`. NULL = call site
  lupa mengisinya.
- **Error sebelum extractor jalan → `extractor` NULL itu benar.** Contoh: iframe
  error di `FallbackPipeline` → `stage="COLLECT"`, `extractor` NULL. Jangan diisi
  karangan; yang penting `stage` dan `message` akurat.
- **Jangan laporkan "X% kolom NULL" tanpa segmentasi dulu.** Angka agregat menyesatkan
  karena baris `SUCCESS` sering memang tidak punya `stage`/`extractor` (mis. `buildList`
  yang DEBUG-only, atau `Search ... p1: N hasil`). Contoh nyata: "80% `stage` NULL"
  ternyata 770 dari 797 barisnya `SUCCESS`, dan sebagian besar justru `buildList`/
  `Search` yang memang by-design kosong di build lama — bukan evidence defect.
  Selalu pecah menurut `level` dan `message` dulu, baru putuskan apakah itu gap.
- **Sepadan dengan `DEBUG` yang tidak masuk Supabase**: `buildList` masih `SUCCESS`
  di produksi = masih ada user di build lama, karena `c4b9579` menurunkannya ke
  `DEBUG`. `traceback` terisi `0` = belum ada build yang membawa `4f8e17f`. Ini
  marker adopsi gratis, bahkan sebelum `plugin_version` ada.

## Log Functions

```kotlin
logDebug(tag, message)                              // logcat saja
logSuccess(tag, message, url?, method?, ...)        // → Supabase
logFail(tag, message, url?, method?, type?, stage?, extractor?, ...)
logError(tag, message, error?, ...)                 // → Supabase
logCritical(tag, message, error?, ...)              // → Supabase

// Common params: selectors, stage, extractor, attempt, durationMs, runId
```

Contoh lengkap:
```kotlin
logFail("Anichin", "selector tidak match",
    url = url, method = "loadLinks",
    type = FailureType.SELECTOR_FAILURE, stage = "SCRAPE")
```

## Querying Logs

### PostgREST — primary (data kecil-menengah)

```bash
ANON=$(grep -oP 'export\s+SB_ANON=\K\S+' ~/.bashrc | head -1)
REF=$(grep -rhoP 'https://\K[a-z0-9]+(?=\.supabase\.co)' --include='*.kt' . | sort -u | head -1)

curl -s "https://$REF.supabase.co/rest/v1/logs?select=created_at,level,tag,message,failure_type,extractor,host,stage&level=neq.SUCCESS&order=created_at.desc&limit=50" \
  -H "apikey: $ANON" -H "Authorization: Bearer $ANON"
```

Helper Python opsional (bisa hilang — path di `/tmp`): `import sb; sb.getraw("logs?...")` atau `sb.get("logs", {...})` dengan `getraw(qs)` / `get(table, params)` ke `/rest/v1/`. Kalau tidak ada, pakai curl di atas.

### PostgREST syntax yang sering salah

| Filter | Benar | Salah (diabaikan diam-diam) |
|--------|-------|------------------------------|
| Timestamp | `created_at=gte.2026-09-28T06:00:00Z` | `created_at.gte.2026-09-28T06:00:00Z` |
| Level | `level=eq.FAIL` | `level=eq.FAIL,level=eq.ERROR` (AND, bukan OR) |
| Wildcard | `host=like.*acefile*` | `host=like.*acefile` (tanpa `*` kedua = exact) |

### Pitfall: row cap PostgREST

Default limit PostgREST adalah **1000 row** dan max server **~3000**. Query tanpa `range` bisa:
- hit cap → hasil **terpotong** (count tidak akurat)
- timeout untuk full-scan `logs`

Selalu: (1) pakai `created_at=gte.<timestamp>` agar window sempit, (2) `order=created_at.desc`, (3) pagination `&limit=1000&offset=1000` bila butuh lebih, (4) bandingkan **window identik** antar query — hitung statistik dari window berbeda = salah.

### Management API SQL (query besar/aggregate)

```bash
TOKEN=$(cat /data/data/com.termux/files/home/ubuntu/ubuntu22-fs/root/.supabase/access-token 2>/dev/null)
REF=cjjopuwhpcuoaoifhcfj  # atau auto-detect dari source

curl -s -X POST "https://api.supabase.com/v1/projects/$REF/database/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"SELECT failure_type, count(*) FROM logs WHERE created_at > now() - interval '\''24 hours'\'' GROUP BY 1 ORDER BY 2 DESC"}'
```

> Jika path token tidak ada di environment sekarang, gunakan PostgREST. Jalur Supabase DDL (`ALTER TABLE`) tetap perlu manual di dashboard — anon key tidak bisa DDL.

### Common Queries

```sql
-- Provider failures (24 jam, explicit created_at filter)
SELECT tag, failure_type, count(*) AS cnt
FROM logs
WHERE created_at > now() - interval '24 hours'
  AND level != 'SUCCESS'
GROUP BY tag, failure_type
ORDER BY cnt DESC;

-- Latest extractor failures (window sempit)
SELECT created_at, extractor, failure_type, message
FROM logs
WHERE created_at > now() - interval '2 hours'
  AND extractor IS NOT NULL
  AND level IN ('FAIL','ERROR','CRITICAL')
ORDER BY created_at DESC LIMIT 30;

-- Success rate provider (window identik untuk pembanding)
SELECT tag,
  count(*) FILTER (WHERE level='SUCCESS') AS ok,
  count(*) FILTER (WHERE level!='SUCCESS') AS bad
FROM logs
WHERE created_at > now() - interval '1 hour'
  AND tag = 'Anichin'
GROUP BY tag;
```

## Case Study Ringkas: Anichin Cloudflare + Poster (2026-09-20)

- **Symptom:** main page kosong, poster tak tampil.
- **Root cause:** (1) `HttpStatusException` tidak membawa response body sehingga indikator CF tidak terdeteksi; (2) regex `CLOUDFLARE_HTTP` mengandung `\b403\b` sehingga *semua* 403 memicu solver; (3) `Document` tanpa `baseUri` membuat `absUrl()` kosong untuk poster root-relative.
- **Fix:** capture body + cek body, hapus `\b403\b`, hapus `retryAfter` di handler 403, `doc.setBaseUri(res.url)`, `safeExtractImage` pakai `absUrl()` dengan fallback raw.
- **Verifikasi:** CI hijau + live `curl_cffi` (200, 30 item) + regression test `SelectorResolverTest`.
- Detail kode ada di `extraction` (Cloudflare) dan `architecture` (Flow 3).

## Verification

- [ ] Tahu cara query logs (PostgREST / Management API)
- [ ] Bisa bedakan FailureType (`REMOVED` bukan bug, `N/A` = missing `type=`)
- [ ] Selalu query dengan window `created_at` eksplisit + pagination aware
- [ ] **Sudah cek `plugin_version` sebelum menyimpulkan "fix tidak berefek"**
- [ ] Tahu bedakan `stage` NULL (call site lupa) vs stage yang salah
- [ ] Kalau lihat `ok tanpa plugin_version` di logcat → migration 0005 belum di-apply
- [ ] Bisa identifikasi health provider dari pattern log terbaru

## Related Skills

- `development` — debugging workflow, log analysis methodology
- `extraction` — `EXTRACTOR_FAILURE`, `CONTENT_REMOVED`, `INVALID_URL`
- `selector-checker` — `SELECTOR_FAILURE` investigation
- `architecture` — module `log/`
- `build-deploy` — versionCode epoch menit (nilai `plugin_version`) + cara apply
  migration SQL via `apply-migrations.yml`
