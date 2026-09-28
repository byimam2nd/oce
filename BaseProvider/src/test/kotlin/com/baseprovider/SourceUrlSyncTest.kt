package com.baseprovider

import com.baseprovider.log.SupabaseObservability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlSyncTest {

    @Test
    fun `URL sama persis bukan drift`() {
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe", "https://anichin.moe"))
    }

    @Test
    fun `garis miring di akhir bukan drift`() {
        // Config dan DB bisa berbeda trailing slash tanpa ART yang salah.
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe/", "https://anichin.moe"))
    }

    @Test
    fun `host berbeda adalah drift`() {
        assertTrue(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.cafe", "https://anichin.moe"))
    }

    @Test
    fun `main_url kosong di DB bukan drift`() {
        // main_url nullable. Kasus ini ditangani beginRun (insert), bukan
        // self-heal PATCH, jadi tidak boleh dianggap drift di sini.
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            null, "https://anichin.moe"))
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "   ", "https://anichin.moe"))
    }

    @Test
    fun `skema berbeda adalah drift`() {
        // Pindah http -> https adalah perubahan nyata, bukan gaya penulisan.
        assertTrue(SupabaseObservability.sourceUrlDrifted(
            "http://anichin.moe", "https://anichin.moe"))
    }

    @Test
    fun `huruf besar pada skema dan host bukan drift`() {
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "HTTPS://ANICHIN.MOE", "https://anichin.moe"))
    }

    @Test
    fun `port default tidak dibedakan dari tanpa port`() {
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe:443", "https://anichin.moe"))
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "http://anichin.moe:80", "http://anichin.moe"))
    }

    @Test
    fun `port non-default tetap dianggap drift`() {
        assertTrue(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe:8443", "https://anichin.moe"))
    }

    @Test
    fun `path berbeda adalah drift`() {
        assertTrue(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe/film", "https://anichin.moe"))
    }

    @Test
    fun `config kosong tidak memicu PATCH`() {
        // Tidak ada sumber kebenaran untuk menyinkronkan; jangan menulis apa pun.
        assertFalse(SupabaseObservability.sourceUrlDrifted(
            "https://anichin.moe", ""))
    }
}
