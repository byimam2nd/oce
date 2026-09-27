package com.baseprovider

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Compile-and-test di CI berjalan di JVM modern, sehingga API Java 9+ yang
 * TIDAK ada di android.jar (dan core library desugaring tidak diaktifkan)
 * lolos build lalu meledak sebagai NoSuchMethodError di perangkat.
 *
 * Kasus nyata: AdaptiveHeaderProbe memakai
 * ByteArrayOutputStream.toString(Charset) (Java 10+) -> Rumble gagal 100%
 * dengan "No virtual method toString(Ljava/nio/charset/Charset;)".
 *
 * Test ini menjaga agar pola yang sama tidak kembali.
 */
class AndroidCompatApiTest {

    @Test
    fun `tidak ada API Java 9+ yang tidak tersedia di Android`() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue(
            "Folder sumber tidak ditemukan: ${sourceRoot.absolutePath}",
            sourceRoot.isDirectory
        )

        // Catatan: jangan masukkan ".repeat(" / ".lines()" ke daftar ini.
        // Nama itu sama dengan fungsi stdlib Kotlin (kotlin.text), yang AMAN di
        // Android; banned list hanya untuk API yang benar-benar tidak ada.
        val banned = listOf(
            "readAllBytes" to "java.io.InputStream.readAllBytes (Java 9)",
            "transferTo" to "java.io.InputStream.transferTo (Java 9)",
            "import java.util.Optional" to "java.util.Optional tidak ada di android.jar",
            ".strip()" to "String.strip (Java 11)",
            ".formatted(" to "String.formatted (Java 15)",
            "Files.readString" to "java.nio.file.Files.readString (Java 11)",
            "import java.nio.file" to "java.nio.file tidak ada di android.jar",
            "toString(Charsets" to "String/BAOS.toString(Charset) (Java 10+)"
        )

        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                banned.forEach { (needle, reason) ->
                    if (code.contains(needle)) {
                        val rel = file.relativeTo(sourceRoot).path
                        violations += "$rel:${index + 1}  $reason  ->  ${line.trim()}"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "API tidak kompatibel Android terdeteksi:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}
