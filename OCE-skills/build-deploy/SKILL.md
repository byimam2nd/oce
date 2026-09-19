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

**DILARANG keras** menjalankan `./gradlew` atau build gradle apapun di lingkungan lokal.

Build & verifikasi kode dilakukan **HANYA** dengan:
```
commit → git push origin master → git push private master → cek CI
```

Jika user meminta "build" / "verifikasi" / "cek compile":
1. Commit changes
2. Push ke kedua remote
3. Watch CI: `gh run watch <id> --repo byimam2nd/oce-source --exit-status`
4. Laporkan hasil ke user

## CI/CD Pipeline

### Build Pipeline (`ci-cd.yml`)

**Trigger:** push ke `master` (paths: `*/src/**`, build files, workflows)

**Runner:** `ubuntu-22.04`

**Steps:**
```
1. Checkout source (private)        → fetch-depth: 0
2. Pre-populate JitPack artifact    → gradle.jar from jitpack.io
3. Setup JDK 17                     → actions/setup-java@v4, adopt
4. Setup Android SDK                → android-actions/setup-android@v4
5. Set Build Environment:
   - SUPABASE_URL (from secrets)
   - SUPABASE_ANON_KEY (from secrets)
   - BUILD_TIMESTAMP (Asia/Jakarta)
   - OCE_VERSION = $(($(date +%s) / 60))  ← epoch minutes, monotonic
6. Validate provider configs        → python3 scripts/validate_providers.py
7. Run BaseProvider unit tests      → ./gradlew :BaseProvider:testDebugUnitTest
8. Build Plugins                    → ./gradlew make makePluginsJson ensureJarCompatibility
9. Upload artifacts                 → actions/upload-artifact@v4
```

### Publish Beta (`ci-cd.yml` — job 2)

**Needs:** build (harus hijau dulu)

```
1. Generate GitHub App token
2. Checkout public distribution (builds branch)
3. Download artifacts
4. Deploy beta → commit + push ke builds branch
```

### Release Pipeline (`release.yml`)

**Trigger:** tag `v*`

```
1. Build plugins (sama dengan ci-cd build)
2. Patch URLs untuk GitHub Release
3. Create GitHub Release → .cs3 + plugins.json
```

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

### Bump ditentukan oleh DAMPAK, bukan urutan

| Dampak | Contoh | Bump |
|--------|--------|------|
| Kecil | bug fix, perf, refactor, chore | `vX.Y.(Z+1)` |
| Sedang | fitur baru, provider/extractor baru | `vX.(Y+1).0` |
| Besar | arsitektur berubah, API/config break | `v(X+1).0.0` |

### Tag Workflow

```bash
# 1. Pastikan CI hijau dulu
gh run watch <id> --repo byimam2nd/oce-source --exit-status

# 2. Tag di HEAD (setelah CI hijau)
git tag -a v3.16.1 -m "fix: deskripsi singkat"

# 3. Push ke kedua remote
git push origin v3.16.1
git push private v3.16.1
```

**Tag men-trigger release pipeline.** Jangan tag sebelum CI hijau.

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
- [ ] Beta artifacts ter-deploy ke builds branch
- [ ] Jika release: tag di HEAD, release pipeline hijau

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
