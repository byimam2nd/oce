package com.baseprovider.extractor

import com.baseprovider.config.ExtractorConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ConfigDrivenCryptoTest {

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)
            .replace('+', '-').replace('/', '_').trimEnd('=')

    private fun newCde(): ConfigDrivenExtractor =
        ConfigDrivenExtractor(
            ExtractorConfig(id = "Crypto", mainUrl = "https://crypto.test")
        )

    // ── AES-GCM decrypt (ByseSX/Cloudhownetwork key-parts) ──

    @Test
    fun `decryptAesGcm roundtrips encrypted payload`() {
        val cde = newCde()
        val plaintext = "https://cdn.example/seg/001.ts"
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val iv = "123456789012".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Key dipecah jadi 2 parts (b64url) — sama seperti alur produksi.
        val keyParts = listOf(
            b64UrlEncode(key.copyOfRange(0, 16)),
            b64UrlEncode(key.copyOfRange(16, 32))
        )
        val decrypted = cde.decryptAesGcm(
            keyParts,
            b64UrlEncode(iv),
            b64UrlEncode(encrypted)
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `decryptAesGcm returns empty on bad key`() {
        val cde = newCde()
        val result = cde.decryptAesGcm(
            listOf(b64UrlEncode("wrong".toByteArray(Charsets.UTF_8))),
            b64UrlEncode("123456789012".toByteArray(Charsets.UTF_8)),
            b64UrlEncode("deadbeef".toByteArray(Charsets.UTF_8))
        )
        assertEquals("", result)
    }

    @Test
    fun `decryptAesGcm returns empty on blank parts`() {
        val cde = newCde()
        assertEquals("", cde.decryptAesGcm(emptyList(), "", ""))
    }

    // ── sigDecode (Vidguardto URL signature) ──

    @Test
    fun `sigDecode decodes validated signature`() {
        val cde = newCde()
        // Vektor di-generate dari inverse transform — round-trip terbukti
        // menghasilkan "playbackstreamurl.m3u8".
        val rawSig = "4f474f7a535748334d45327860413733616f4476616f547866454c705b6f446a675a407155446e634d5669"
        val url = "https://stream.example/v.mp4?sig=$rawSig&token=xyz"
        val decoded = cde.sigDecode(url)
        assertTrue("harus mengandung sig ter-decode", decoded.contains("playbackstreamurl.m3u8"))
        assertFalse("sig raw tidak boleh tersisa", decoded.contains(rawSig))
    }

    @Test
    fun `sigDecode returns url unchanged when no sig param`() {
        val cde = newCde()
        val url = "https://stream.example/v.mp4?token=xyz"
        assertEquals(url, cde.sigDecode(url))
    }

    // ── runRhino (JS eval untuk payload ter-obfuscate) ──

    @Test
    fun `runRhino extracts object via JSON stringify`() = runBlocking {
        val cde = newCde()
        val js = "var result = {url: 'https://h.test/v.mp4', label: '720p'};"
        val out = cde.runRhino(js, "result")
        assertTrue("harus berisi url yang di-set", out.contains("v.mp4"))
        assertTrue("harus berisi label", out.contains("720p"))
    }

    @Test
    fun `runRhino eval expression string`() = runBlocking {
        val cde = newCde()
        val out = cde.runRhino("var n = 'oke-' + (2 + 3);", "n")
        assertEquals("oke-5", out)
    }

    @Test
    fun `runRhino returns empty on invalid js`() = runBlocking {
        val cde = newCde()
        assertEquals("", cde.runRhino("var { broken", "x"))
    }
}