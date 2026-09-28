---
name: oce-development
description: OCE Development — workflow, debugging, testing, impact analysis
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: development
---

# OCE Development

## Purpose

Workflow development OCE: locate, trace, modify, verify — dan cara mendiagnosis bug berdasarkan bukti, bukan dugaan.

## When to Use

- Task development (fix, fitur, refactor)
- Debugging issue
- Sebelum perubahan signifikan

## When NOT to Use

- Edit config JSON (→ `provider`)
- Verifikasi selector (→ `selector-checker`)
- Commit & deploy (→ `build-deploy`)
- Query log (→ `logging`)

## HARD RULE: No Local Gradle

`./gradlew` **dilarang keras** dijalankan lokal. Termux tidak punya resources untuk itu.

**Hanya:** `commit → push → cek CI` (`build-deploy`). Unit test hanya jalan di CI: `./gradlew :BaseProvider:testDebugUnitTest` di dalam workflow, bukan di HP.

Konsekuensi yang harus disadari: **Tidak ada compiler lokal** — kesalahan yang hanya ketahuan saat compile baru muncul ~5 menit kemudian lewat CI, setelah push. Jadi:
- Tulis unit test untuk setiap logika string/format murni. Ini jaring pengaman termurah: pada 2026-09-28 testlah yang menangkap bug `"$d.networkError"` (lihat di bawah), bukan review manual.
- Kalau memang tidak bisa diuji, pilih sintaks yang tidak ambigu.
- Scan idiom yang rapuh sebelum push:
  ```bash
  # Kotlin hanya menginterpolasi identifier setelah "$"; "$d.x" = toString(d) + ".x"
  grep -rnP '\$[A-Za-z_][A-Za-z0-9_]*\.[a-z]' --include='*.kt' BaseProvider/src/main/kotlin/ | grep -vP '\$\{'
  ```
  Hit yang tersisa harus memang literal yang disengaja (`"$fileName.json"`), bukan akses property. Bentuk yang aman: `"$d" + d.x` atau `"${d.x}"`.

## Development Workflow

```
UNDERSTAND → LOCATE → TRACE ALL PATHS → MODIFY → CI → VERIFY
```

### 1. UNDERSTAND
Task apa, mengapa, dampaknya. Tidak yakin → tanya user.

### 2. LOCATE
File di `BaseProvider/`. Baca **sebelum** edit. `architecture` untuk mental model.

### 3. TRACE ALL PATHS ← kunci
- **Callers:** siapa memanggil fungsi ini?
- **Callees:** apa yang dipanggil fungsi ini?
- **Semua entry path:** apakah ada jalur lain (loop utama, recursive iframe, variant runner, direct call) yang melewati kode ini?

> Pelajaran `eb51e94`: `skipHosts` sudah dicek di loop utama `FallbackPipeline`, tapi jalur `tryManualIframeFetch` **bypass**. Bug tetap ada. Kalau guard bersifat keamanan/biaya, ia harus ada di setiap path, bukan cuma path yang terlihat di dump pertama.

### 4. MODIFY
Perubahan **sekecil mungkin**. Ikuti konvensi yang ada. Jangan tambah cleanup/abstraction di luar scope. Preserve formatting.

### 5. TEST
- Test baru: `BaseProvider/src/test/kotlin/com/baseprovider/<Class>Test.kt` — bisa di subdir (`collector/`, `core/`, `extractor/`, `settings/`, `harness/`).
- Logika kritikal **wajib** ada test yang menguji perilaku nyata, bukan hanya yang compile.
- Jalankan **lewat CI** saja.

### 6. VERIFY
- [ ] Syntax & import valid
- [ ] Tidak ada dead code / duplicate logic
- [ ] Edge case: null, empty, boundary, failure path
- [ ] Backward compatible
- [ ] Diff review: ada perubahan unintended?
- [ ] CI hijau

## Debugging (Evidence-Based)

```
SYMPTOM → EVIDENCE → HYPOTHESIS → VERIFICATION → ROOT CAUSE → FIX
```

**Dilarang menebak penyebab.** Dugaan tanpa signature log atau reproduksi = belum jadi hipotesis.

### Decision Tree

```
Bug reported
├── Muncul di CI?
│   ├── YA → baca `gh run view <id> --log-failed`
│   │   ├── Syntax → fix syntax, commit, push
│   │   ├── Test fail → baca assertion, fix kode ATAU fix test kalau test-nya salah
│   │   └── Build fail → cek dependency dari log
│   └── TIDAK → bug runtime
│       ├── Query log terbaru (→ `logging`)
│       ├── Cek selector (→ `selector-checker`)
│       └── Cek extractor path (→ `extraction`)
├── Semua provider kena?
│   ├── YA → BaseProvider shared code
│   └── TIDAK → provider config / extractor config
└── Punya signature jelas?
    ├── YA → grep signature itu di codebase
    └── TIDAK → tambah logDebug lebih dulu, reproduksi
```

### Lessons yang Menghemat Waktu

| Kesalahan umum | Aturan |
|----------------|--------|
| Asumsi extractor config aktif padahal ID-nya tidak di `configDrivenIds` | Cek registry dulu |
| Patch guard di satu path saja | Trace **semua** entry path |
| Terus menebak selector saat item memang tidak ada di HTML | Cek HTML live dulu; kalau tidak ada, itu sisi situs |
| Menuduh `AdaptiveHeaderProbe` false-negative tanpa signature | Butuh data; default-nya ia benar |
| Menghidupkan kembali fitur yang sudah di-revert | `git log` dulu, konfirmasi ke user |
| Menyorot SUCCESS spam sebagai bug | Pesan konstan → `logDebug` |
| Hitung statistik dari window berbeda | Window harus identik (→ `logging`) |
| Klaim "sudah fix" saat CI hijau | CI hijau hanya compile+test; verifikasi produksi terpisah |

### Kalau ctx tidak cukup untuk patching
Pilih bagian **paling kecil** yang bisa dibuktikan. Kalau macet >2 menit, sederhanakan scope dan lapor — jangan menebak.

## Case Study: Anichin Cloudflare + Poster (2026-09-20)

- **Symptom:** main page kosong, poster tak tampil.
- **Evidence:** Supabase `NETWORK_FAILURE` + `CLOUDFLARE_FAILURE`; live curl dapat 403 + challenge HTML; `HttpStatusException` hanya bawa message tanpa body; regex `CLOUDFLARE_HTTP` mengandung `\b403\b` sehingga semua 403 memicu solver; `Document` tanpa `baseUri` → `absUrl()` kosong untuk poster root-relative.
- **Root cause:** body response tidak ikut exception + regex over-match; poster butuh baseUri.
- **Fix:** `NetworkUtils.kt` (body + regex), `HttpClient.kt` (setBaseUri, hapus retryAfter di 403, cek body), `ProviderParser.kt` (`safeExtractImage` pakai `absUrl` dengan fallback raw).
- **Verifikasi:** CI hijau, live `curl_cffi` 200 + 30 item, regression test `SelectorResolverTest`.

## Impact Analysis

Sebelum patch: cek **callers, consumers, imports, tests, config, hot path, backward compat, provider lain**.

## Common Patterns

**Tambah config field** → `ProviderConfig.kt` → `ProviderConfigParser.kt` → `ProviderConfigParserTest.kt` → pakai di engine → set di JSON.

**Tambah selector** → verifikasi HTML dulu (`selector-checker`) → edit JSON dengan multi-variant → cek series **dan** episode page → CI.

**Fix bug** → root cause (bukan symptom) → patch di shared code bila memengaruhi banyak provider → tambah regression test → backward compatible.

## Verification

- [ ] Tahu callers, callees, dan semua entry path
- [ ] Impact analysis done
- [ ] Regression test untuk logika kritikal
- [ ] Tidak ada perubahan unintended di diff
- [ ] CI hijau (bukan build lokal)

## Related Skills

- `shared` — git rules, change safety, anti-hallucination
- `architecture` — module & data flow
- `provider` / `extraction` — domain-specific
- `logging` — query log produksi
- `build-deploy` — CI/CD, commit rules
