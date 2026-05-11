# 🛠️ Development Guidelines - OCE Project

> "Good developers learn from their mistakes. Great developers learn from others' mistakes."

Dokumentasi ini berisi best practices, conventions, dan guidelines untuk pengembangan proyek OCE.

---

## 📋 TABLE OF CONTENTS

- [Import Management](#import-management)
- [Code Quality](#code-quality)
- [Sync Workflow](#sync-workflow)
- [Error Handling](#error-handling)
- [Testing](#testing)
- [Git Workflow](#git-workflow)
- [CI/CD](#cicd)
- [Debugging](#debugging)

---

## 📦 IMPORT MANAGEMENT

### ✅ DO:
```kotlin
// 1. Verify usage before adding/removing imports
// Check: Is it used in code?
val result = Base64.decode(data)  // ← Yes, used

// Check: Is it used in annotation?
@OptIn(ExperimentalEncodingApi::class)  // ← Yes, used

// Check: Is it used in companion object?
companion object {
    fun helper() = ConcurrentHashMap<String, Any>()  // ← Yes, used
}

// 2. Group imports logically
// Group 1: CloudStream library
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Group 3: External libraries
import org.jsoup.nodes.Element
import org.json.JSONObject

// Group 4: Kotlin/Java standard library
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

// Group 5: Android-specific
import android.annotation.SuppressLint
```

### ❌ DON'T:
```kotlin
// ❌ Delete imports based on text search only
// Just because "Mutex" appears in import doesn't mean it's used

// ❌ Assume wildcard imports are fine
import com.lagradost.cloudstream3.*  // Makes it hard to track usage

// ❌ Mix wildcard with explicit imports
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor  // Confusing!
```

### 🔍 Verification Checklist:
Before removing ANY import:
- [ ] Search for direct usage in code
- [ ] Check annotations (`@OptIn`, `@SuppressLint`, etc.)
- [ ] Check companion objects
- [ ] Check extension functions
- [ ] Check data class definitions
- [ ] Check inline function calls

---

## 🎨 CODE QUALITY

### Error Handling:
```kotlin
// ✅ GOOD: Use Log.e() with 2 arguments only
Log.e("Tag", "Error message: ${e.message}")

// ❌ BAD: Don't use 3 arguments
Log.e("Tag", "Message", e)  // CloudStream Log.e only takes 2 args!
```

### Try-Catch Structure:
```kotlin
// ✅ GOOD: Try-catch around proper scope
try {
    val result = processSomething()
    callback(result)
} catch (e: Exception) {
    Log.e("Tag", "Failed: ${e.message}")
}

// ❌ BAD: Try-catch around lambda (won't catch lambda errors)
try {
    list.map { item ->
        riskyOperation(item)  // ← Errors here NOT caught!
    }
} catch (e: Exception) {
    // This won't work!
}
```

### Logging:
```kotlin
// ✅ GOOD: Consistent tag format
Log.e("ModuleName", "[ExtractorName] Failed: ${e.message}")
Log.d("ModuleName", "Cache HIT for $key")

// ❌ BAD: Inconsistent tags
Log.e("RandomTag", "Something failed")
Log.d("SomeOtherTag", "Cache hit")
```

---

## 🐛 ERROR HANDLING

### Standard Pattern:
```kotlin
override suspend fun getUrl(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    try {
        // Extraction logic
        val document = app.get(url, referer = referer).document
        // ... process
        
    } catch (e: Exception) {
        Log.e("MasterExtractors", "[ExtractorName] Failed: ${e.message}")
        // Don't throw - let other extractors try
    }
}
```

### What NOT to Do:
```kotlin
// ❌ DON'T throw exceptions (crashes the app)
throw Exception("Failed: ${e.message}")

// ❌ DON'T silent catch (hard to debug)
} catch (e: Exception) {
    // Nothing here!
}

// ❌ DON'T wrong Log.e signature
Log.e("Tag", "Message", e)  // 3 args - WRONG!
```

---

## 🧪 TESTING

### Before Pushing:
- [ ] Read git diff carefully
- [ ] Verify no corrupted files
- [ ] Ensure imports are correct
- [ ] Check for syntax errors

### After Pushing:
- [ ] Monitor CI/CD workflow
- [ ] Check build status
- [ ] If failed, read error logs
- [ ] Fix ONE error at a time
- [ ] Re-run after each fix

---

## 🔀 GIT WORKFLOW

### Commit Messages:
```bash
# Format: <type>: <description>

# Types:
fix:     - Bug fix
feat:    - New feature
cleanup: - Code cleanup (imports, formatting)
chore:   - Maintenance tasks (sync, CI/CD)
docs:    - Documentation

# Examples:
fix(P0): add missing Log import to MasterExtractors.kt
cleanup: remove 18 unused imports across codebase
chore: update generated_sync files
docs: add LESSONS_LEARNED.md
```

### When to Force Push:
```bash
# ✅ Acceptable cases:
- Corrupted generated files
- Broken sync state
- Experiment failures
- Accidental bad commits

# ❌ Never force push:
- Normal code changes
- After CI generates files
- When others might have pulled
```

### Recovery Commands:
```bash
# Revert to last known good commit
git reset --hard <commit-hash>
git push --force origin master

# Abort bad rebase
git rebase --abort

# Restore specific files from origin
git checkout origin/master -- path/to/file.kt
```

---

## 🤖 CI/CD

### Understanding the Pipeline:
```
git push → Sync Job → Build Job → Push artifacts
           ↓             ↓
    1. Detect modules  1. Checkout latest
    2. Run sync script 2. ./gradlew make
    3. Verify files   3. Copy to builds/
    4. Commit & push  4. Push to builds branch
```

### Common Issues:
| Issue | Cause | Fix |
|-------|-------|-----|
| Build fails with "Unresolved reference" | Missing import or wrong sync | Check imports, re-sync |
| Build fails with "Too many arguments" | Wrong API signature | Check API docs |
| Build fails with "Syntax error" | Corrupted file or bad structure | Restore from good commit |
| Workflow doesn't trigger | Empty commit or network issue | Make real change, push again |

### Monitoring:
```bash
# List recent workflows
gh run list --limit 5

# Watch specific workflow
gh run watch <ID>

# Check failed logs
gh run view --log-failed --job=<job-ID>
```

---

## 🔍 DEBUGGING

### Step-by-Step Approach:
1. **Read FULL error message**
2. **Identify the FIRST error** (often causes cascade)
3. **Fix ONE error**
4. **Re-run/build**
5. **Check next error**
6. **Repeat until clean**

### Common Mistakes:
```bash
# ❌ DON'T: Assume root cause from partial info
# ✅ DO: Read complete error log

# ❌ DON'T: Fix all errors at once
# ✅ DO: Fix one, verify, then next

# ❌ DON'T: Use PowerShell for sync
# ✅ DO: Use CI/CD bash script
```

### Tools:
```bash
# Search for usage
grep -r "ClassName" --include="*.kt" .

# Check imports
grep -n "^import" file.kt

# Find specific patterns
findstr /N "pattern" file.kt

# Compare commits
git diff commit1 commit2 -- file.kt
```

---

## 📊 QUICK REFERENCE

### CloudStream Log.e():
```kotlin
// ✅ CORRECT (2 args only)
Log.e("Tag", "Message")
Log.e("Tag", "Error: ${e.message}")

// ❌ WRONG (3 args)
Log.e("Tag", "Message", e)
```

### Import Organization:
```kotlin
// Group 1: Generated sync
import com.{Provider}.generated_sync.*

// Group 2: CloudStream
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*

// Group 3: External
import org.jsoup.nodes.Element

// Group 4: Kotlin/Java
import kotlinx.coroutines.*

// Group 5: Android
import android.annotation.SuppressLint
```

---

**Last Updated:** 2026-05-06  
**Maintainer:** imam2nd  
**Status:** Active - Will be updated as we learn more
