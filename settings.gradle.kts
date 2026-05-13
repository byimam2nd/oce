// ========================================
// CloudStream Extensions - Settings
// ========================================
// Modern plugin management with build optimization
// Last Updated: 2026-03-25
// ========================================

rootProject.name = "CloudstreamPlugins"

// Enable build cache for faster incremental builds
buildCache {
    local {
        isEnabled = true
        directory = File(rootDir, ".gradle/build-cache")
    }
}

// Auto-include all modules with build.gradle.kts
val disabled = listOf("BaseHtmlProvider", "BaseProvider")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

include(":ProviderBase")

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
