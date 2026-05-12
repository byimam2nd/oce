# 🧠 Deep Learning Guide | Panduan Pembelajaran Mendalam

[**🇺🇸 English**](#-english) | [**🇮🇩 Bahasa Indonesia**](#-bahasa-indonesia)

---

## 🇺🇸 English

Welcome to the heart of **OCE**. This guide helps you understand the architecture and logic of our system.

### 🗺️ Project Roadmap
To understand OCE, view it as a **Factory (Blueprint)** and **Products (Provider Modules)**.
- **Blueprint (`BaseHtmlProvider/`):** The core engine. One fix here applies to all providers.
- **Nerve Center (`ProviderConstants.kt`):** Stores site-specific data using `ProviderID:::Value` tagging.
- **Harvester (`ProviderEkstraktors.kt`):** Harvests video links using deep scanning pipelines.

### 🔬 Detailed Code Analysis
1.  **MainAPI Lifecycle:** Review `Provider.kt` functions: `getMainPage()`, `search()`, `load()`, and `loadLinks()`.
2.  **Performance Engine:** Study `SmartThrottle` and `globalHtmlCache` in `ProviderUtils.kt`.

---

## 🇮🇩 Bahasa Indonesia

Selamat datang di jantung **Open Cloudstream Extensions (OCE)**.

### 🗺️ Peta Navigasi Proyek
Pahami OCE sebagai sebuah **Pabrik (Blueprint)** dan **Produk (Provider Modules)**.
- **Blueprint (`BaseHtmlProvider/`):** Pusat logika utama. Perbaikan di sini akan tersebar ke seluruh provider.
- **Hub Konfigurasi (`ProviderConstants.kt`):** Pusat data spesifik situs menggunakan sistem *Owner Tagging*.
- **Mesin Ekstraksi (`ProviderEkstraktors.kt`):** Logika memanen link video dari berbagai host.

### 🔬 Cara Mempelajari Kode
1.  **Lifecycle MainAPI:** Buka `Provider.kt` dan pelajari alur fungsi utama.
2.  **Mesin Performa:** Pelajari sistem caching dan throttling di `ProviderUtils.kt`.

---
*Logic + Data = Extension (.cs3)*
