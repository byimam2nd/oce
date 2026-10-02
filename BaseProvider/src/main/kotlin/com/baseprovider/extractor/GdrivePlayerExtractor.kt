package com.baseprovider.extractor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64

/**
 * Logika murni gdriveplayer.to, dipisah dari network agar bisa diuji.
 *
 * Halaman `embed2.php` tidak pernah memuat media URL di markup. Yang ada:
 *   <script>(function(){var k="<kunci>"; ... atob("<payload>") ... })()</script>
 * `payload` di-base64-decode lalu di-XOR dengan `k` berulang (siklis,
 * offset 0) → JS yang berisi konfigurasi player. Di dalam JS itu:
 *   HLS="hlsplaylist.php?s=...&idhls=....m3u8"
 *   SUB="subproxy.php?u=<base64>&t=<token>"
 *
 * M3U8 yang di-host site adalah playlist HLS asli (93 segmen terverifikasi,
 * segmen `.ts` di host terpisah `stream121.space`) — bukan redirect.
 */
internal object GdrivePlayerPayload {

    private val KEY = Regex("""var\s+k\s*=\s*"([^"]+)"""")
    private val PAYLOAD = Regex("""atob\(\s*"([^"]+)"\s*\)""")
    private val HLS = Regex("""\bHLS\s*=\s*"([^"]+)\"""")
    private val SUB = Regex("""\bSUB\s*=\s*"([^"]+)\"""")

    /**
     * Dekode payload XOR. Satu pass: kunci diterapkan cycling per index.
     * Mengembalikan '' bila kunci/payload tidak ada atau hasil decode
     * tidak mengandung bytecode JS yang masuk akal (guard typo/halaman
     * error, supaya extractor keluar diam-diam daripada emit link sampah).
     */
    fun decodeJs(html: String): String {
        val key = KEY.find(html)?.groupValues?.get(1)?.toByteArray(Charsets.ISO_8859_1)
            ?: return ""
        val b64 = PAYLOAD.find(html)?.groupValues?.get(1) ?: return ""
        val raw = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return ""
        val out = ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val js = String(out, Charsets.UTF_8)
        return if (js.contains("HLS=") || js.contains("hlsplaylist")) js else ""
    }

    /** URL absolut untuk HLS/subtitle, relatif terhadap mainUrl. */
    fun hlsUrl(js: String, mainUrl: String): String {
        val v = HLS.find(js)?.groupValues?.get(1)?.trim().orEmpty()
        return if (v.isBlank()) "" else v.toAbsolute(mainUrl)
    }

    fun subtitleUrl(js: String, mainUrl: String): String? {
        val v = SUB.find(js)?.groupValues?.get(1)?.trim().orEmpty()
        return if (v.isBlank()) null else v.toAbsolute(mainUrl)
    }

    private fun String.toAbsolute(base: String): String =
        if (startsWith("http")) this else base.trimEnd('/') + "/" + trimStart('/')
}

class GdrivePlayer : ExtractorApi() {
    override var name = "GdrivePlayer"
    override var mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.contains("gdriveplayer.to")) return
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val js = GdrivePlayerPayload.decodeJs(response.text)
        if (js.isBlank()) return

        GdrivePlayerPayload.subtitleUrl(js, mainUrl)?.let { subtitleCallback(SubtitleFile("English", it)) }

        val hls = GdrivePlayerPayload.hlsUrl(js, mainUrl)
        if (hls.isBlank()) return
        MasterLinkGenerator.createSmartLink(
            this.name, hls, "$mainUrl/",
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback
        )
    }
}
