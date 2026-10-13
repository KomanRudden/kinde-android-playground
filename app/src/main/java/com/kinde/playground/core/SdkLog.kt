package com.kinde.playground.core

import android.os.Process

/**
 * Reads this process's own logcat. An app may always read its own log lines, so
 * this needs no permission.
 *
 * The SDK's ApiClient logs "URL: ..." and "Response code: ..." for every request
 * when the SDK is a debug build (it is when built from source, as here). That is
 * how the playground shows which HTTP calls a given SDK method made without
 * patching the SDK. With a published (release) AAR those lines don't exist, and
 * the pages say so.
 */
object SdkLog {
    data class Line(val epochMs: Long, val level: Char, val tag: String, val message: String)

    data class HttpCall(val url: String, val status: Int?)

    private val SDK_TAGS = listOf("ApiClient", "KindeSDK", "KindeClaim", "AppAuth")

    /** Parses `logcat -v epoch` output. Example: "1728712345.123  4242  4260 D ApiClient: URL: https://..." */
    private val LINE = Regex("^\\s*(\\d+)\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s?(.*)$")

    fun parse(output: String): List<Line> = output.lineSequence().mapNotNull { raw ->
        LINE.find(raw)?.destructured?.let { (sec, ms, level, tag, msg) ->
            Line(sec.toLong() * 1000 + ms.toLong(), level[0], tag.trim(), msg)
        }
    }.toList()

    /** Pairs "URL:" lines with the "Response code:" line that follows them. */
    fun httpCalls(lines: List<Line>): List<HttpCall> {
        val calls = mutableListOf<HttpCall>()
        var pending: String? = null
        for (line in lines.filter { it.tag == "ApiClient" }) {
            when {
                line.message.startsWith("URL: ") -> {
                    pending?.let { calls += HttpCall(it, null) }
                    pending = Redact.text(line.message.removePrefix("URL: ").trim())
                }
                line.message.startsWith("Response code: ") && pending != null -> {
                    calls += HttpCall(pending, line.message.removePrefix("Response code: ").trim().toIntOrNull())
                    pending = null
                }
            }
        }
        pending?.let { calls += HttpCall(it, null) }
        return calls
    }

    /** Lines logged by this process since [sinceMs]. Empty if logcat can't be read. */
    fun read(sinceMs: Long = 0, tags: List<String>? = SDK_TAGS): List<Line> {
        val cmd = mutableListOf("logcat", "-d", "-v", "epoch", "--pid=${Process.myPid()}")
        if (sinceMs > 0) {
            // logcat -T takes "sec.ms" in epoch format when -v epoch is used.
            cmd += listOf("-T", "%d.%03d".format(sinceMs / 1000, sinceMs % 1000))
        }
        if (tags != null) {
            tags.forEach { cmd += "$it:V" }
            cmd += "*:S"
        }
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            parse(text).filter { it.epochMs >= sinceMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Runs [block] and returns its result with the HTTP calls the SDK logged meanwhile. */
    inline fun <T> capture(block: () -> T): Pair<T, List<HttpCall>> {
        val start = System.currentTimeMillis() - 5
        val result = block()
        // Give logd a moment to flush the last lines.
        Thread.sleep(150)
        return result to httpCalls(read(start, listOf("ApiClient")))
    }

    /** True when the SDK build in this APK emits ApiClient lines (debug SDK builds only). */
    val httpLoggingAvailable: Boolean by lazy {
        runCatching {
            val cls = Class.forName("au.kinde.sdk.BuildConfig")
            cls.getField("DEBUG").getBoolean(null)
        }.getOrDefault(false)
    }
}
