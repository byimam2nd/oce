package com.baseprovider.config

/**
 * Konfigurasi extractor berbasis JSON (pola sama seperti [ProviderConfig]).
 *
 * Satu file JSON = satu extractor. Extractors tidak lagi wajib menulis class
 * Kotlin baru: engine [com.baseprovider.extractor.ConfigDrivenExtractor]
 * mengeksekusi [steps] secara berurutan dengan semua metode ekstraksi yang
 * tersedia (fetch / post / regex / jsonPath / constructUrl / substring).
 *
 * [variants] memungkinkan SATU extractor punya beberapa strategi header
 * sebagai fallback: engine mencoba variant pertama; jika tidak menghasilkan
 * link, lanjut ke variant berikutnya sampai ada hasil.
 */
data class ExtractorConfig(
    val id: String,

    // ── Identity ──
    val name: String = id,
    val mainUrl: String = "https://example.com",
    val requiresReferer: Boolean = true,
    // true = request cache (CachedExtractorApi) untuk GET/POST form/JSON.
    val cached: Boolean = false,

    // ── ID extraction dari URL masuk ──
    val idSource: IdSource? = null,

    // ── Varian header strategy (fallback berurutan) ──
    val variants: List<ExtractorVariant> = listOf(ExtractorVariant()),

    // ── Pipeline eksekusi ──
    val steps: List<ExtractorStep> = emptyList(),

    // ── Output delivery ──
    // Template referer untuk link video (bisa berisi {mainUrl} / {url} / {id}).
    // Kosong = null (probe otomatis AdaptiveHeaderProbe menentukan sendiri).
    val videoReferer: String = "",
    // "adaptive" (prioritaskan HLS master) | "master" (filterMasterM3u8) | "none"
    val outputFilter: String = "adaptive",
) {
    init { validate() }

    private fun validate() {
        val errors = mutableListOf<String>()
        if (mainUrl.isBlank() || !mainUrl.startsWith("http"))
            errors += "mainUrl must be a valid http URL"
        if (variants.isEmpty()) errors += "variants must not be empty"
        if (steps.isEmpty()) errors += "steps must not be empty"
        if (outputFilter !in listOf("adaptive", "master", "none"))
            errors += "invalid outputFilter: $outputFilter"
        if (errors.isNotEmpty()) {
            com.lagradost.api.Log.w("ExtractorConfig[$id]", "Validation:\n  ${errors.joinToString("\n  ")}")
        }
    }
}

/** Sumber id dari URL masuk (untuk placeholder {id}). */
data class IdSource(
    val type: String,          // "query" | "path" | "regex" | "css" | "none"
    val param: String = "",    // untuk "query": nama query param
    val pattern: String = "",  // untuk "regex": pattern dengan capture group
    val group: Int = 1,        // untuk "regex": nomor capture group
    val selector: String = "", // untuk "css": selector elemen
    val attr: String = "src",  // untuk "css": atribut
)

/** Satu strategi header untuk request halaman/API extractor. */
data class ExtractorVariant(
    val name: String = "default",
    val headers: Map<String, String> = emptyMap(),
    // Template referer untuk request page/API. Kosong = pakai referer caller
    // (atau mainUrl bila caller tidak punya). Bisa berisi {mainUrl} / {url}.
    val referer: String = "",
    // Override User-Agent untuk request page/API (kosong = default engine).
    val userAgent: String = "",
)

/**
 * Satu langkah pipeline ekstraksi. [ConfigDrivenExtractor] punya handler
 * untuk setiap step type; menambah jenis langkah baru = menambah handler,
 * bukan menulis extractor baru.
 */
sealed class ExtractorStep {

    /** GET halaman/API. Hasil disimpan ke variabel [store]. */
    data class Fetch(
        val url: String,                 // template, biasanya "{url}"
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
    ) : ExtractorStep()

    /** POST form-encoded. Hasil disimpan ke variabel [store]. */
    data class PostForm(
        val url: String,                 // template
        val data: Map<String, String>,   // template value (bisa {id})
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
    ) : ExtractorStep()

    /** POST body JSON mentah. Hasil disimpan ke variabel [store]. */
    data class PostJson(
        val url: String,
        val jsonBody: String,
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
    ) : ExtractorStep()

    /** Ekstrak URL video dari teks di variabel [source]. */
    data class Regex(
        val pattern: String,
        val group: Int = 1,
        val source: String = "response",
        // Filter: hasil harus mengandung substring ini (domain/dll). Kosong = semua.
        val filter: String = "",
        // true = pakai pola universal (mp4|m3u8|mkv|mpd|webm|ts|mov).
        val universal: Boolean = false,
        // Decode unicode escapes (\uXXXX) pada hasil (pola OkRu).
        val decodeUnicode: Boolean = false,
        // Jika diisi: simpan match pertama (setelah filter) ke variabel, bukan
        // jadi URL video. Berguna untuk menangkap token/id/payload untuk step
        // berikutnya (pola Gdplayer kaken, AbyssPlayer encrypted, MegaPlay id).
        val store: String = "",
    ) : ExtractorStep()

    /** Ambil URL dari JSON (mis. "file", "videoSource", "sources[0].file"). */
    data class JsonPath(
        val path: String,
        val source: String = "response",
        // Jika diisi: simpan nilai pertama (String) ke variabel, bukan emit.
        val store: String = "",
    ) : ExtractorStep()

    /** Bangun URL video langsung dari template (pola AnichinStream). */
    data class ConstructUrl(
        val template: String,            // mis. "{mainUrl}/hls/{id}.m3u8"
        // Jika diisi: simpan hasil ke variabel (bukan emit URL). Berguna untuk
        // membangun URL API menengah (pola Dailymotion/Gdplayer).
        val store: String = "",
    ) : ExtractorStep()

    /** Ambil URL di antara dua marker (pola EmTurbovid `var urlPlay = '...'`). */
    data class Substring(
        val startMarker: String,
        val endMarker: String,
        val source: String = "response",
        // Jika diisi: simpan hasil ke variabel, bukan emit URL.
        val store: String = "",
    ) : ExtractorStep()

    /** Resolve URL relatif/`//`/`/path` terhadap base (pola Xtwap, PlayCdn). */
    data class ResolveUrl(
        val base: String = "{url}",      // template base, mis. "{url}" atau "{mainUrl}"
        // Sumber URL yang di-resolve: variabel berisi URL yang akan di-resolve.
        val source: String = "",
    ) : ExtractorStep()
}