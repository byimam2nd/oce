# 🧠 Panduan Pembelajaran Arsitektur Modular OCE

Dokumen ini menjelaskan struktur internal **Arsitektur Modular V2.2.0** yang digunakan dalam proyek OCE untuk memastikan skalabilitas dan kemudahan pemeliharaan.

---

## 🗺️ Peta Navigasi Modular

Sistem kami memecah tanggung jawab besar menjadi komponen-komponen kecil yang terisolasi di dalam folder:
📂 **`BaseHtmlProvider/`**

### 1. Scrapper (Logika Koneksi & Alur)
📄 **`ProviderScrapper.kt`**
- **Tanggung Jawab:** Mengelola *request* HTTP, orkestrasi pencarian, pemuatan halaman, dan penanganan tautan (*loadLinks*).
- **Karakteristik:** Berisi alur kerja algoritmik. Tidak mengandung selector CSS secara langsung.

### 2. Mapper (Logika Transformasi Data)
📄 **`ProviderMapper.kt`**
- **Tanggung Jawab:** Mengubah elemen HTML mentah (`Jsoup Element`) menjadi objek data Cloudstream (`SearchResponse`, `Episode`, dll).
- **Karakteristik:** Fokus pada parsing data dan pembersihan teks (termasuk *deduplication*).

### 3. Cloudstream Adapter (Jembatan API)
📄 **`ProviderCloudstream.kt`**
- **Tanggung Jawab:** Implementasi `MainAPI`. Bertindak sebagai pusat konfigurasi provider (nama, URL, tipe konten).
- **Karakteristik:** Sangat tipis. Hanya mendelegasikan panggilan ke `Scrapper`.

### 4. HTML Constants (Pusat Kontrol)
📄 **`ProviderHTMLConstants.kt`**
- **Tanggung Jawab:** Menyimpan seluruh selector CSS, pola Regex, dan metadata spesifik provider.
- **Sistem:** Menggunakan **Owner Tagging** (`ProviderID:::Value`) untuk memungkinkan ribuan konfigurasi dalam satu tempat.

### 5. Ekstraktor & Utilitas
📄 **`ProviderEkstraktors.kt`**: Logika untuk memanen link video langsung dari host (OkRu, Dailymotion, dll).
📄 **`ProviderUtils.kt`**: Fungsi pembantu global seperti pembersih judul, penanganan kualitas, dan *logging*.

---

## ⚙️ Prinsip Kerja (The Engine Room)

### 1. Single Responsibility Principle (SRP)
Setiap file hanya memiliki satu alasan untuk berubah. Jika ada masalah pada tampilan judul, Anda hanya perlu melihat `ProviderMapper`. Jika ada masalah pada koneksi, periksa `ProviderScrapper`.

### 2. Site-Agnostic Engine
Logika inti tidak pernah mengetahui alamat website yang sedang diproses. Seluruh data tersebut disuntikkan melalui `Constants` atau properti di `Adapter`.

### 3. Automated Synchronization
Jangan pernah menyentuh folder `ProviderAnichin/`, `ProviderAnimasu/`, dll. Folder tersebut adalah hasil "cetakan" otomatis dari skrip `scripts/sync_providers.py`. Seluruh pengembangan WAJIB dilakukan di folder `BaseHtmlProvider/`.

---

## 🔬 Cara Mempelajari Pipeline Data

1.  **Start:** `ProviderCloudstream.search()` dipanggil oleh Cloudstream.
2.  **Execution:** Adapter memanggil `scrapper.search()`.
3.  **Extraction:** Scrapper mengambil HTML dan memanggil `mapper.toSearchResult()`.
4.  **Mapping:** Mapper mengambil data menggunakan selector dari `HTMLConstants` dan melakukan pembersihan teks.
5.  **Return:** Data bersih dikembalikan ke UI aplikasi.

---
*Dokumentasi ini memastikan setiap pengembang dapat berkontribusi pada arsitektur yang stabil dan profesional.*
