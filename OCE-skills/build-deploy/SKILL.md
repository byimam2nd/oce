---
name: oce-build-deploy
description: OCE Build & Deploy — CI/CD pipeline, commit rules, tag & release, verification
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: build-deploy
---

# OCE Build & Deploy

## Purpose

Mengelola build pipeline, commit workflow, tag & release OCE. Skill ini memastikan agent tidak menjalankan build lokal dan selalu memverifikasi via CI.

## When to Use

- Setelah selesai edit kode dan perlu commit
- Saat user minta "build", "verifikasi", "cek compile"
- Saat ingin bump version & release
- Saat CI merah dan perlu debug

## When NOT to Use

- Untuk edit kode provider/extractor (→ `provider` atau `extraction`)
- Untuk cek selector (→ `selector-checker`)

## CRITICAL RULE: No Local Gradle Build

**DILARANG keras** menjalankan `./gradlew` atau build gradle apapun di lingkungan lokal (Termux).

Build & verifikasi kode dilakukan **HANYA** dengan:
```
commit → git push origin master → git push private master → cek CI
```

Jika user meminta "build" / "verifikasi" / "cek compile":
1. Commit changes
2. Push ke kedua remote
3. Watch CI: `gh run watch <id> --repo byimam2nd/oce-source --exit-status`
4. Laporkan hasil ke user

**Pengecualian:** commit dokumentasi/markdown saja (mis. `OCE-skills/`) **tidak memicu CI** karena path filter di bawah tidak mencakupnya. Itu normal — tidak perlu menunggu run yang tidak akan terjadi.

## CI/CD Pipeline

### Build + Publish Beta (`ci-cd.yml`)

**Trigger:** push ke `master`, **hanya** untuk path:
`*/src/**`, `*/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `.github/workflows/**`. Plus `workflow_dispatch`.

**Runner:** `ubuntu-22.04` (JANGAN `ubuntu-latest`)

**Steps:**
```
1. Checkout source (private, fetch-depth 0)
2. Pre-populate JitPack artifact (gradle.jar dari jitpack.io)
3. Setup JDK 17 (adopt) + Android SDK (android-actions/setup-android@v4)
4. Env: SUPABASE_URL, SUPABASE_ANON_KEY, BUILD_TIMESTAMP (Asia/Jakarta),
         OCE_VERSION = $(($(date +%s) / 60))   ← epoch minutes, monotonic
5. python3 scripts/validate_providers.py
6. ./gradlew :BaseProvider:testDebugUnitTest
7. ./gradlew make makePluginsJson ensureJarCompatibility
8. Upload artifacts
9. Job "publish-beta": token GitHub App → checkout builds branch (public) →
   download artifacts → push ke byimam2nd/oce@builds
```

### Health Check (`health-check.yml`)
- **Trigger:** cron `20 22 * * *` (05:20 WIB) + `workflow_dispatch`
- `scripts/health_check.py` → menulis issue kalau ada provider bermasalah
- Permissions: `issues: write`

### Provider Test Harness (`provider-test.yml`)
- **Trigger:** `workflow_dispatch` saja (tidak otomatis)
- Menjalankan harness: `./gradlew :BaseProvider:testDebugUnitTest -Poce.harness=1 -Poce.harness.providers=... -Poce.harness.query=... -Poce.harness.episodes=...`
- Output → `/tmp/harness.md` + GitHub Step Summary
- Dipakai saat butuh reproduksi scraping tanpa perangkat.

### Release (`release.yml`)
- **Trigger:** tag `v*` (atau `workflow_dispatch`)
- `OCE_VERSION` = epoch menit (sama seperti beta) + `RELEASE_TAG` untuk description
- Build sama seperti `ci-cd`, patch URL untuk GitHub Release, buat release `.cs3` + `plugins.json`
- Job `release` butuh secret `OCE_DISTRIBUTOR_APP_ID` + `OCE_DISTRIBUTOR_APP_PEM`; kalau kosong, artefak tetap ter-build tapi GitHub Release tidak dibuat.

## CRITICAL: Skema versionCode

`OCE_VERSION` = **epoch menit**, mis. `29843182`. Satu ruang versionCode dipakai bersama oleh `ci-cd.yml` (beta) dan `release.yml` (stable).

```kotlin
// build.gradle.kts
val rawVersion = System.getenv("OCE_VERSION")?.trim()
val envVersion = if (rawVersion.isNullOrBlank()) null else
    requireNotNull(rawVersion.toIntOrNull()) { "OCE_VERSION harus angka monoton, bukan '$rawVersion'..." }
```

**Jangan pernah pass semver.** Skema lama (`filter { it.isDigit() }`) merusaknya:

| Tag | Skema lama | Akibat |
|-----|-----------|--------|
| v3.13.03 | `31303` | — |
| v3.16.0 | `3160` | lebih kecil dari v3.13.03, non-monoton |
| v3.9.1 | `391` | user di v3.16.0 tidak pernah ditawari update |

Karena beta sudah menempati ~29.8 jt, **tidak ada semver yang bisa mengalahkannya** — user beta tidak akan pernah bisa kembali ke stable. Karena itu tag (`v4.0.0`) hanya nama rilis; versionCode tetap angka. Tag tampil di description plugin: `[v4.0.0 | 2026-09-28 17:22 WIB]`.

`requireNotNull` dipakai agar salah-isi **gagal keras** — diam-diam jatuh ke `version 1` akan membuat user tidak bisa update tanpa ada yang sadar.

## Release vs Beta

| | Beta | Stable |
|---|------|--------|
| Trigger | push ke `master` (path filter) | tag `v*` |
| Workflow | `ci-cd.yml` | `release.yml` |
| Output | branch `builds` | GitHub Release (`.cs3` + `plugins.json`) |
| VERSION | epoch menit | epoch menit |

Latest tag = kode yang **sudah tersebar ke user**. `git rev-list --count <tag>..HEAD` = berapa commit yang belum rilis — kalau besar, user stable belum dapat fix terbaru. Ini penyebab paling umum "fix tapi tidak berefek" (lihat `logging`).

Cek Ordering sebelum tag:
```bash
git fetch origin +refs/heads/builds:refs/remotes/origin/builds --force -q
git show origin/builds:plugins.json | python3 -c "import json,sys; print(sorted({e['version'] for e in json.load(sys.stdin)}))"
```
Pastikan versi beta terakhir < epoch menit saat tag dipotong (otomatis true, karena keduanya `date +%s`).

## Verifikasi Beta — Jangan Asumsikan User Sudah Update

| Sumber | Cara |
|--------|------|
| CI run | `gh run view <id>` → step "Set Build Environment" |
| Beta artifact | `byimam2nd/oce@builds` → `plugins.json` |
| Release artifact | `gh release view <tag> --repo byimam2nd/oce` |
| User | minta versi dari user / logcat (`logDebug`) |

**Ingat:** orang berbeda update di waktu berbeda. Log produksi sebelum user update masih berasal dari build lama → jangan simpulkan fix gagal (`logging`).

## Commit Rules

### 1. Jangan commit sebelum diperintah user

### 2. Commit Message Format
```
fix:     bug fix, perf improvement, refactor internal
feat:    new feature, provider baru, extractor baru
refactor: restructuring tanpa behavioral change
chore:   CI, docs, config maintenance
```

### 3. Satu commit = satu perubahan logis

### 4. Setelah commit, push ke kedua remote
```bash
git push origin master && git push private master
```

### 5. WAJIB cek CI setelah push
```bash
# Tunggu ~15 detik untuk CI trigger
sleep 15

# Cek run terbaru
gh run list --repo byimam2nd/oce-source --limit 1

# Watch sampai selesai
gh run watch <run-id> --repo byimam2nd/oce-source --exit-status
```

### 6. Jangan anggap selesai sebelum CI hijau

## Version & Tag Rules

### Bump tag ditentukan oleh DAMPAK

| Dampak | Contoh | Bump |
|--------|--------|------|
| Kecil | bug fix, perf, refactor, chore | `vX.Y.(Z+1)` |
| Sedang | fitur baru, provider/extractor baru | `vX.(Y+1).0` |
| Besar | arsitektur berubah, versioning break | `v(X+1).0.0` |

**Nomor tag TIDAK menjadi versionCode** (lihat skema versionCode di atas). Jadi nomor tag boleh "mundur" secara semver — yang penting unik. Tetap lebih baik monoton untuk keterbacaan.

### Before Tag: cek berapa commit yang tertahan

```bash
LT=$(git describe --tags --abbrev=0)
echo "commits sejak $LT: $(git rev-list --count $LT..HEAD)"
```

Angka besar = banyak fix yang belum sampai ke user stable. Kalau itulah yang terjadi, **itulah alasan utama** laporan "sudah fix tapi masih error" (lihat `logging`).

### Tag Workflow

```bash
# 1. Pastikan CI hijau dulu
gh run watch <id> --repo byimam2nd/oce-source --exit-status

# 2. Tag di HEAD (setelah CI hijau)
git tag -a v4.0.1 -m "fix: deskripsi singkat"

# 3. Push ke kedua remote
git push origin v4.0.1
git push private v4.0.1

# 4. Watch release
gh run list --repo byimam2nd/oce-source --workflow=release.yml --limit 1
gh run watch <id> --repo byimam2nd/oce-source --exit-status

# 5. Verifikasi artefak benar-benar publik
gh release view v4.0.1 --repo byimam2nd/oce --json tagName,isDraft,assets
```

**Tag men-trigger release pipeline.** Jangan tag sebelum CI hijau. Release sukses ≠ release ada: cek `isDraft=false` dan asset `.cs3` + `plugins.json` benar-benar ada.

## Branch Structure

| Branch | Purpose |
|--------|---------|
| `master` | Source code |
| `builds` | Beta artifacts (.cs3, plugins.json) |
| GitHub Releases | Stable artifacts |

## Remote Setup

```
origin  = byimam2nd/oce         (public, release)
private = byimam2nd/oce-source  (source, CI trigger)
```

## versionCode Convention

`OCE_VERSION = $(($(date +%s) / 60))` — epoch minutes. Monotonic, tidak perlu manual bump.

## Common CI Failures

### SDK Setup Failed
```
Failed to find package 'tools'
```
→ Pastikan pakai `android-actions/setup-android@v4` (bukan v3)
→ Pastikan runner `ubuntu-22.04` (bukan `ubuntu-latest`)

### Unit Tests Failed
→ Baca error log. Biasanya: syntax error, missing import, broken test
→ Fix, commit, push, watch CI lagi

### Build Failed
→ Cek gradle output. Biasanya: missing dependency, syntax error
→ Jangan build lokal — fix berdasarkan error log, push, CI

## Verification

- [ ] Commit message sesuai format (`fix:`/`feat:`/`refactor:`/`chore:`)
- [ ] Push ke kedua remote (origin + private)
- [ ] CI build hijau (`gh run watch`)
- [ ] Beta artifacts ter-deploy ke builds branch, versionCode naik
- [ ] Jika release: tag di HEAD, release pipeline hijau, `isDraft=false`, asset `.cs3` + `plugins.json` ada
- [ ] Kalau `OCE_VERSION` diubah: cek ordering vs beta terakhir sebelum tag

## Recovery

### CI Merah setelah push
1. Baca error di CI log (`gh run view <id>`)
2. Fix masalah
3. Commit fix, push, watch CI lagi
4. Jangan amend commit yang gagal — bikin commit baru

### Merge Conflict
1. Fetch: `git fetch origin master`
2. Rebase: `git rebase origin/master`
3. Resolve conflicts
4. Force push: `git push private master --force-with-lease`
5. Push origin: `git push origin master`

## Related Skills

- `shared` — git rules, change safety
- `architecture` — understanding build system
- `development` — debugging CI failures
