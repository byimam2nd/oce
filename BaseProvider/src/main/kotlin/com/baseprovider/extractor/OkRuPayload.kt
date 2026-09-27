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
}
