package com.baseprovider.model

/**
 * Validasi tipe hasil ekstraksi selector (rule-based, deterministik).
 *
 * Bukan ML/LLM — ini aturan eksplisit untuk menilai "apakah hasil ini
 * bentuknya benar?". Dipakai oleh [SelectorResolver] dalam loop
 * self-correction: selector yang menghasilkan nilai invalid ditolak dan
 * diganti fallback berikutnya.
 */
enum class FieldType {
    TITLE,
    POSTER,
    URL,
    EPISODE_TEXT,
    DESC
}

object SelectorValidator {
    private val URL_PREFIX = Regex("""^(https?:)?//""")
    private val DATA_IMAGE = Regex("""(?i)^data:image/""")
    private val EPISODE_TOKEN = Regex("""(?i)(?:episode|ep|eps)\s*\d+|(?:eps?\.?)""")

    // L4: regex dibebankan ke satu instance file-level, bukan dibuat ulang
    // tiap pemanggilan isValidTitle (yang bisa ribuan kali per scan).
    private val WHITESPACE_SPLIT = Regex("\\s+")
    private val SENTENCE_MARKS = charArrayOf('.', '!', '?', ';')

    /** Nilai kosong/blank selalu ditolak untuk semua tipe. */
    fun isValid(type: FieldType, value: String?): Boolean {
        val v = value?.trim() ?: return false
        if (v.isBlank()) return false
        val ok = when (type) {
            FieldType.TITLE -> isValidTitle(v)
            FieldType.POSTER -> isValidPoster(v)
            FieldType.URL -> isValidUrl(v)
            FieldType.EPISODE_TEXT -> isValidEpisodeText(v)
            FieldType.DESC -> v.length >= 30
        }
        if (!ok) {
            com.baseprovider.log.logDebug("SelectorValidator",
                "REJECT type=$type len=${v.length}: ${v.take(40)}")
        }
        return ok
    }

    /**
     * Judul: panjang wajar (3..250), bukan paragraf. Menolak hasil yang
     * jelas berupa deskripsi/teks panjang bertanda kalimat banyak.
     */
    private fun isValidTitle(v: String): Boolean {
        if (v.length !in 3..250) return false
        if (v.contains('\n')) return false
        val marks = v.count { it in SENTENCE_MARKS }
        if (v.length > 40 && marks >= 3) return false
        if (v.split(WHITESPACE_SPLIT).size > 45) return false
        return true
    }

    /**
     * Poster: harus URL (http(s):// atau //) tanpa spasi. Teks deskripsi
     * yang bukan URL otomatis ditolak. Ekstensi gambar tidak wajib karena
     * banyak CDN memakai URL tanpa ekstensi — yang jelas salah (bukan URL)
     * yang ditolak, bukan yang ambigu.
     */
    private fun isValidPoster(v: String): Boolean {
        if (DATA_IMAGE.containsMatchIn(v)) return true
        if (!URL_PREFIX.containsMatchIn(v)) return false
        return !v.contains(' ')
    }

    private fun isValidUrl(v: String): Boolean {
        if (!URL_PREFIX.containsMatchIn(v)) return false
        return !v.contains(' ')
    }

    private fun isValidEpisodeText(v: String): Boolean {
        if (v.length !in 2..40) return false
        return EPISODE_TOKEN.containsMatchIn(v)
    }
}