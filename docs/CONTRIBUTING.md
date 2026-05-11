# 🤝 Contributing to OCE

Terima kasih telah berkontribusi ke OCE (Open CloudStream Extensions)!

---

## 📋 Getting Started

### Prerequisites
- **JDK 17** atau lebih tinggi
- **Android SDK** (API 35)
- **Git**

### Setup
```bash
# Clone repository
git clone https://github.com/byimam2nd/oce.git
cd oce

# Build semua provider
./gradlew make

# Build provider spesifik
./gradlew :Anichin:make
```

---

## 📁 Project Structure

```
oce/
├── {Provider}/                    # Provider modules
│   ├── build.gradle.kts            # Konfigurasi plugin
│   └── src/main/kotlin/com/{Provider}/
│       ├── {Provider}.kt          # Main API implementation
│       ├── {Provider}Plugin.kt    # Plugin registration
│       ├── {Provider}Utils.kt     # Utilities (opsional)
│       └── {Provider}Ekstraktors.kt  # Extractors (opsional)
│
├── build.gradle.kts               # Root build config
├── gradle.properties              # Gradle properties
├── docs/                         # Dokumentasi
└── .github/workflows/            # CI/CD
```

---

## 🔧 Development

### Edit Provider Files
Setiap provider berdiri sendiri. Edit langsung di folder provider:
```bash
# Edit Anichin.kt
vim Anichin/src/main/kotlin/com/Anichin/Anichin.kt
```

### Build & Test
```bash
# Build provider spesifik
./gradlew :Anichin:make

# Atau build semua
./gradlew make
```

---

## 📝 Commit Guidelines

Gunakan [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <description>

[optional body]
```

**Types:**
- `feat:` - Fitur baru
- `fix:` - Bug fix
- `refactor:` - Refactoring
- `docs:` - Dokumentasi
- `chore:` - Maintenance

**Examples:**
```
feat: add new provider for anime streaming
fix: resolve null pointer in loadLinks
docs: update quick reference guide
chore: clean up obsolete files
```

---

## ✅ Before Submitting

1. Build berhasil: `./gradlew make`
2. Tidak ada error di kode
3. Dokumentasi sudah sesuai (jika ada perubahan)

---

## 📚 Dokumentasi

Lihat folder `docs/` untuk panduan lengkap:
- [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) - Struktur folder
- [DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md) - Panduan pengembangan
- [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - Referensi cepat

---

**Last Updated:** 2026-05-06