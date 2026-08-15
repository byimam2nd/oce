package com.baseprovider.core

/**
 * Adaptive poster/thumbnail resizing via URL rewrite (on-the-fly image proxy).
 *
 * Prinsip: poster di home/search grid & thumbnail episode ditampilkan kecil
 * (~342px), jadi mengirim full-res (780px+) membuang bandwidth. Kita rewrite
 * URL ke versi kecil + format efisien (AVIF/WebP) yang TAMPIL IDENTIK di
 * ukuran card, tapi ukuran file jauh lebih kecil → fetching lebih cepat.
 *
 * Template diisi per-provider lewat config `posterResizeUrl`, contoh:
 *   https://images.weserv.nl/?url={url}&w=342&output=avif,webp
 *
 * Failover/adaptive:
 * - Template kosong / tanpa token `{url}` → URL asli (fitur mati, backward-compatible).
 * - URL null/blank → null (tidak merusak mapping).
 * - Tidak di-resize: detail page hero poster & banner (ditampilkan besar,
 *   resize akan menurunkan kualitas yang terlihat) — lihat pemakaian di ProviderMapper.
 *
 * Catatan: plugin tidak bisa mendeteksi gagal-load gambar (itu di app/Coil),
 * jadi failover jaringan tidak bisa dari sisi ini — gate per-provider adalah
 * mekanisme failover: provider dengan CDN hotlink-protected set template kosong.
 */
object PosterResizer {

    // URL yang sudah lewat proxy resize / CDN optimizer — rewrite ulang hanya
    // akan double-compress (turunkan kualitas) tanpa manfaat tambahan.
    private val RESIZE_QUERY_REGEX = Regex(
        """[?&](?:w|width|resize|s|size)=\d+""",
        RegexOption.IGNORE_CASE
    )

    fun resize(url: String?, template: String): String? {
        if (url.isNullOrBlank()) return url
        if (template.isBlank() || !template.contains("{url}")) return url
        if (alreadyResized(url)) return url
        val encoded = runCatching {
            java.net.URLEncoder.encode(url, "UTF-8").replace("+", "%20")
        }.getOrDefault(url)
        return template.replace("{url}", encoded)
    }

    /**
     * Deteksi URL yang sudah berukuran/teroptimasi sehingga tidak perlu
     * di-rewrite ulang: mengandung query resize (mis. `?w=342`) atau `?s=`,
     * `?size=`. CDN yang sudah menerima parameter ukuran langsung menurunkan
     * kualitas tanpa perlu proxy tambahan.
     */
    private fun alreadyResized(url: String): Boolean =
        RESIZE_QUERY_REGEX.containsMatchIn(url)
}
