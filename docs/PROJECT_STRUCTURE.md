# 📁 Project Structure

Dokumen ini menjelaskan struktur proyek yang sebenarnya. Semua path relatif terhadap root folder `oce/`.

---

## 📊 Struktur Folder

```
oce/
├── .github/workflows/          # CI/CD pipelines
│   └── ci-cd.yml               # Auto-build & deploy
│
├── docs/                       # Dokumentasi proyek
│   ├── README.md               # Index dokumentasi
│   ├── CONTEXT.md              # Overview proyek
│   ├── DEVELOPMENT_GUIDELINES.md
│   ├── QUICK_REFERENCE.md
│   └── ... (dokumentasi lain)
│
├── Anichin/                    # Provider: Anime streaming
│   ├── build.gradle.kts        # Konfigurasi plugin
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── kotlin/com/Anichin/
│   │       ├── Anichin.kt              # Main API implementation
│   │       ├── AnichinPlugin.kt        # Plugin registration
│   │       ├── AnichinUtils.kt         # Utilities & helpers
│   │       └── AnichinEkstraktors.kt  # Video extractors
│
├── Samehadaku/                 # Provider: Anime streaming
├── Pencurimovie/               # Provider: Movie/TV streaming
├── Funmovieslix/               # Provider: Movie streaming
├── Melolo/                     # Provider
├── Idlix/                      # Provider
├── LayarKaca21/                # Provider
├── Donghuastream/              # Provider: Donghua
├── Animasu/                   # Provider
├── Dramabox/                   # Provider
│
├── build.gradle.kts            # Root build config
├── settings.gradle.kts         # Gradle settings
├── gradle.properties           # Gradle properties
├── gradlew                     # Gradle wrapper
├── repo.json                  # Repository manifest
├── README.md                   # Main readme
├── CONTRIBUTING.md            # Contributing guide
└── local.properties.example    # Template konfigurasi lokal
```

---

## 📦 Provider Modules

Setiap folder provider adalah **module Gradle independen** yang menghasilkan satu file `.cs3` (CloudStream extension).

### Provider yang Tersedia (11):

| Provider | Jenis Konten | Bahasa |
|----------|--------------|--------|
| **Anichin** | Anime (Donghua) | Indonesia |
| **Animasu** | Anime | Indonesia |
| **Samehadaku** | Anime | Indonesia |
| **Donghuastream** | Donghua | Indonesia |
| **Pencurimovie** | Movie/TV Series | Indonesia |
| **LayarKaca21** | Movie/TV Series | Indonesia |
| **Funmovieslix** | Movie | Indonesia |
| **Idlix** | Movie/TV Series | Indonesia |
| **Melolo** | - | Indonesia |
| **Dramabox** | - | - |

---

## 📄 File per Provider

Setiap provider memiliki struktur file yang sama:

```
{Provider}/
├── build.gradle.kts           # Wajib - konfigurasi CloudStream plugin
├── src/main/
│   ├── AndroidManifest.xml     # Wajib - manifest Android
│   └── kotlin/com/{Provider}/
│       ├── {Provider}.kt       # Wajib - MainAPI implementation
│       ├── {Provider}Plugin.kt # Wajib - @CloudstreamPlugin annotation
│       ├── {Provider}Utils.kt  # Optional - utilities, cache, helpers
│       └── {Provider}Ekstraktors.kt  # Optional - custom extractors
```

### Penjelasan File:

| File | Wajib | Deskripsi |
|------|-------|-----------|
| `{Provider}.kt` | ✅ | Implementasi MainAPI: search, getMainPage, load, loadLinks |
| `{Provider}Plugin.kt` | ✅ | Plugin registration dengan annotation @CloudstreamPlugin |
| `{Provider}Utils.kt` | ❌ | Shared utilities: CacheManager, rateLimitDelay, executeWithRetry |
| `{Provider}Ekstraktors.kt` | ❌ | Custom extractor classes jika diperlukan |
| `build.gradle.kts` | ✅ | Konfigurasi: name, description, language, tvTypes |

---

## 🔧 Build System

### Build Command

```bash
# Build semua provider
./gradlew make

# Build provider spesifik
./gradlew :Anichin:make
./gradlew :Samehadaku:make

# Build dengan verbose output
./gradlew make --info
```

### Output

Setiap provider menghasilkan:
- `{Provider}.cs3` - CloudStream extension file (utama)
- `{Provider}.jar` - Java archive (compatibility)

Semua output masuk ke folder `build/` masing-masing provider.

---

## 🔄 CI/CD Workflow

```
Push ke master → CI/CD trigger → Build semua provider → Push ke builds branch
```

### Proses:
1. **Push** perubahan ke branch `master`
2. **CI/CD** berjalan otomatis (`.github/workflows/ci-cd.yml`)
3. **Build** semua provider dengan Gradle
4. **Push** artifacts (.cs3, plugins.json) ke branch `builds`

### Secrets yang Digunakan:
- TMDB_API, ANILIST_API (metadata)
- Various streaming API keys

---

## 📝 Catatan Penting

### ❌ YANG TIDAK ADA:
- `master/` folder - tidak ada centralized shared code
- `generated_sync/` folder - tidak ada auto-sync mechanism
- Shared module - setiap provider berdiri sendiri

### ✅ KONDISI SEHARENYA:
- Setiap provider memiliki file `*Utils.kt` sendiri dengan kode serupa
- Tidak ada otomatis sync - edit per provider
- Build independen per provider

---

## 🔗 Link Terkait

- [Main README](../README.md)
- [Development Guidelines](DEVELOPMENT_GUIDELINES.md)
- [Quick Reference](QUICK_REFERENCE.md)
- [CloudStream Documentation](https://recloudstream.github.io/csdocs/)

---

*Last Updated: 2026-05-06*
*Status: ✅ Accurate*