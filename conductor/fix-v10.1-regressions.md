# Plan: Fix V10.1 Regressions (Missing Metadata and Links)

## Objective
Restore full functionality to all 6 providers by fixing regressions introduced in V10.1, specifically regarding attribute selection and link extraction.

## Key Files & Context
- `BaseHtmlProvider/ProviderConstants.kt`
- `BaseHtmlProvider/Provider.kt`

## Root Causes
1. **Attribute Extraction Failure:** In V10.1, multiple attribute names were combined into single strings (e.g., `"GLOBAL:::data-src, GLOBAL:::src"`). Jsoup's `.attr()` method treats this as a single, invalid attribute name, causing metadata (posters, values) to be lost.
2. **`loadLinks` Extraction Failure:** The logic `val href = it.attr("href").ifBlank { it.selectFirst("a")?.attr("href") } ?: ""` fails to extract links from `<option>` tags (used by Anichin, Animasu, Donghuastream) because they store URLs in attributes like `value` or `data-index`, not `href`.

## Implementation Steps

### 1. Fix `ProviderConstants.kt` Attributes
Split the combined attribute strings back into individual list items to ensure `attrSafe` processes them correctly.

- `ATTR_IMAGE` -> `listOf("GLOBAL:::data-original", "GLOBAL:::data-src", "GLOBAL:::data-lazy-src", "GLOBAL:::src")`
- `ATTR_VALUE` -> `listOf("GLOBAL:::value", "GLOBAL:::data-index", "GLOBAL:::data-id", "GLOBAL:::data-url", "GLOBAL:::data-link")`

### 2. Fix `Provider.kt` Link Extraction
Update `loadLinks` to extract the URL using `attrSafe(resolveConfigList(ATTR_VALUE))` before falling back to `href`.

```kotlin
// In loadLinks
val playerLinksSelectors = resolveConfigList(LINK_OPTIONS)
val attrValueSelectors = resolveConfigList(ATTR_VALUE)

val playerLinks = playerLinksSelectors.flatMap { selector ->
    document.select(selector).map { el ->
        // Prioritaskan atribut value (untuk option tag), fallback ke href (untuk a/li tag)
        val href = el.attrSafe(attrValueSelectors) ?: el.attr("href").ifBlank { el.selectFirst("a")?.attr("href") } ?: ""
        fixUrlSmart(href, currentUrl)
    }
}.filter { it.isNotBlank() }
```

## Verification
- Run `test_load_links.py` on LayarKaca21 to ensure it remains stable.
- The UI should correctly display posters and descriptions for Anichin, Animasu, etc.
- Video links for Anichin and Animasu should load successfully.
