package com.baseprovider.extractor

/**
 * Logika parsing payload ok.ru yang murni — tanpa dependency CloudStream
 * sehingga bisa diuji di JVM unit test.
 */
internal object OkRuPayload {

    val HLS_MANIFEST_RE = Regex(""""hlsManifestUrl":\s*"([^"]+)"""")

    /**
     * ok.ru meng-escape JSON payload di dalam HTML dengan HTML entity
     * (`&quot;hlsManifestUrl&quot;:&quot;...`), bukan hanya backslash escape.
     * Tanpa normalisasi entity, regex [HLS_MANIFEST_RE] tidak pernah match →
     * extractor keluar tanpa memanggil callback → 0 link dan host tercatat
     * "All extraction methods failed".
     */
    fun normalize(raw: String): String = raw
        .replace("\\&quot;", "\"")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("\\\\", "\\")

    /** URL HLS mentah (masih berisi escape `\u0026`) atau null bila tidak ada. */
    fun extractHlsManifest(raw: String): String? =
        HLS_MANIFEST_RE.find(normalize(raw))?.groupValues?.getOrNull(1)

    /**
     * true bila halaman masih memuat payload player (master HLS atau daftar
     * video).
     *
     * ok.ru menjawab video yang dihapus/tidak tersedia dengan halaman
     * "Плеер Видео" generik (~32KB) yang TIDAK memuat payload apa pun. Tanpa
     * cek ini, video mati dicatat sebagai "All extraction methods failed"
     * (FailureType.EXTRACTOR) sehingga terlihat seperti bug extractor kita,
     * padahal tidak ada yang bisa diperbaiki di sisi OCE.
     */
    fun hasPlayerPayload(raw: String): Boolean {
        val n = normalize(raw)
        return n.contains("hlsManifestUrl") || n.contains("\"videos\"")
    }
}
