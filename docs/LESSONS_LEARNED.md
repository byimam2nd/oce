# 📚 Lessons Learned - OCE Project

> "Experience is simply the name we give our mistakes." - Oscar Wilde

Dokumentasi ini berisi semua pelajaran, kesalahan, dan learnings yang didapat dari pengembangan proyek OCE.

---

## 🔥 CRITICAL LESSONS

### 1. ❌ JANGAN Hapus Import Tanpa Verifikasi 2x

**Tanggal:** 2026-04-04  
**Severity:** HIGH (Menyebabkan build gagal)

**Kesalahan:**
Saya menghapus import berdasarkan pattern matching tanpa membaca actual code usage:
- `kotlin.io.encoding.Base64` → DIHAPUS (padahal dipakai di `sigDecode()`)
- `kotlin.io.encoding.ExperimentalEncodingApi` → DIHAPUS (padahal dipakai di `@OptIn`)
- `android.annotation.SuppressLint` → DIHAPUS (padahal dipakai di `Megacloud` extractor)

**Root Cause:**
- Hanya search text pattern, tidak baca context
- Tidak check annotation usage (`@OptIn`, `@SuppressLint`)
- Tidak check function body yang mungkin hidden

**Correct Approach:**
```bash
# ❌ JANGAN: Hanya grep
grep "Base64" file.kt

# ✅ LAKUKAN: Baca file lengkap dan cari semua usage
# 1. Check imports section
# 2. Check annotations (@OptIn, @SuppressLint, etc.)
# 3. Check function bodies
# 4. Check data class definitions
# 5. Check companion objects
```

**Impact:**
- Build gagal di 8 providers
- Butuh 3x fix untuk restore imports
- Wasted CI/CD minutes

---

### 2. ❌ PowerShell Sync Script Corrupt Files

**Tanggal:** 2026-04-04  
**Severity:** HIGH (File corruption)

**Kesalahan:**
Menggunakan PowerShell `-replace` untuk sync master files ke generated_sync:
```powershell
# ❌ INI SALAH:
(Get-Content file.kt -Raw) -replace 'pattern', 'replacement' | Set-Content file.kt
```

**Root Cause:**
- PowerShell `-replace` corrupt special characters (backticks, unicode)
- Encoding issues dengan UTF-8 characters
- Line endings berubah (LF → CRLF)
- String interpolation issues dengan `$` dan backticks

**Correct Approach:**
```bash
# ✅ SELALU GUNAKAN: CI/CD bash sync script
bash scripts/sync-all-masters.sh
```

**Impact:**
- SyncExtractors.kt jadi corrupted
- Syntax errors di 8 providers
- `Unresolved reference` errors di mana-mana

---

### 3. ❌ Log.e() API Signature Salah

**Tanggal:** 2026-04-04  
**Severity:** MEDIUM

**Kesalahan:**
Menggunakan `Log.e(tag, message, exception)` dengan 3 argumen:
```kotlin
// ❌ SALAH:
Log.e("Tag", "Message", e)  // 3 arguments

// ✅ BENAR:
Log.e("Tag", "Message")     // 2 arguments only
```

**Root Cause:**
- Asumsi Log.e sama seperti Java/Android Log.e yang support 3 args
- CloudStream's Log.e hanya support 2 args
- Tidak check API documentation sebelum pakai

**Impact:**
- `Too many arguments` error di 10 extractors
- Build gagal di semua providers

---

### 4. ❌ Try-Catch Structure Salah

**Tanggal:** 2026-04-04  
**Severity:** MEDIUM

**Kesalahan:**
Menambahkan try-catch di Newuservideo extractor dengan structure salah:
```kotlin
// ❌ SALAH: catch di dalam map lambda
try {
    tryParseJson<Sources>(json)?.streams?.map {
        // ... code
    }
} catch (e: Exception) {  // ← Ini tidak bisa capture error dari map
    Log.e("Tag", "Failed: ${e.message}")
}
```

**Root Cause:**
- Tidak paham scope try-catch dengan lambda
- Map lambda punya scope sendiri
- Indentasi salah → braces tidak match

**Impact:**
- `Syntax error: Expecting ')'`
- `Missing '}'` errors
- Build gagal

---

## ⚡ WORKFLOW LESSONS

### 5. Empty Commit Tidak Selalu Trigger Workflow

**Tanggal:** 2026-04-04  
**Severity:** LOW

**Lesson:**
```bash
# ❌ Empty commit mungkin tidak trigger:
git commit --allow-empty -m "ci: trigger"

# ✅ Selalu buat perubahan minimal:
echo $(date) >> README.md && git add README.md && git commit -m "ci: trigger"
```

---

### 6. Force Push Sometimes Necessary

**Tanggal:** 2026-04-04  
**Severity:** MEDIUM

**Lesson:**
Ketika branch dalam keadaan broken dan tidak bisa recover dengan normal commit:
```bash
# Revert ke last known good commit
git reset --hard <good-commit-hash>
git push --force origin master

# Ini acceptable untuk:
# - Corrupted generated files
# - Broken sync state
# - Experiment yang gagal
```

---

### 7. Always Let CI/CD Handle Sync

**Tanggal:** 2026-04-04  
**Severity:** HIGH

**Lesson:**
```bash
# ❌ JANGAN sync manual dengan PowerShell
# ❌ JANGAN sync manual dengan copy-paste

# ✅ SELALU:
# 1. Edit master files
# 2. Commit changes ke master
# 3. Push ke remote
# 4. BIARKAN CI/CD sync script yang handle generated files
```

**Kenapa:**
- CI/CD sync script (bash + awk) tested dan proven working
- PowerShell scripts tidak handle encoding dengan benar
- Manual copy-paste pasti lupa replace package names

---

## 🔍 DEBUGGING LESSONS

### 8. Read Actual Errors, Not Assumptions

**Tanggal:** 2026-04-04  
**Severity:** HIGH

**Kesalahan:**
Saya assume error "Unresolved reference 'Log'" karena import hilang, ternyata karena:
1. Import `com.lagradost.api.Log` memang hilang ✓
2. TAPI setelah ditambah, error berubah ke "Too many arguments"
3. Yang ternyata karena Log.e() hanya terima 2 args

**Lesson:**
```bash
# ❌ JANGAN: Assume root cause dari satu error
# ✅ LAKUKAN:
# 1. Baca FULL error message
# 2. Fix first error, re-run, check next error
# 3. Iterate sampai semua error fixed
```

---

### 9. GH CLI Logging Limitations

**Tanggal:** 2026-04-04  
**Severity:** LOW

**Lesson:**
- `gh run view --log-failed` bisa timeout untuk large logs
- `findstr` di Windows tidak handle pipe dengan baik
- Better: Check GitHub Actions UI langsung di browser

---

## 🛠️ TOOLING LESSONS

### 10. PowerShell vs Bash untuk Sync

| Task | PowerShell | Bash (awk) | Winner |
|------|-----------|-----------|---------|
| Simple text replace | ✅ Works | ✅ Works | Tie |
| UTF-8 encoding | ❌ Issues | ✅ Works | **Bash** |
| Backticks/special chars | ❌ Corrupts | ✅ Preserves | **Bash** |
| Line endings | ❌ Changes | ✅ Preserves | **Bash** |
| Complex regex | ⚠️ Limited | ✅ Full power | **Bash** |

**Conclusion:** SELALU pakai bash sync script, JANGAN PowerShell untuk sync code files.

---

## 📋 BEST PRACTICES SUMMARY

### Before Deleting Anything:
- [ ] Read the FULL file, not just imports section
- [ ] Check ALL usages (annotations, functions, data classes)
- [ ] Verify 2x with different methods
- [ ] Test locally before commit

### Before Pushing:
- [ ] Check git diff carefully
- [ ] Verify no corrupted files
- [ ] Ensure sync will work (use CI script, not manual)

### When Build Fails:
- [ ] Read FULL error log
- [ ] Fix ONE error at a time
- [ ] Re-run after each fix
- [ ] Don't batch fixes (hard to debug)

### For Sync Operations:
- [ ] ALWAYS use CI/CD sync script
- [ ] NEVER sync manually with PowerShell
- [ ] ALWAYS let CI handle generated files

---

## 🎯 METRICS

| Metric | Value |
|--------|-------|
| **Total Lessons Learned** | 10 |
| **Build Failures Caused** | 7 |
| **CI/CD Minutes Wasted** | ~30 minutes |
| **Commits to Fix Mistakes** | 6 |
| **Force Pushes Needed** | 1 |

---

## 📝 CONTINUOUS IMPROVEMENT

### What We Do Better Now:
1. ✅ Verify imports 2x before deletion
2. ✅ Use CI/CD sync script exclusively
3. ✅ Check API documentation before using functions
4. ✅ Read full error logs before assuming root cause
5. ✅ Test changes locally when possible

### What Could Be Better:
- [ ] Automated import checker (linting)
- [ ] Pre-commit hooks for validation
- [ ] Better sync script error handling
- [ ] Local build verification before push

---

**Last Updated:** 2026-04-04  
**Contributors:** @imam2nd, AI Assistant  
**Status:** Active - Will be updated with each new lesson
