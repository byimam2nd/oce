package com.baseprovider.model

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolver selector multi-fallback + adaptive relocate.
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
 *
 * Storage bersifat in-memory per sesi (tanpa persistensi disk).
 */
object SelectorResolver {
    private const val SEPARATOR = "||"
    private const val DEFAULT_THRESHOLD = 40
    private const val MAX_RELOCATE_SCAN = 3000

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

    private val SPECIAL_ATTRS = listOf("class", "id", "href", "src")

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
                if (key.isNotBlank()) saveFingerprint(key, el)
                return el
            }
        }
        if (key.isNotBlank()) {
            relocateAll(document, key).firstOrNull()?.let { el ->
                saveFingerprint(key, el)
                return el
            }
        }
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
                if (key.isNotBlank()) els.firstOrNull()?.let { saveFingerprint(key, it) }
                return els
            }
        }
        if (key.isNotBlank()) {
            val relocated = relocateAll(document, key)
            if (relocated.isNotEmpty()) {
                saveFingerprint(key, relocated.first())
                return Elements(relocated)
            }
        }
        return Elements()
    }

    fun text(
        document: Element,
        selector: String,
        key: String = ""
    ): String? = selectFirst(document, selector, key)?.text()?.trim()

    fun reset() = fingerprints.clear()

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
}