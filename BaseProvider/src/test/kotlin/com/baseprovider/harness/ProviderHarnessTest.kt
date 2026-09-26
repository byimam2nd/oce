package com.baseprovider.harness

import com.baseprovider.core.ProviderCloudstream
import com.baseprovider.log.ProviderLog
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProviderHarnessTest {

    @Test
    fun providerHarness() {
        runBlocking {
            assumeTrue("set -Doce.harness=1", System.getProperty("oce.harness") == "1")

            val providersProp = System.getProperty("oce.harness.providers") ?: "all"
            val query = System.getProperty("oce.harness.query") ?: "naruto"
            val maxEps = (System.getProperty("oce.harness.episodes") ?: "2").toInt()
            val stepTimeoutMs = (System.getProperty("oce.harness.timeout") ?: "120000").toLong()
            val outFile = System.getProperty("oce.harness.out")

            val targetProviders = if (providersProp == "all") {
                listOf("Anichin", "Animasu", "Donghuastream", "Dutamovie21", "IndoDrama21", "LayarKaca21", "Samehadaku", "Animexin")
            } else {
                providersProp.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }

            val report = StringBuilder()
            report.append("# Provider Harness Report\n\n")
            report.append("| provider | catalog | search | detail | links |\n")
            report.append("|---|---|---|---|---|\n")

            for (providerId in targetProviders) {
                val lines = mutableListOf<String>()
                ProviderLog.mirror = { lines.add(it) }

                var catalogStatus = "SKIP"
                var searchStatus = "SKIP"
                var detailStatus = "SKIP"
                var linksStatus = "SKIP"

                try {
                    val api = object : ProviderCloudstream() {
                        override val providerId: String get() = providerId
                    }

                    catalogStatus = runCatching {
                        runTestStep(stepTimeoutMs, "catalog") {
                            val req = MainPageRequest("OCE Harness", "", false)
                            val home = api.getMainPage(1, req)
                            val items = home.items.flatMap { (it as HomePageList).list }
                            if (items.isEmpty()) "KOSONG" else "OK(${items.size})"
                        }
                    }.getOrElse { e -> classifyFailure(e) }

                    val firstItemUrl = runCatching {
                        val req = MainPageRequest("OCE Harness", "", false)
                        val home = api.getMainPage(1, req)
                        home.items.flatMap { (it as HomePageList).list }
                            .firstOrNull()?.url
                    }.getOrNull()

                    searchStatus = runCatching {
                        runTestStep(stepTimeoutMs, "search") {
                            val results = api.search(query)
                            if (results.isEmpty()) "KOSONG" else "OK(${results.size})"
                        }
                    }.getOrElse { e -> classifyFailure(e) }

                    val detailUrl = firstItemUrl
                        ?: runCatching { api.search(query).firstOrNull()?.url }.getOrNull()
                        ?: ""

                    var episodeDataUrls: List<String> = emptyList()
                    var detailTitle = ""
                    detailStatus = runCatching {
                        runTestStep(stepTimeoutMs, "detail") {
                            val lr = api.load(detailUrl)
                            detailTitle = lr.name
                            episodeDataUrls = when (lr) {
                                is AnimeLoadResponse -> lr.episodes.values.flatten().map { it.data }
                                is TvSeriesLoadResponse -> lr.episodes.map { it.data }
                                is MovieLoadResponse -> {
                                    lr.dataUrl?.let { listOf(it) } ?: listOf(detailUrl)
                                }
                                else -> emptyList()
                            }
                            if (episodeDataUrls.isEmpty()) "KOSONG" else "OK(${episodeDataUrls.size} eps)"
                        }
                    }.getOrElse { e -> classifyFailure(e) }

                    if (episodeDataUrls.isNotEmpty() && !detailStatus.startsWith("GAGAL") && !detailStatus.startsWith("TIMEOUT")) {
                        val results = mutableListOf<String>()
                        for ((idx, epUrl) in episodeDataUrls.take(maxEps).withIndex()) {
                            val stepRes = runCatching {
                                runTestStep(stepTimeoutMs, "links-$idx") {
                                    val collected = mutableListOf<ExtractorLink>()
                                    val ok = api.loadLinks(epUrl, false, {}, { collected.add(it) })
                                    if (!ok) "GAGAL: loadLinks returned false"
                                    else if (collected.isEmpty()) "KOSONG"
                                    else "OK(${collected.size} links: ${collected.take(2).map { it.name }.joinToString(",")})"
                                }
                            }.getOrElse { e -> classifyFailure(e) }
                            results.add("ep${idx + 1}: $stepRes")
                        }
                        linksStatus = results.joinToString("; ")
                    } else {
                        linksStatus = "SKIP (detail $detailStatus)"
                    }
                } catch (e: NoClassDefFoundError) {
                    val msg = if (e.message?.contains("MainAPIKt") == true) {
                        "NEEDS_ANDROID_RUNTIME: CloudStream classes.jar static initializer requires Android. Harness runs in JVM only; use health-check workflow for diagnostics."
                    } else {
                        "GAGAL: ${e.javaClass.simpleName}: ${e.message?.take(120) ?: "no message"}"
                    }
                    catalogStatus = msg
                    searchStatus = "-"
                    detailStatus = "-"
                    linksStatus = "-"
                } finally {
                    ProviderLog.mirror = null
                    val failLines = lines.filter { it.contains("FAIL") || it.contains("ERROR") || it.contains("CRITICAL") }
                        .map { "  - $it" }
                        .joinToString("\n")
                    val debugLines = lines.filter { it.contains("SELECT") || it.contains("MISS") || it.contains("HIT") }
                        .take(10)
                        .map { "  - $it" }
                        .joinToString("\n")
                    val logSummary = StringBuilder()
                    if (failLines.isNotBlank()) logSummary.append("\n**Gagal**:\n$failLines")
                    if (debugLines.isNotBlank()) logSummary.append("\n**Selector**:\n$debugLines")
                    report.append("| $providerId | $catalogStatus | $searchStatus | $detailStatus | $linksStatus |\n")
                    if (logSummary.isNotBlank()) {
                        report.append("| | | | | $logSummary |\n")
                    }
                }
            }

            val finalReport = report.toString()
            println(finalReport)
            outFile?.let { File(it).writeText(finalReport) }
        }
    }

    private suspend fun <T> runTestStep(
        timeoutMs: Long,
        stepName: String,
        block: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            block()
        }
    }

    private fun classifyFailure(e: Throwable): String {
        return when {
            e is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
            e.message?.contains("WebView", ignoreCase = true) == true -> "NEEDS_WEBVIEW"
            e.message?.contains("browser", ignoreCase = true) == true -> "NEEDS_WEBVIEW"
            e.message?.contains("interceptor", ignoreCase = true) == true -> "NEEDS_WEBVIEW"
            else -> "GAGAL: ${e.javaClass.simpleName}: ${e.message?.take(120) ?: "no message"}"
        }
    }
}