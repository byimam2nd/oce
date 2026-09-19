---
name: oce-shared
description: OCE shared rules — git, change safety, anti-hallucination, verification, workflow principles
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: shared-rules
---

# OCE Shared Rules

Rules berlaku untuk SEMUA skill OCE. Baca skill ini SEBELUM skill lain.

## WHEN → SEE → CHECK → ACT → VERIFY

Pattern wajib untuk semua task:

1. **WHEN** — pahami task, identifikasi scope
2. **SEE** — baca file/repository terkait SEBELUM edit
3. **CHECK** — verifikasi asumsi terhadap kode aktual
4. **ACT** — lakukan perubahan minimal
5. **VERIFY** — buktikan perubahan benar

## Anti-Hallucination Rules

- **DILARANG** mengasumsikan nama file, function, class, API, command, atau behavior yang belum diverifikasi dari repository.
- **DILARANG** menyatakan task berhasil sebelum melakukan verification (build, test, syntax check).
- **DILARANG** membuat file, dependency, endpoint, atau konfigurasi berdasarkan asumsi semata.
- **DILARANG** mengarang informasi teknis. Jika tidak yakin, TANDAI sebagai uncertainty.
- Jika informasi penting tidak diketahui → **inspect repository/environment terlebih dahulu**.
- Repository adalah source of truth. Dokumentasi lama bisa stale — selalu cross-check ke kode.

## Change Safety

### Sebelum Edit
```
CHECK CURRENT STATE:
1. git status — ada uncommitted changes?
2. git diff — apa yang sudah berubah?
3. Baca file target — pahami struktur & conventions
4. Cari callers/consumers — siapa yang menggunakan code ini?
```

### Setelah Edit
```
CHECK RESULT:
1. Syntax valid (Kotlin: compilable, JSON: parseable)
2. Import/reference tidak broken
3. Tidak ada dead code atau duplicate logic
4. Edge cases ter-handle: null, empty, boundary
5. Backward compatibility maintained
```

### Rules
- Jangan edit file di `Provider*/` — akan ditimpa build. Edit di `BaseProvider/`.
- Jangan rewrite besar jika perubahan lokal sudah cukup.
- Jangan tambah cleanup, abstraction, rename, atau format di luar scope task.
- Pertahankan behavior existing. Perubahan harus additive/fallback, bukan replacement.
- Satu perubahan logis per commit.

## Git & Repository Management

### Remote Setup
```
origin  = byimam2nd/oce         (public, release builds)
private = byimam2nd/oce-source  (source code, CI trigger)
```

### Push Pattern
```
git push origin master && git push private master
```

### Commit Message Convention
```
fix:     bug fix, perf, refactor internal
feat:    new feature, provider baru, extractor baru
refactor: restructuring tanpa behavioral change
chore:   CI, docs, config maintenance
```

### CRITICAL: CI Verification
Setelah commit & push, **WAJIB cek CI**:
```bash
gh run list --repo byimam2nd/oce-source --limit 1
gh run watch <run-id> --repo byimam2nd/oce-source --exit-status
```
**Jangan anggap selesai sebelum CI hijau.**

### DILARANG
- Commit tanpa perintah user
- Force push ke master
- Reset perubahan user tanpa izin
- Delete untracked files tanpa verifikasi
- Overwrite konfigurasi tanpa cek state

## Verification Patterns

### Code Verification
```
1. File exists? → ls/glob
2. Syntax valid? → grep for obvious errors, or compile check via CI
3. Imports resolved? → grep for class/function references
4. Tests pass? → CI unit tests
5. Behavior correct? → runtime test via CI publish
```

### Selector Verification
See skill: `selector-checker` — 4-phase verification.

### Build Verification
**DILARANG menjalankan `./gradlew` lokal.** Build hanya via:
```
commit → push → CI build
gh run watch <id> --exit-status
```

## Repository Mental Model

Agent harus mampu menjawab:
- Apa entry point? → `ProviderCloudstream` (extends `MainAPI`)
- Bagaimana data mengalir? → Config → Engine → Scrapper → Mapper → CloudStream
- Module boundary? → Provider*/ = thin wrapper, BaseProvider/ = shared code
- Config? → JSON per-provider di `config/`, global fallback
- Build? → sourceSets compile BaseProvider ke setiap provider
- Test? → `:BaseProvider:testDebugUnitTest`
- Deploy? → CI push → build → publish-beta ke builds branch

## Inter-Skill Navigation

| Task | Skill |
|------|-------|
| Pahami arsitektur OCE | `architecture` |
| Tambah/edit provider | `provider` |
| Tambah/edit extractor | `extraction` |
| Debug bug/error | `development` (bagian debugging) |
| Cek selector | `selector-checker` |
| Cek log/error | `logging` |
| Build & deploy | `build-deploy` |
| Commit & push | `build-deploy` (bagian commit rules) |
