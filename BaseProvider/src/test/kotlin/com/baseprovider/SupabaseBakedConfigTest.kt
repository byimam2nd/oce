package com.baseprovider

import com.baseprovider.log.SupabaseBakedConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant untuk nilai yang di-bake `generateSupabaseConfig` (build.gradle.kts)
 * dari OCE_VERSION. Kalau template Gradle-nya rusak, PLUGIN_VERSION bisa berisi
 * "null"/ruang kosong/"undefined" — log tetap terkirim, tapi setiap query
 * "build mana yang aktif?" diam-diam jadi salah. Jadi ini dikunci di sini.
 */
class SupabaseBakedConfigTest {

    @Test
    fun `plugin version is blank or positive integer string`() {
        val v = SupabaseBakedConfig.PLUGIN_VERSION
        // Build lokal tidak menyet OCE_VERSION → kosong, kolom dilewati.
        if (v.isBlank()) return
        // Build CI selalu epoch menit: digit saja, tanpa tanda/suffix.
        assertTrue(
            "PLUGIN_VERSION harus digit atau kosong, bukan: '$v'",
            v.all { it.isDigit() } && v.toLong() > 0L
        )
    }
}
