# 🧠 Panduan Pembelajaran Mendalam Proyek OCE

Selamat datang di jantung **Open Cloudstream Extensions (OCE)**. Dokumen ini dirancang untuk membantu Anda memahami arsitektur, alur kerja, dan logika sistem kami secara mendetail dari tingkat tinggi hingga ke baris kode.

---

## 🗺️ Peta Navigasi Proyek

Untuk memahami OCE, Anda harus melihatnya sebagai sebuah **Pabrik (Blueprint)** dan **Produk (Provider Modules)**.

### 1. Arsitektur Sentralisasi (The Blueprint)
Inti dari proyek ini bukan berada di folder provider masing-masing, melainkan di folder:
📂 **`BaseHtmlProvider/`**

Ini adalah "Blueprint" atau cetakan utama. Mengapa ini penting?
- **Efisiensi:** Jika ada bug pada mesin pencarian, kita hanya perlu memperbaikinya di sini satu kali, dan perbaikan akan tersebar ke semua provider.
- **Konsistensi:** Seluruh provider akan memiliki perilaku dan performa yang sama karena menggunakan logika yang identik.

### 2. Hub Konfigurasi (The Nerve Center)
📂 **`BaseHtmlProvider/ProviderConstants.kt`**

Jika `BaseHtmlProvider` adalah ototnya, maka `ProviderConstants` adalah sistem sarafnya. Di sinilah semua data spesifik situs disimpan:
- **Owner Tagging:** Perhatikan pola `ProviderID:::Value` (Contoh: `Anichin:::https://anichin.cafe`). Ini memungkinkan satu variabel digunakan oleh banyak provider dengan nilai yang berbeda.
- **CSS Selectors:** Jika sebuah situs mengubah tampilannya, Anda hanya perlu memperbarui selektor CSS di sini.

### 3. Mesin Ekstraksi (The Harvester)
📂 **`BaseHtmlProvider/ProviderEkstraktors.kt`**

File ini berisi logika untuk "memanen" link video dari berbagai server hosting.
- **Deep Scanning:** Memahami bagaimana sistem mencari link video secara otomatis di dalam kode HTML yang kompleks.
- **Extractor Registry:** Memahami daftar extractor yang didukung secara lokal.

---

## ⚙️ Alur Kerja Teknis (The Engine Room)

### 1. Proses Sinkronisasi
Sistem kami menggunakan skrip otomatis untuk menyalin kode dari Blueprint ke Provider.
- **Skrip:** `scripts/sync_providers.py`
- **Logika:** Skrip ini membaca file di `BaseHtmlProvider`, mengubah nama package, dan mengganti nama class sesuai dengan target provider (misal: `TemplatesProvider` menjadi `Anichin`).
- **Aturan Emas:** Jangan pernah mengedit folder `ProviderAnichin/` dsb secara langsung, karena perubahan Anda akan tertimpa saat sinkronisasi berjalan.

### 2. CI/CD & Distribusi
Bagaimana kode Anda sampai ke tangan pengguna?
- **Jalur Beta:** Setiap kali Anda push ke branch `master`, workflow `.github/workflows/ci-cd.yml` akan mem-build plugin dan mengirimkannya ke branch `builds`.
- **Jalur Stable:** Saat Anda membuat Tag Git (misal `v1.0.0`), workflow `release.yml` akan membuat rilis resmi di GitHub.

---

## 🔬 Cara Mempelajari Kode Secara Detail

### Langkah 1: Pahami MainAPI Lifecycle
Buka `BaseHtmlProvider/Provider.kt` dan pelajari fungsi-fungsi ini secara berurutan:
1.  `getMainPage()`: Bagaimana data halaman depan diambil dan diproses.
2.  `search()`: Logika pencarian (termasuk pencarian berbasis JSON).
3.  `load()`: Bagaimana metadata (judul, poster, episode) diekstrak secara rekursif.
4.  `loadLinks()`: Tahap akhir dimana link video diambil menggunakan pipeline ekstraktor.

### Langkah 2: Pelajari Pipeline Metadata
Lihat fungsi `extractMetadata` di `Provider.kt`. Perhatikan bagaimana sistem mencoba mengambil data dari berbagai tempat (meta tags, info box, dsb) secara fleksibel.

### Langkah 3: Pelajari Sistem Ekstraksi
Buka `ProviderEkstraktors.kt` dan perhatikan class `MasterLinkGenerator`. Pahami bagaimana kualitas video dideteksi secara otomatis dari pola URL.

---

## 🛠️ Alat Bantu Pengembangan

- **Gradle:** Gunakan `./gradlew make` untuk membangun seluruh proyek secara lokal.
- **Logcat:** Saat pengujian di Android, gunakan tag `[ProviderName]` untuk memantau aktivitas provider Anda.
- **Python:** Pahami skrip sinkronisasi jika Anda ingin menambahkan provider baru yang berbasis blueprint yang sama.

---

## 📚 Kesimpulan

Proyek OCE bukan sekadar koleksi script, melainkan sebuah **Sistem Manajemen Ekstensi**. Kunci utama pembelajarannya adalah memahami bahwa **Logika (BaseHtmlProvider) + Data (Constants) = Extension (.cs3)**.

Jika Anda memahami `BaseHtmlProvider`, Anda telah menguasai 90% dari seluruh proyek ini.

---
*Dokumentasi ini dibuat untuk memastikan setiap kontributor baru dapat memahami kompleksitas OCE dengan cepat dan tepat.*
