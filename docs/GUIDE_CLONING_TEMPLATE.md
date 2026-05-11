# 🛠️ Panduan Cloning Templates Provider

Dokumen ini menjelaskan langkah-demi-langkah cara membuat provider baru menggunakan basis **Templates Provider**. Template ini sudah mendukung fitur modern seperti Multi-URL (Main, Series, Search) dan utilitas resilien secara native.

---

## Langkah 1: Duplikasi Folder
Salin folder `templatesProvider` dan beri nama sesuai provider baru Anda (gunakan format PascalCase, contoh: `LayarKaca21Copy`).

```bash
cp -r templatesProvider LayarKaca21Copy
```

---

## Langkah 2: Ganti Nama File (Penting)
Masuk ke dalam folder `LayarKaca21Copy/src/main/kotlin/com/templatesProvider/` dan ubah nama file-filenya agar unik. Hal ini penting untuk menghindari kebingungan saat pengembangan.

| Nama Lama | Nama Baru (Contoh) | Deskripsi |
|-----------|-----------|-----------|
| `Provider.kt` | `LayarKaca21.kt` | File utama logika scraping. |
| `ProviderPlugin.kt` | `LayarKaca21Plugin.kt` | File pendaftaran plugin ke Cloudstream. |
| `ProviderUtils.kt` | `LayarKaca21Utils.kt` | Fungsi pembantu (Rate limit, retry, dsb). |
| `ProviderEkstraktors.kt` | `LayarKaca21Ekstraktors.kt` | Logika ekstraksi video & Link Generator. |

---

## Langkah 3: Sesuaikan Package Name
Buka **SETIAP** file yang baru Anda ganti namanya di atas, lalu ubah baris pertama (deklarasi package) agar sesuai dengan folder baru.

**Contoh Perubahan:**
```kotlin
// DARI:
package com.templatesProvider

// MENJADI:
package com.LayarKaca21Copy
```

> ⚠️ **Penting:** Jika package name tidak sama di semua file dalam satu modul, project tidak akan bisa di-build.

---

## Langkah 4: Konfigurasi Metadata & URL
Buka file utama (`LayarKaca21.kt`) dan sesuaikan identitas provider Anda.

```kotlin
class LayarKaca21Copy : TemplatesProvider() { // Ganti nama class
    override var mainUrl = "https://lk21.de"        // URL Utama (Movie)
    override var seriesUrl = "https://series.lk21.de" // URL Khusus Series
    override var searchUrl = "https://gudangvape.com" // URL Khusus Search
    override var name = "LayarKaca21 Copy"          // Nama yang muncul di App
}
```

*Jika provider Anda hanya punya 1 URL untuk semuanya, cukup isi ketiga variabel tersebut dengan nilai yang sama.*

---

## Langkah 5: Pendaftaran Plugin
Buka file plugin (`LayarKaca21Plugin.kt`) dan pastikan kelas yang didaftarkan sesuai dengan nama kelas di Langkah 4.

```kotlin
@CloudstreamPlugin
class LayarKaca21Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(LayarKaca21Copy()) // Pastikan memanggil class yang benar
    }
}
```

---

## Langkah 6: Update Ekstraktor
Buka file `LayarKaca21Ekstraktors.kt`. Hapus isi daftar ekstraktor lama dan ganti dengan ekstraktor yang dibutuhkan oleh provider baru tersebut.

```kotlin
object LayarKaca21Ekstraktors {
    val list = listOf(
        // Masukkan kelas ekstraktor di sini
        Dailymotion(),
        Odnoklassniki()
    )
}
```

---

## Langkah 7: Verifikasi Build
Pastikan Gradle mengenali modul baru Anda dengan menjalankan perintah berikut:

```bash
./gradlew :LayarKaca21Copy:make
```

Jika muncul pesan `BUILD SUCCESSFUL`, selamat! Provider baru Anda sudah siap digunakan dan akan otomatis terbungkus dalam format `.cs3`.

---

## Tips Tambahan
1. **Multi-Selector:** Variabel seperti `SEARCH_ITEMS` di dalam `companion object` mendukung list. Jika provider baru menggunakan tag yang berbeda, cukup tambahkan ke dalam list tersebut tanpa menghapus yang lama.
2. **Utils:** Jangan memodifikasi `ProviderUtils.kt` kecuali Anda benar-benar butuh fungsi global baru. Fungsi yang ada sudah didesain sangat aman (*safe fallback*).
3. **Log:** Gunakan `logDebug(TAG, "pesan")` untuk mempermudah pelacakan error saat testing.
