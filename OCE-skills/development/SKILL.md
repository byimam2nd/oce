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

Panduan development workflow OCE: debugging, testing, impact analysis, dan development practices. Skill ini membantu agent bekerja dengan benar terhadap codebase OCE.

## When to Use

- Saat mengerjakan task development (bug fix, fitur baru, refactor)
- Saat debugging issue
- Sebelum melakukan perubahan signifikan

## When NOT to Use

- Untuk edit provider config JSON (→ `provider`)
- Untuk verifikasi selector (→ `selector-checker`)
- Untuk commit & deploy (→ `build-deploy`)

## Development Workflow

```
UNDERSTAND → LOCATE → TRACE → MODIFY → TEST → VERIFY → REVIEW
```

### 1. UNDERSTAND
- Baca task description
- Identifikasi: apa yang berubah, mengapa, dampaknya
- Jika tidak yakin → tanya user

### 2. LOCATE
- Temukan file terkait di BaseProvider/
- Cek `architecture` skill untuk mental model
- Baca file SEBELUM edit

### 3. TRACE
- Cari callers: siapa yang memanggil function ini?
- Cari callees: apa yang dipanggil function ini?
- Cek impact: perubahan ini mempengaruhi subsystem lain?

### 4. MODIFY
- Perubahan SEKECIL MUNGKIN
- Ikuti conventions yang ada
- Jangan tambah cleanup/abstraction di luar scope
- Preserve formatting yang sudah ada

### 5. TEST
- Jalankan unit test: `./gradlew :BaseProvider:testDebugUnitTest` (via CI only)
- Atau: commit → push → CI build

### 6. VERIFY
- [ ] Syntax valid
- [ ] Import/reference tidak broken
- [ ] Tidak ada dead code
- [ ] Edge cases ter-handle
- [ ] Backward compatible

### 7. REVIEW
- Review diff sendiri sebelum claim selesai
- Cek: apakah ada perubahan unintended?
- Cek: apakah scope terlalu luas?

## Debugging (Evidence-Based)

**DILARANG menebak penyebab bug.** Gunakan:

```
SYMPTOM → EVIDENCE → HYPOTHESIS → VERIFICATION → ROOT CAUSE → FIX
```

### Decision Tree

```
Bug report diterima
├── Apakah error muncul di CI?
│   ├── YA → baca CI log, cari error message
│   │   ├── Syntax error → fix syntax, commit, push
│   │   ├── Test failure → baca assertion, fix kode
│   │   └── Build failure → cek dependency, fix
│   └── TIDAK → bug di runtime
│       ├── Cek Supabase logs (→ `logging` skill)
│       ├── Cek selector (→ `selector-checker`)
│       └── Cek extractor (→ `extraction`)
├── Apakah error terjadi di semua provider?
│   ├── YA → masalah di BaseProvider/shared code
│   └── TIDAK → masalah di specific provider config
└── Apakah ada error message spesifik?
    ├── YA → grep error message di codebase
    └── TIDAK → tambah logging dulu, reproduksi
```

### Debugging Checklist

1. **Reproduksi** — bisa error ini diulang?
2. **Isolasi** — di mana tepatnya error terjadi?
3. **Evidence** — kumpulkan data (logs, stack trace, HTTP response)
4. **Hypothesis** — buat dugaan berdasarkan evidence
5. **Verify** — uji hypothesis dengan perubahan minimal
6. **Fix** — perbaiki root cause, bukan symptom
7. **Regression test** — pastikan fix tidak breaking yang lain

## Testing

### Framework
- **Kotlin test** (kotlin.test) — unit tests
- **JUnit5** via Gradle test runner
- **Location:** `BaseProvider/src/test/kotlin/com/baseprovider/`

### 17 Test Files (current)

| File | Tests | Coverage |
|------|-------|----------|
| `MovieSeriesDetectorTest.kt` | 10 | Movie vs series detection |
| `EpisodeDetectionTest.kt` | 4 | Episode link detection |
| `ConfigDrivenEngineTest.kt` | 9 | ExtractId, JsonPath |
| `ExtractorConfigParserTest.kt` | 7 | Config JSON parsing |
| `CompiledRegexPatternsTest.kt` | 5 | URL prioritization |
| `SmartThrottleTest.kt` | 6 | Rate limiting |
| `NetworkUtilsTest.kt` | 4 | HTTP utilities |
| `SelectorResolverTest.kt` | 8 | Selector fallback + safeExtractImage |
| `ProviderConfigTest.kt` | 3 | Config validation |
| `ProviderConfigParserTest.kt` | 9 | JSON → ProviderConfig |
| `ExpiringCacheTest.kt` | 5 | TTL cache |
| `CircuitBreakerTest.kt` | 4 | Circuit breaker |
| `PosterResizerTest.kt` | 10 | URL resize |
| `SearchPaginationTest.kt` | 4 | Search hasNext |
| `M3u8MasterVerifierTest.kt` | 7 | Master m3u8 validation |
| `AdaptiveQualityPickerTest.kt` | 10 | Quality selection |
| `OceSettingsTest.kt` | 7 | Settings |

### Running Tests
**Via CI only:**
```bash
git commit -m "test: description" && git push origin master && git push private master
gh run watch <id> --repo byimam2nd/oce-source --exit-status
```

**Local (development only, not for verification):**
```bash
./gradlew :BaseProvider:testDebugUnitTest
```

### Adding Tests
1. Buat file di `BaseProvider/src/test/kotlin/com/baseprovider/`
2. Naming: `<Class>Test.kt`
3. Import: `kotlin.test.*`, `org.junit.jupiter.api.*`
4. Test methods: descriptive names (boleh spasi, BDD-style)
5. Pastikan test independent (tidak bergantung test lain)

## Impact Analysis

Sebelum perubahan signifikan, checklist:

| Concern | Cek |
|---------|-----|
| Callers | Siapa yang panggil function ini? |
| Consumers | Siapa yang pakai class/interface ini? |
| Dependencies | Apa yang di-import? |
| Tests | Ada test yang cover ini? |
| Config | Ada config field yang terpengaruh? |
| Runtime | Apakah ini hot path? |
| Backwards compat | Apakah API berubah? |
| Other providers | Apakah semua provider terpengaruh? |

### Rule: Minimal Change
- Jangan rewrite jika local fix cukup
- Jangan tambah abstraction baru jika belum perlu
- Jangan rename massal
- Jangan format ulang file yang tidak sedang diubah

## Common Patterns

### Adding Config Field
1. Add field ke `ProviderConfig.kt` dengan default
2. Add JSON parsing ke `ProviderConfigParser.kt`
3. Update `ProviderConfigTest.kt`
4. Update `all bundled configs parse` test
5. Use field di engine code

### Adding Selector
1. Add field ke `ProviderConfig.kt`
2. Add parsing ke `ProviderConfigParser.kt`
3. Add test ke `ProviderConfigParserTest.kt`
4. Add CSS selector ke provider JSON config
5. Verify dengan `selector-checker` skill

### Fixing Bug
1. Cari root cause (bukan symptom)
2. Fix di tempat yang benar (shared code, bukan per-provider)
3. Tambah test jika tidak ada yang cover
4. Pastikan fix backward compatible

## Verification

- [ ] Code trace: tahu callers dan callees
- [ ] Impact analysis: tahu dampak perubahan
- [ ] Diff review: tidak ada perubahan unintended
- [ ] CI hijau setelah push

## Related Skills

- `shared` — git rules, change safety, anti-hallucination
- `architecture` — module structure, data flow
- `provider` — provider-specific development
- `extraction` — extractor-specific development
- `logging` — debugging via Supabase logs
- `build-deploy` — CI/CD, commit rules
