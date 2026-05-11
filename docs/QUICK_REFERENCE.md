# ⚡ Quick Reference

## 🚀 Common Tasks

### Build Project
```bash
# Build semua provider
./gradlew make

# Build provider spesifik
./gradlew :Anichin:make
./gradlew :Samehadaku:make

# Clean dan build ulang
./gradlew clean make
```

### Git Commands
```bash
# Commit dan push
git add -A
git commit -m "fix: description"
git push

# Pull latest
git pull --rebase

# Check status
git status
```

---

## 📁 Struktur File Provider

```
{Provider}/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/{Provider}/
        ├── {Provider}.kt           # MainAPI
        ├── {Provider}Plugin.kt     # @CloudstreamPlugin
        ├── {Provider}Utils.kt      # Utilities (optional)
        └── {Provider}Ekstraktors.kt # Extractors (optional)
```

---

## 🔧 CloudStream MainAPI Methods

| Method | Wajib | Deskripsi |
|--------|-------|-----------|
| `getMainPage()` | ✅ | Halaman utama/home |
| `search()` | ✅ | Pencarian konten |
| `load()` | ✅ | Detail halaman konten |
| `loadLinks()` | ✅ | Ekstraksi video link |
| `getTags()` | - | Tags/genres |
| `getYears()` | - | Available years |

---

## 📦 Imports yang Umum

```kotlin
// CloudStream library
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

// Java utilities
import java.util.concurrent.ConcurrentHashMap

// JSoup untuk HTML parsing
import org.jsoup.nodes.Element
```

---

## 🛠️ Utility Functions

Setiap provider memiliki utility functions di `{Provider}Utils.kt`:

```kotlin
// Cache
CacheManager<T>(ttl = 30 * 60 * 1000)

// Rate limiting
suspend fun rateLimitDelay()

// HTTP
suspend fun <T> executeWithRetry(block: suspend () -> T)

// User Agent
fun getRandomUserAgent(): String

// Parsing
fun extractEpisodeNumber(text: String): Int?
fun Element.extractImageAttr(): String
fun getQualityFromName(name: String?): Int
```

---

## ⚠️ Common Pitfalls

| Issue | Solution |
|-------|----------|
| Build gagal | `./gradlew clean` lalu `./gradlew make` |
| Provider tidak muncul | Cek `build.gradle.kts` - pastikan `status = 1` |
| Link tidak work | Cek selector di `loadLinks()` - website mungkin berubah |
| Timeout | Tambah timeout di `app.get(url, timeout = ...)` |

---

## 📋 CI/CD

```
git push → CI/CD trigger → Build → Push ke builds branch
```

- **Trigger:** Push ke `master` branch
- **Workflow:** `.github/workflows/ci-cd.yml`
- **Output:** `.cs3` files di branch `builds`

---

*Last Updated: 2026-05-06*