package com.baseprovider.log

import com.lagradost.api.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

object ProviderLog {
    private const val GLOBAL_PREFIX = "OCE"
    private const val SENT_MESSAGE_LIMIT = 1000

    private val TG_TOKEN: String get() = System.getenv("OCE_TG_TOKEN") ?: ""
    private val TG_GROUP_ID: String get() = System
        .getenv("OCE_TG_GROUP_ID") ?: ""
    private val TG_THREAD_ID: String get() = System.getenv("OCE_TG_THREAD_ID") ?: "2"

    private val sentMessages = java.util.concurrent
        .ConcurrentHashMap<String, Pair<Int, Int>>()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tgJob = Job()

    fun log(
        level: LogLevel, tag: String, message: String,
        error: Throwable? = null, url: String? = null,
        method: String? = null, type: FailureType? = null,
        selectors: String = ""
    ) {
        val errTrace = error?.let {
            buildString {
                val cause = it.message ?: it.javaClass.simpleName
                append("\n")
                append("Cause : $cause\n")
                it.stackTrace.take(3).forEachIndexed { index, frame ->
                    append("Stack ${index + 1}: ${frame.fileName}:${frame.lineNumber}\n")
                }
            }
        } ?: ""
        val host = url?.let { runCatching { URI(it).host }
            .getOrElse { e -> Log.d("OCE", "URI parsing failed for $url: ${e.message}"); null } } ?: ""
        val ft = type ?: if (host.contains("short.")) FailureType
            .SHORTLINK_FAILURE else FailureType.UNKNOWN
        val hostInfo = if (host.isNotBlank()) " | host=$host" else ""
        val methodInfo = if (method != null) " | method=$method" else ""
        val typeInfo = " | type=${ft.label}"
        val selInfo = if (selectors
            .isNotBlank()) " | selectors=$selectors" else ""
        val logcatMsg = "[$tag]${methodInfo}$typeInfo${selInfo}$hostInfo | $message"
        val fullMsg = message + errTrace

        when (level) {
            LogLevel.DEBUG -> Log.d(GLOBAL_PREFIX, logcatMsg)
            LogLevel.SUCCESS -> Log.i(GLOBAL_PREFIX, logcatMsg)
            LogLevel.FAIL -> Log.w(GLOBAL_PREFIX, logcatMsg)
            LogLevel.ERROR -> Log.e(GLOBAL_PREFIX, logcatMsg)
            LogLevel.CRITICAL -> Log.e(GLOBAL_PREFIX, logcatMsg)
        }

        if (level != LogLevel.DEBUG && level != LogLevel.SUCCESS) {
            sendToTelegram(level.name, tag, fullMsg, url, host, method, ft,
                selectors)
        }
    }

    private fun trimSentMessages() {
        if (sentMessages.size > SENT_MESSAGE_LIMIT) {
            val keysToEvict = sentMessages.keys.take(sentMessages
                .size - (SENT_MESSAGE_LIMIT / 2))
            keysToEvict.forEach { sentMessages.remove(it) }
        }
    }

    private fun sendToTelegram(
        level: String, tag: String, message: String,
        url: String?, host: String, method: String?,
        type: FailureType, selectors: String = ""
    ) {
        if (TG_TOKEN.isBlank()) return
        val emoji = when (level) {
            "SUCCESS" -> "\u2705"
            "FAIL" -> "\u274C"
            "ERROR" -> "\u274C"
            "CRITICAL" -> "\uD83D\uDD25"
            else -> "\u2139\uFE0F"
        }
        val urlInfo = url?.let { if (it.length > 80) it
            .take(77) + "..." else it } ?: ""
        val methodInfo = method ?: ""
        val selInfo = selectors.ifBlank { "-" }

        val isLinkMethod = method == "loadLinks" || method == "extractLinks"
        val rawBody = when {
            level == "SUCCESS" -> "$emoji[Sukses]$tag/$methodInfo/$selInfo/$urlInfo/$message"
            isLinkMethod && level == "FAIL" -> "$emoji[$level]$tag/$methodInfo/$urlInfo/$selInfo/$message\nMassagge: $message"
            else -> "$emoji[$level]$tag/$methodInfo/$selInfo/$urlInfo/$message\nMassagge: $message"
        }

        val key = "$level|$tag|${method ?: ""}|$host"

        synchronized(sentMessages) { trimSentMessages() }

        logScope.launch(tgJob) {
            val existing = sentMessages[key]
            if (existing != null) {
                val (msgId, count) = existing
                val newCount = count + 1
                val body = "[$newCount]$rawBody"
                runCatching {
                    com.lagradost.cloudstream3.app.post(
                        "https://api.telegram.org/bot$TG_TOKEN/editMessageText",
                        requestBody = org.json.JSONObject().apply {
                            put("chat_id", TG_GROUP_ID)
                            if (TG_THREAD_ID.isNotBlank()) put("message_thread_id", TG_THREAD_ID.toIntOrNull() ?: 0)
                            put("message_id", msgId)
                            put("text", body)
                        }.toString().toRequestBody("application/json"
                            .toMediaType())
                    ).text
                }.onSuccess {
                    sentMessages[key] = msgId to newCount
                }.onFailure { e -> Log.e("OCE", "Telegram editMessageText failed: ${e.message}") }
            } else {
                runCatching {
                    val resp = com.lagradost.cloudstream3.app.post(
                        "https://api.telegram.org/bot$TG_TOKEN/sendMessage",
                        requestBody = org.json.JSONObject().apply {
                            put("chat_id", TG_GROUP_ID)
                            if (TG_THREAD_ID.isNotBlank()) put("message_thread_id", TG_THREAD_ID.toIntOrNull() ?: 0)
                            put("text", rawBody)
                            put("disable_web_page_preview", true)
                        }.toString().toRequestBody("application/json"
                            .toMediaType())
                    ).text
                    val msgId = org.json.JSONObject(resp)
                        .getJSONObject("result").getInt("message_id")
                    sentMessages[key] = msgId to 1
                }.onFailure { e -> Log.e("OCE", "Telegram sendMessage failed: ${e.message}") }
            }
        }
    }
}

fun log(
    level: LogLevel, tag: String, message: String,
    error: Throwable? = null, url: String? = null,
    method: String? = null, type: FailureType? = null,
    selectors: String = ""
) = ProviderLog.log(level, tag, message, error, url, method, type,
    selectors)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag,
    message)
fun logFail(
    tag: String, message: String, url: String? = null,
    method: String? = null, type: FailureType? = null,
    selectors: String = ""
) = log(LogLevel.FAIL, tag, message, url = url, method = method, type =
    type, selectors = selectors)
fun logError(
    tag: String, message: String, error: Throwable? = null,
    url: String? = null, method: String? = null,
    type: FailureType? = null
) = log(LogLevel.ERROR, tag, message, error, url, method, type)
fun logCritical(
    tag: String, message: String, error: Throwable? = null,
    url: String? = null, method: String? = null,
    type: FailureType? = null, selectors: String = ""
) = log(LogLevel.CRITICAL, tag, message, error, url, method, type,
    selectors)
fun logSuccess(
    tag: String, message: String, url: String? = null,
    method: String? = null, selectors: String = ""
) = log(LogLevel.SUCCESS, tag, message, url = url, method = method, type =
    FailureType.SUCCESS, selectors = selectors)
