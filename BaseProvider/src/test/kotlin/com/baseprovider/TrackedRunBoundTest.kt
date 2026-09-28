package com.baseprovider

import com.baseprovider.log.SupabaseObservability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batas run yang dilacak per proses. endRun menghapus entry, tapi tidak ada
 * jaminan setiap path memanggilnya: pada cancellation endRun dipanggil di
 * dalam cabang pembuang, dan kalau `awaitRunCreated` menyerah, entry tidak
 * pernah dibuang. Tanpa batas, proses yang hidup lama menahan entry terus.
 */
class TrackedRunBoundTest {

    @Test
    fun `tidak ada yang di-evict saat masih di bawah batas`() {
        val touched = mapOf("a" to 100L, "b" to 200L)
        assertTrue(SupabaseObservability.evictVictims(touched, cap = 8, count = 4)
            .isEmpty())
    }

    @Test
    fun `tidak ada yang di-evict saat jumlah persis sama dengan batas`() {
        val touched = mapOf("a" to 100L, "b" to 200L, "c" to 300L)
        assertTrue(SupabaseObservability.evictVictims(touched, cap = 3, count = 2)
            .isEmpty())
    }

    @Test
    fun `evict dimulai dari run terlama tidak disentuh`() {
        val touched = linkedMapOf(
            "baru" to 500L, "lama" to 100L, "tengah" to 300L
        )
        assertEquals(listOf("lama"), SupabaseObservability.evictVictims(
            touched, cap = 2, count = 2))
    }

    @Test
    fun `jumlah yang di-evict dibatasi`() {
        val touched = (1..10).associateBy { "run$it" } { it * 100L }
        val victims = SupabaseObservability.evictVictims(touched, cap = 5, count = 3)
        assertEquals(3, victims.size)
        assertEquals(listOf("run1", "run2", "run3"), victims)
    }

    @Test
    fun `run yang sedang diproses tidak ikut ter-evict saat masih ada yang lebih tua`() {
        // run yang baru disentuh (witness tinggi) harus selamat supaya
        // endRun-nya tidak kehilangan statistik.
        val touched = linkedMapOf(
            "run1" to 100L, "run2" to 100L, "aktif" to 9999L
        )
        val victims = SupabaseObservability.evictVictims(touched, cap = 2, count = 2)
        assertTrue(victims.contains("run1"))
        assertTrue(victims.contains("run2"))
        assertTrue(!victims.contains("aktif"))
    }

    @Test
    fun `daftar kosong tidak menghasilkan apa pun`() {
        assertTrue(SupabaseObservability.evictVictims(emptyMap(), cap = 1, count = 1)
            .isEmpty())
    }
}
