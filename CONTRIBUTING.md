# 🤝 Panduan Kontribusi: Membangun Masa Depan OCE

Terima kasih telah meluangkan waktu untuk berkontribusi! Di **Open Cloudstream Extensions (OCE)**, kami tidak hanya mengumpulkan kode; kami membangun ekosistem yang resilien, modular, dan berkualitas tinggi.

---

## 🌟 Visi Kontribusi Kami

Kami mengutamakan **kualitas di atas kuantitas**. Setiap kontribusi yang masuk harus selaras dengan prinsip utama kami:
1.  **Stabilitas:** Kode harus tahan terhadap perubahan dinamis website sumber.
2.  **Modularitas:** Perubahan logika tidak boleh merusak fungsionalitas lain (Single Responsibility).
3.  **Kebersihan:** Kode yang bersih adalah kode yang mudah dipelihara oleh siapa pun.

---

## 🏛️ Hukum Sentralisasi (Modular Blueprint)

Proyek OCE menggunakan arsitektur **Modular Blueprint** di mana logika inti dipisahkan secara ketat dari data spesifik situs.

### 1. Pengembangan Berbasis Blueprint
Seluruh logika inti berada di dalam direktori `BaseHtmlProvider/`. 
- **Aturan:** Anda **DILARANG** memodifikasi logika langsung pada folder modul provider (misal: `ProviderAnichin/`). Perubahan tersebut akan terhapus otomatis oleh sistem sinkronisasi.
- **Tindakan:** Lakukan pengembangan pada file template master:
    - `ProviderScrapper.kt`: Untuk alur scraping dan koneksi.
    - `ProviderMapper.kt`: Untuk transformasi elemen HTML ke data.
    - `ProviderCloudstream.kt`: Untuk konfigurasi adapter Cloudstream.
    - `ProviderUtils.kt`: Untuk fungsi utilitas global.

### 2. Abstraksi Data (HTML Constants)
Logika mesin dirancang untuk menjadi *Site-Agnostic*.
- **Aturan:** Semua selektor CSS, pola Regex, dan ID eksternal harus didefinisikan di dalam `ProviderHTMLConstants.kt` menggunakan sistem **Owner Tagging**.
- **Tindakan:** Gunakan format `ProviderID:::Selector` untuk isolasi konfigurasi per provider.

### 3. Skalabilitas Global
Setiap baris kode di blueprint harus mampu menangani puluhan situs secara bersamaan. Hindari solusi "Quick Fix" yang hanya bekerja untuk satu situs namun merusak stabilitas global.

---

## 🚀 Alur Kontribusi (Workflow)

1.  **Issue:** Diskusikan fitur atau bug di kolom Issue sebelum memulai.
2.  **Environment:** Gunakan JDK 17+ dan Android SDK API 35.
3.  **Development:** Lakukan perubahan di `BaseHtmlProvider/`.
4.  **Sync:** Jalankan `python3 scripts/sync_providers.py` untuk menyebarkan perubahan ke seluruh modul.
5.  **Build:** Pastikan `./gradlew make` berhasil tanpa error kompilasi.

---

## 📝 Standar Komunikasi (Commits)

| Tipe | Deskripsi |
| :--- | :--- |
| `feat` | Penambahan fitur atau provider baru. |
| `fix` | Perbaikan bug atau penyesuaian selektor. |
| `refactor` | Perubahan kode tanpa mengubah fungsi utama (modularisasi). |
| `chore` | Sinkronisasi, pembaruan dokumentasi, atau CI/CD. |

---

## 📜 Kode Etik & Lisensi

- **Profesionalisme:** Berkomunikasilah dengan profesional di kolom Issue dan PR.
- **Lisensi:** Seluruh kontribusi dilisensikan di bawah **GNU GPLv3**.

---
<div align="center">
  <b>Terima kasih telah membantu membangun hiburan yang lebih baik.</b>
</div>
