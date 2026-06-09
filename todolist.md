# OCE Development Todo List

> Panduan pengembangan terstruktur untuk memastikan konsistensi, meminimalkan kesalahan, dan mencegah item terlewat.

---

## 📋 Aturan Main

1. **Update todolist sebelum mulai task baru** — baca, cari item `[ ]` yang relevan, ubah ke `[-]`.
2. **Selesaikan satu item, jangan paralel** — fokus sampai tuntas, baru pindah.
3. **Tandai item**:
   - `[ ]` → Belum dikerjakan
   - `[-]` → Sedang dikerjakan
   - `[x]` → Selesai
   - `[~]` → Dilewati / tidak jadi
4. **Commit message harus menyertakan ID item** — contoh: `fix(baseprovider): P1-03 split BaseProviderHelpers.kt jadi 6 file`
5. **Setiap selesai 1 item → run lint + build** (atau push ke CI biar CI yang validasi).
6. **Jika menemukan masalah baru di luar scope** → tambah ke **§ Additional Findings** (bukan diperbaiki sekarang).

---

## 🏷️ Prioritas

| Level | Label | Arti |
|-------|-------|------|
| 🔴 P1 | **Critical** | blocking, correctness, atau stability. Wajib selesai sebelum rilis. |
| 🟡 P2 | **High** | maintainability, meningkatkan kualitas kode. Target selesai 1-2 sprint. |
| 🟢 P3 | **Medium** | polish, naming, formatting, docs. Bisa ditunda. |
| ⚪ P4 | **Low** | nice-to-have, refactor besar tanpa dampak langsung. |

---

## 🎯 PHASE 1: Correctness & Stability (P1)

### P1-01 [x] Fix package declaration extractor
- **File**: Semua file di `BaseProvider/src/main/kotlin/com/baseprovider/extractor/`
- **Aksi**: Ubah `package com.baseprovider` → `package com.baseprovider.extractor`
- **Risk**: Import broken di semua file yang refer extractor class. Wajib update semua import.
- **Verifikasi**: `./gradlew make --stacktrace` → success

### P1-02 [x] Rename `wishfast` → `Wishfast`
- **File**: `WishfastExtractor.kt`
- **Aksi**: Rename class dari `wishfast` ke `Wishfast`. Update reference di `ExtractorRegistry.kt` jika ada.
- **Risk**: Rendah (single class, single reference).

### P1-03 [x] Split `BaseProviderHelpers.kt`
- **Aksi**: Pisah menjadi 6 file terpisah:
  - `CircuitBreaker.kt` → `HostCircuitBreaker` object
  - `SmartThrottle.kt` → `SmartThrottle` object + `rateLimitDelay()`
  - `ExpiringCache.kt` → `ExpiringCache<T>` class + `globalHtmlCache` + `linkSemaphore`
  - `Logging.kt` → `FailureType` enum, `LogLevel` enum, `ProviderLog` object, semua fungsi `log*()`
  - `NetworkUtils.kt` → `executeWithRetry()`, `NON_RETRYABLE_HTTP`
  - `DomainHelpers.kt` → `normalizeDomain()`, `normalizeExtractorDomain()`, `isDirectMediaUrl()`
- **Risk**: Import reference di banyak file berubah. Pastikan semua import di-update.
- **Verifikasi**: Build success + tidak ada regression.

### P1-04 [x] Wrapping baris >100 karakter
- **Aksi**: Wrap semua baris yang >100 karakter (prioritas). Selesai — semua >150 chars fixed, >100 chars sisanya method signature API yang tidak bisa diringkas.
- **Risk**: Rendah — hanya formatting.

---

## 🎯 PHASE 2: Maintainability (P2)

### P2-01 [x] Extract `fromJson()` dari `ProviderConfig.kt`
- **Aksi**: Pindahkan `fromJson()` + 6 private helper methods ke `ProviderConfigParser.kt`.
- **Target**: `ProviderConfig.kt` turun dari 303 → ~200 baris.
- **Risk**: `fromJson()` dipanggil di `ConfigRegistry.kt` — update import.

### P2-02 [x] Decompose `ProviderScrapper.kt`
- **Aksi**: Ekstrak logic `load()` → `DetailPageScrapper.kt` class terpisah.
- **Target**: `ProviderScrapper.kt` turun dari 175 → ~100 baris.
- **Risk**: Method `load()` complex. Pastikan tidak ada logic yang terlewat.

### P2-03 [x] Pisah multi-class files
- **File**: `SimpleExtractors.kt`, `StreamRubyExtractor.kt`, `HownetworkExtractor.kt`, `VidguardtoExtractor.kt`
- **Aksi**: Masing-masing class pindah ke file sendiri.
- **Risk**: Update `ExtractorRegistry.kt` import.

### P2-04 [x] Ekstrak nested data class `ByseSXExtractor`
- **Action**: Pindahkan 5 data class (`ByseDetailsRoot`, `BysePlaybackRoot`, dll.) ke file terpisah atau top-level di extractor package.
- **Risk**: Import reference.

---

## 🎯 PHASE 3: Polish & Formatting (P3)

### P3-01 [x] Wrapping baris >75 karakter (lanjutan P1-04)
- **Aksi**: Wrap semua baris yang masih >75 karakter. Selesai — semua >120 chars fixed, sisanya method signature/class declaration yang tidak bisa diringkas.
- **Risk**: Rendah.

### P3-02 [x] Standarisasi imports
- **Aksi**: Hapus unused imports, sort imports sesuai standar Kotlin. Redundant same-package imports dihapus dari semua file.
- **Risk**: Rendah.

### P3-03 [x] Review naming regex constants
- **Aksi**: Pindahkan regex konstanta ke `CompiledRegexPatterns.kt` jika tersebar di file lain.
- **Risk**: Rendah.

---

## 🎯 PHASE 4: Architecture (P4)

### P4-01 [x] Eliminasi global mutable state
- **Aksi**: Enkapsulasi `globalHtmlCache`, `linkSemaphore` — inject via constructor atau context.
- **Risk**: Perubahan di banyak file. Testing diperlukan.

### P4-02 [x] Ekstrak `ProviderScrapper.loadLinks()` → `LinkCollector` merge?
- **Aksi**: Evaluasi apakah `LinkCollector` perlu di-merge dengan `ProviderScrapper.loadLinks()`, atau dipisah lebih lanjut. Keputusan: tidak perlu merge — separation of concerns sudah benar.

### P4-03 [x] Unit test untuk `ProviderConfig.fromJson()`
- **Aksi**: Tambah test untuk parser config JSON.
- **Risk**: Framework test perlu disiapkan.

---

## 📈 Progress Tracker

| Phase | Total | Selesai | Sisa |
|-------|-------|---------|------|
| P1 | 4 | 4 | 0 |
| P2 | 4 | 4 | 0 |
| P3 | 3 | 3 | 0 |
| P4 | 3 | 3 | 0 |
| **Total** | **14** | **14** | **0** |

---

## 🧠 Additional Findings

> Masalah ditemukan di luar scope task saat ini. Dicatat untuk follow-up terpisah.

| # | Temuan | Ditemukan | Impact |
|---|--------|-----------|--------|
| 1 | `ProviderMapper.kt:79` — line 426 chars, chain call ekstrim | Analisis 2026-06-09 | Rendah |
| 2 | duplicate `jsonArrayToList` overloads di `ProviderConfig.kt` (lines 278-286) | Analisis 2026-06-09 | Rendah |
| 3 | Flat package: extractor subfolder tapi `package com.baseprovider` | Analisis 2026-06-09 | Medium (P1-01) |

---

## 🚀 Cara Pakai

1. **Mulai task**: `sed -i 's/\[ \] P1-01/\[-\] P1-01/' todolist.md`
2. **Selesai**: `sed -i 's/\[-\] P1-01/\[x\] P1-01/' todolist.md` + update Progress Tracker
3. **Commit**: `git commit -m "fix(baseprovider): P1-01 fix package declaration extractor"`
4. **Sync**: Update Progress Tracker setiap 5 commit (atau akhir sesi)

---

*Last updated: 2026-06-09*
