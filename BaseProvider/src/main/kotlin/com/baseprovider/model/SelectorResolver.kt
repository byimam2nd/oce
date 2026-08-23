package com.baseprovider.model

import com.baseprovider.log.FailureType
import com.baseprovider.log.logDebug
import com.baseprovider.log.logFail
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolver selector multi-fallback + adaptive relocate + self-correction.
 *
 * Konsep diport dari Scrapling `scrapling/parser.py` (adaptive):
 *  1. Selector string boleh berisi beberapa varian dipisah `||` — dicoba
 *     berurutan; varian pertama yang menghasilkan match dipakai. Ini backward
 *     compatible: selector lama tanpa `||` berperilaku identik.
 *  2. Jika SEMUA varian menghasilkan 0 match, dan ada fingerprint tersimpan
 *     untuk field itu (di-save saat selector sukses sebelumnya), dilakukan
 *     "relocate": scan seluruh elemen, beri skor kemiripan terhadap
 *     fingerprint, ambil elemen dengan skor tertinggi ≥ threshold. Hasilnya
 *     di-save ulang (self-healing) sehingga makin akurat tiap iterasi.
 *  3. Self-correction via validasi tipe: varian yang match DOM tapi hasilnya
 *     bukan tipe yang diharapkan (mis. teks deskripsi di posisi judul)
 *     ditolak dan varian itu di-blacklist sementara; iterasi berikutnya
 *     mencoba varian berikutnya. Validasi rule-based di [SelectorValidator].
 *
 * Storage bersifat in-memory per sesi (tanpa persistensi disk).
 */
object SelectorResolver {
    private const val SEPARATOR = "||"
    private const val DEFAULT_THRESHOLD = 40
    private const val MAX_RELOCATE_SCAN = 3000
    private const val MAX_BROKEN_PER_KEY = 8

    // Interval rate-limit log decay per key: selector yang gagal match akan
    // dipanggil per-item (search/detail) sehingga tanpa rate-limit logcat dan
    // Supabase ter-spam puluhan baris per halaman. Satu peringatan per key per
    // interval sudah cukup untuk visibilitas "selector mulai rusak".
    private const val DECAY_LOG_INTERVAL_MS = 5 * 60_000L
    private val lastDecayLog = ConcurrentHashMap<String, Long>()

    private data class Fingerprint(
        val tag: String,
        val attributes: Map<String, String>,
        val text: String,
        val path: List<String>,
        val parentTag: String?,
        val parentAttributes: Map<String, String>,
        val siblings: List<String>,
        val children: List<String>
    )

    // key = providerId:fieldName -> fingerprint elemen pertama yang match
    private val fingerprints = ConcurrentHashMap<String, Fingerprint>()

    // key = providerId:fieldName -> varian selector yang match DOM tapi
    // hasilnya gagal validasi tipe (di-blacklist sementara).
    private val brokenVariants = ConcurrentHashMap<String, MutableSet<String>>()

    private val SPECIAL_ATTRS = listOf("class", "id", "href", "src")

    // ── Adaptive episode-link detection (URL pattern, tanpa selector) ──
    // Dipakai sebagai fallback saat config.episodeItems gagal match (struktur
    // situs berubah). Cukup robust untuk pola episode umum: /eps/, /episode/,
    // /season-..., /ep-, atau teks "Eps N"/"Episode N".
    private val EPISODE_URL_REGEX = Regex(
        """(?i)(?:/eps/|/episode/|/ep/|-episode-|/season-|-season-|/ep-)"""
    )
    private val EPISODE_TEXT_REGEX = Regex(
        """(?i)(?:\bepisode\b|\beps?\b)\s*(\d+(?:\.\d+)?)"""
    )

    // Token episode KUAT (tanpa alternatif season). Dipakai sebagai lapisan
    // validasi TAMBAHAN di [detectEpisodeLinks]: URL yang hanya cocok pola
    // season adalah halaman detail series, bukan halaman episode.
    private val STRONG_EPISODE_URL_REGEX = Regex(
        """(?i)(?:/eps/|/episode/|/ep/|-episode-|/ep-)"""
    )

    // Pola season untuk deteksi "season-only" (URL cocok season TAPI tidak
    // kuat). Regex utama EPISODE_URL_REGEX TIDAK diubah — aturan ini murni
    // lapisan tambahan agar perilaku lama tetap utuh.
    private val SEASON_ONLY_URL_REGEX = Regex("""(?i)(?:/season-|-season-)""")

    /**
     * Scan seluruh anchor halaman; ambil yang URL atau teksnya cocok pola
     * episode. Href yang sama dengan halaman saat ini (mis. tombol "Lihat
     * Semua Episode") di-skip agar tidak jadi episode self-loop.
     */
    fun detectEpisodeLinks(document: Element, currentUrl: String): Elements {
        val out = Elements()
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val abs = runCatching { fixUrlSmart(href, currentUrl) }
                .getOrDefault(href)
            if (abs == currentUrl) continue
            val text = a.text()
            if (EPISODE_URL_REGEX.containsMatchIn(abs) ||
                EPISODE_TEXT_REGEX.containsMatchIn(text)) {
                // Lapisan adaptif TAMBAHAN (tidak mengubah aturan lama):
                // URL yang hanya cocok pola season tanpa token episode kuat
                // adalah halaman detail series — skip, KECUALI labelnya
                // jelas episode ("Eps N") yang mempertahankan perilaku lama.
                // Kasus nyata: rekomendasi "/tv/ludwig-season-2-2026/" di
                // halaman film Dutamovie21 membuat film jadi series palsu.
                val weakSeasonOnly =
                    !STRONG_EPISODE_URL_REGEX.containsMatchIn(abs) &&
                        SEASON_ONLY_URL_REGEX.containsMatchIn(abs)
                if (weakSeasonOnly && !EPISODE_TEXT_REGEX.containsMatchIn(text)) {
                    continue
                }
                out.add(a)
            }
        }
        logDebug("SelectorResolver",
            "detectEpisodeLinks[$currentUrl] -> ${out.size} kandidat")
        return out
    }

    /** Pecah selector multi-varian. Selector lama tanpa `||` tetap satu item. */
    fun variants(selector: String): List<String> =
        selector.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }

    fun selectFirst(
        document: Element,
        selector: String,
        key: String = ""
    ): Element? {
        if (selector.isBlank()) return null
        val v = variants(selector)
        for (variant in v) {
            val el = runCatching { document.selectFirst(variant) }.getOrNull()
            if (el != null) {
                logDebug("SelectorResolver",
                    "selectFirst[$key] HIT variant='$variant'")
                if (key.isNotBlank()) saveFingerprint(key, el)
                return el
            }
        }
        logDebug("SelectorResolver",
            "selectFirst[$key] MISS semua variant: '$selector'")
        if (key.isNotBlank()) {
            relocateAll(document, key).firstOrNull()?.let { el ->
                saveFingerprint(key, el)
                return el
            }
        }
        logDecay(key, "selectFirst: all variants failed, relocate no match: '$selector'")
        return null
    }

    fun select(
        document: Element,
        selector: String,
        key: String = ""
    ): Elements {
        if (selector.isBlank()) return Elements()
        val v = variants(selector)
        for (variant in v) {
            val els = runCatching { document.select(variant) }.getOrNull()
            if (els != null && !els.isEmpty()) {
                logDebug("SelectorResolver",
                    "select[$key] HIT variant='$variant' -> ${els.size} elemen")
                if (key.isNotBlank()) els.firstOrNull()?.let { saveFingerprint(key, it) }
                return els
            }
        }
        logDebug("SelectorResolver", "select[$key] MISS semua variant: '$selector'")
        if (key.isNotBlank()) {
            val relocated = relocateAll(document, key)
            if (relocated.isNotEmpty()) {
                saveFingerprint(key, relocated.first())
                return Elements(relocated)
            }
        }
        logDecay(key, "select: all variants failed, relocate no match: '$selector'")
        return Elements()
    }

    fun text(
        document: Element,
        selector: String,
        key: String = ""
    ): String? = selectFirst(document, selector, key)?.text()?.trim()

    /**
     * Self-correction loop. Sama dengan [selectFirst] tetapi setiap hasil
     * divalidasi terhadap [type]. Varian yang match DOM tapi hasilnya bukan
     * tipe yang diharapkan ditolak (di-blacklist sementara), lalu lanjut ke
     * varian berikutnya; jika semua varian gagal validasi, coba relocate
     * via fingerprint.
     */
    fun selectValidated(
        document: Element,
        selector: String,
        key: String,
        type: FieldType,
        extract: (Element) -> String?
    ): Element? {
        if (selector.isBlank()) return null
        val v = variants(selector)
        for (variant in v) {
            if (key.isNotBlank() && isBroken(key, variant)) continue
            val el = runCatching { document.selectFirst(variant) }.getOrNull()
            if (el == null) continue
            if (SelectorValidator.isValid(type, extract(el))) {
                logDebug("SelectorResolver",
                    "selectValidated[$key] VALID variant='$variant'")
                if (key.isNotBlank()) {
                    saveFingerprint(key, el)
                    unmarkBroken(key, variant)
                }
                return el
            }
            logDebug("SelectorResolver",
                "selectValidated[$key] INVALID variant='$variant' (tipe $type)")
            if (key.isNotBlank()) markBroken(key, variant)
        }
        if (key.isNotBlank()) {
            relocateValidated(document, key, type, extract)?.let { el ->
                saveFingerprint(key, el)
                return el
            }
        }
        logDecay(key, "selectValidated: all variants failed validation, relocate no match: '$selector'")
        return null
    }

    /**
     * Versi teks dari [selectValidated]: hasil harus lolos validasi tipe
     * sebelum dipakai. Return null jika semua varian gagal.
     */
    fun textValidated(
        document: Element,
        selector: String,
        key: String,
        type: FieldType
    ): String? =
        selectValidated(document, selector, key, type) { it.text()?.trim() }
            ?.text()?.trim()

    fun reset() {
        fingerprints.clear()
        brokenVariants.clear()
    }

    // ── Fingerprint (mirip Scrapling element_to_dict) ──

    private fun saveFingerprint(key: String, element: Element) {
        fingerprints[key] = elementToFingerprint(element)
    }

    private fun elementToFingerprint(el: Element): Fingerprint {
        val attrs = mutableMapOf<String, String>()
        el.attributes().forEach { a -> attrs[a.key] = a.value }
        val path = buildList {
            var cur: Element? = el
            while (cur != null) {
                add(cur.tagName())
                cur = cur.parent()
            }
        }
        val parent = el.parent()
        val parentAttrs = mutableMapOf<String, String>()
        parent?.attributes()?.forEach { a -> parentAttrs[a.key] = a.value }
        return Fingerprint(
            tag = el.tagName(),
            attributes = attrs,
            text = el.text().trim(),
            path = path,
            parentTag = parent?.tagName(),
            parentAttributes = parentAttrs,
            siblings = el.siblingElements().map { it.tagName() },
            children = el.children().map { it.tagName() }
        )
    }

    // ── Similarity scoring (port dari Scrapling parser.py:805-877) ──

    private fun similarity(fp: Fingerprint, el: Element): Int {
        var score = 0.0
        var checks = 0

        if (fp.tag == el.tagName()) { score += 1.0; checks++ }

        if (fp.text.isNotBlank()) {
            score += ratio(fp.text, el.text().trim())
            checks++
        }

        if (fp.attributes.isNotEmpty()) {
            val attrs = mutableMapOf<String, String>()
            el.attributes().forEach { a -> attrs[a.key] = a.value }
            score += dictDiff(fp.attributes, attrs)
            checks++
        }

        for (attr in SPECIAL_ATTRS) {
            val a = fp.attributes[attr]
            if (a != null && a.isNotBlank()) {
                score += if (a == el.attr(attr)) 1.0 else ratio(a, el.attr(attr))
                checks++
            }
        }

        if (fp.path.size >= 2) {
            val elPath = buildList {
                var cur: Element? = el
                while (cur != null) { add(cur.tagName()); cur = cur.parent() }
            }
            score += ratio(fp.path.joinToString("/"), elPath.joinToString("/"))
            checks++
        }

        val parent = el.parent()
        if (fp.parentTag != null && parent != null) {
            score += if (fp.parentTag == parent.tagName()) 1.0 else 0.0
            checks++
            if (fp.parentAttributes.isNotEmpty()) {
                val pattrs = mutableMapOf<String, String>()
                parent.attributes().forEach { a -> pattrs[a.key] = a.value }
                score += dictDiff(fp.parentAttributes, pattrs)
                checks++
            }
        }

        if (fp.siblings.isNotEmpty()) {
            score += ratio(fp.siblings.joinToString(","), el.siblingElements().map { it.tagName() }.joinToString(","))
            checks++
        }

        if (fp.children.isNotEmpty()) {
            score += ratio(fp.children.joinToString(","), el.children().map { it.tagName() }.joinToString(","))
            checks++
        }

        return if (checks == 0) 0 else (score / checks * 100).toInt()
    }

    private fun dictDiff(a: Map<String, String>, b: Map<String, String>): Double {
        val keyRatio = ratio(a.keys.joinToString(","), b.keys.joinToString(","))
        val valueRatio = ratio(a.values.joinToString(","), b.values.joinToString(","))
        return keyRatio * 0.5 + valueRatio * 0.5
    }

    /** Ratcliff-Obershelp (difflib.SequenceMatcher.ratio) — implementasi ringan. */
    private fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val m = a.length
        val n = b.length
        if (m * n > 400_000) return approximateRatio(a, b)
        val lcsLen = lcsLength(a, b)
        return 2.0 * lcsLen / (m + n)
    }

    private fun lcsLength(a: String, b: String): Int {
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1
                else maxOf(prev[j], curr[j - 1])
            }
            for (j in 1..b.length) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    private fun approximateRatio(a: String, b: String): Double {
        // Untuk string sangat panjang: bandingkan berdasarkan distribusi char.
        val counts = IntArray(128)
        for (c in a) if (c.code < 128) counts[c.code]++
        var common = 0.0
        for (c in b) if (c.code < 128 && counts[c.code] > 0) { common++; counts[c.code]-- }
        return 2.0 * common / (a.length + b.length)
    }

    // ── Relocate: scan seluruh DOM, cari elemen paling mirip fingerprint ──

    private fun relocateAll(document: Element, key: String): List<Element> {
        val fp = fingerprints[key] ?: return emptyList()
        var best = 0
        var scanned = 0
        val bestElements = mutableListOf<Element>()
        for (el in document.getAllElements()) {
            if (++scanned > MAX_RELOCATE_SCAN) break
            // Pre-filter murah: lewati elemen yang tag-nya beda tanpa menjalankan
            // similarity (subtree walk + LCS) yang mahal. Tag sama cukup karena
            // fingerprint menyimpan tag elemen asli yang pernah match.
            if (el.tagName() != fp.tag) continue
            val s = similarity(fp, el)
            if (s > best) { best = s; bestElements.clear(); bestElements.add(el) }
            else if (s == best && s > 0) { bestElements.add(el) }
        }
        if (best < DEFAULT_THRESHOLD || bestElements.isEmpty()) {
            Log.d("OCE", "Adaptive relocate: no match >= $DEFAULT_THRESHOLD for '$key' (best=$best)")
            return emptyList()
        }
        Log.d("OCE", "Adaptive relocate: '$key' matched ${bestElements.size} element(s) (score=$best)")
        return bestElements
    }

    // ── Relocate + validasi tipe (self-correction lanjutan) ──

    private fun relocateValidated(
        document: Element,
        key: String,
        type: FieldType,
        extract: (Element) -> String?
    ): Element? {
        val candidates = relocateAll(document, key)
        if (candidates.isEmpty()) return null
        val fp = fingerprints[key] ?: return null
        var best: Element? = null
        var bestScore = 0
        for (el in candidates) {
            if (!SelectorValidator.isValid(type, extract(el))) continue
            val s = similarity(fp, el)
            if (s > bestScore) { bestScore = s; best = el }
        }
        if (best != null) {
            Log.d("OCE", "Self-correct: relocate '$key' with valid ${type.name} (score=$bestScore)")
            return best
        }
        Log.d("OCE", "Self-correct: relocate '$key' failed validation (all candidates invalid ${type.name})")
        return null
    }

    // ── Broken variant tracking (selector yang match tapi hasil salah) ──

    /**
     * Log decay selector (rate-limited per key, hanya bila selector pernah
     * match sebelumnya). Sinyal "struktur situs berubah": selector yang dulu
     * bekerja sekarang gagal. Tanpa rate-limit, pencarian/detail yang
     * memanggil select per-item akan membanjiri log. Field yang sejak awal
     * tidak pernah match (tanpa fingerprint) TIDAK di-log — itu normal
     * (field opsional yang memang tidak ada di halaman).
     */
    private fun logDecay(key: String, message: String) {
        if (key.isBlank()) return
        if (!fingerprints.containsKey(key)) return
        val now = System.currentTimeMillis()
        val last = lastDecayLog[key] ?: 0L
        if (now - last < DECAY_LOG_INTERVAL_MS) return
        lastDecayLog[key] = now
        // key = "providerId:fieldName" — tag memakai providerId (konsisten
        // dengan log lain), key lengkap disimpan di kolom selectors untuk
        // query korelasi "selector mana paling sering rusak".
        val providerId = key.substringBefore(':').takeIf { it.isNotBlank() } ?: key
        logFail(
            tag = providerId,
            message = message,
            type = FailureType.SELECTOR_FAILURE,
            selectors = key,
            stage = "SELECT"
        )
    }

    private fun isBroken(key: String, variant: String): Boolean {
        val set = brokenVariants[key] ?: return false
        return variant in set
    }

    private fun markBroken(key: String, variant: String) {
        val set = brokenVariants.getOrPut(key) { mutableSetOf() }
        if (set.size >= MAX_BROKEN_PER_KEY) {
            // Jangan biarkan set membengkak — reset saat penuh agar bisa
            // "lupa" dan mencoba lagi (situs bisa berubah lagi).
            brokenVariants.remove(key)
            brokenVariants[key] = mutableSetOf(variant)
        } else {
            set.add(variant)
        }
        logDecay(key, "variant blacklisted (match DOM tapi gagal validasi tipe): '$variant'")
    }

    private fun unmarkBroken(key: String, variant: String) {
        brokenVariants[key]?.remove(variant)
    }
}