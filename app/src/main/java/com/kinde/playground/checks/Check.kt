package com.kinde.playground.checks

import com.kinde.playground.core.Redact
import com.kinde.playground.core.SdkLog

enum class Outcome { PASSED, FAILED, WARN, SKIPPED, INFO }

/**
 * One check, written as three plain sentences: what it did, what went over the
 * network, and what came back. Mirrors the Python playground's live checks.
 */
data class Check(
    val group: String,
    val name: String,
    val did: String,
    val outcome: Outcome,
    val http: String,
    val result: String,
) {
    val id: String get() = "$group:$name".lowercase().replace(Regex("[^a-z0-9:]+"), "-")
}

data class CheckRun(
    val title: String,
    val checks: List<Check>,
    val elapsedMs: Long,
) {
    val passed get() = checks.count { it.outcome == Outcome.PASSED }
    val failed get() = checks.count { it.outcome == Outcome.FAILED }
    val warned get() = checks.count { it.outcome == Outcome.WARN }
    val skipped get() = checks.count { it.outcome == Outcome.SKIPPED }
    val status: Outcome
        get() = when {
            failed > 0 -> Outcome.FAILED
            warned > 0 -> Outcome.WARN
            passed > 0 -> Outcome.PASSED
            else -> Outcome.SKIPPED
        }

    fun summary(): String = buildString {
        append("$passed passed")
        if (failed > 0) append(", $failed failed")
        if (warned > 0) append(", $warned warnings")
        if (skipped > 0) append(", $skipped skipped")
        append(" in ${"%.1f".format(elapsedMs / 1000.0)}s")
    }
}

object Judge {
    const val NO_HTTP_TOKEN = "Nothing is sent to Kinde. This reads the token the SDK already holds."
    const val NO_HTTP = "Nothing is sent to Kinde."

    fun describeHttp(calls: List<SdkLog.HttpCall>, empty: String): String {
        if (calls.isEmpty()) return empty
        val parts = calls.take(3).map { c ->
            val came = c.status?.let { " came back $it" } ?: " did not complete"
            "${methodFor(c.url)}${c.url}$came."
        }.toMutableList()
        val extra = calls.size - 3
        if (extra > 0) parts += "$extra more call${if (extra != 1) "s" else ""}."
        return parts.joinToString(" ")
    }

    // ApiClient logs URLs but not methods. Every SDK call is a GET except these two.
    private fun methodFor(url: String) = when {
        url.endsWith("/oauth2/token") -> "POST "
        url.endsWith("/api/v1/user") -> "POST "
        else -> "GET "
    }

    private fun refusal(status: Int, subject: String): String {
        var text = "Kinde returned HTTP $status" + (if (subject.isNotBlank()) " for $subject" else "") + "."
        if (status == 403) text += " The token may be missing the scope or audience for this call."
        if (status == 401) text += " The token was rejected."
        return text
    }

    /**
     * Turns a call into an outcome. A null result, an exception, an HTTP error or
     * an SDK exception reported to the listener all fail the check; the SDK often
     * signals failure only by returning null and calling onException.
     */
    fun judge(
        group: String,
        name: String,
        did: String,
        value: Any?,
        error: Throwable?,
        calls: List<SdkLog.HttpCall>,
        sdkExceptions: List<String>,
        expectsHttp: Boolean,
        subject: String,
        success: (Any?) -> String,
        nullIsFailure: Boolean = true,
    ): Check {
        // A refusal followed by a successful call to the same URL means the SDK refreshed and retried.
        val refused = calls.filter { (it.status ?: 0) >= 400 }
        val recovered = refused.isNotEmpty() && refused.all { r ->
            calls.drop(calls.lastIndexOf(r) + 1).any { it.url == r.url && it.status in 200..299 }
        }
        val bad = if (recovered) null else refused.lastOrNull()?.status
        val empty = when {
            !expectsHttp -> NO_HTTP_TOKEN
            !SdkLog.httpLoggingAvailable -> "The SDK in this build doesn't log HTTP calls (published release AAR), so they can't be shown."
            else -> "Nothing reached Kinde: the SDK made no request."
        }
        val http = describeHttp(calls, empty)
        val sdkNote = if (sdkExceptions.isNotEmpty()) " The SDK reported: ${sdkExceptions.joinToString("; ")}." else ""
        val outcome: Outcome
        val result: String
        when {
            error != null -> {
                outcome = Outcome.FAILED
                result = (bad?.let { refusal(it, subject) } ?: Redact.error(error)) + sdkNote
            }
            bad != null -> {
                outcome = Outcome.FAILED
                result = refusal(bad, subject) + " The SDK returned ${if (value == null) "null" else "a value"} instead of raising." + sdkNote
            }
            value == null && nullIsFailure -> {
                outcome = Outcome.FAILED
                result = "The SDK returned null.$sdkNote"
            }
            expectsHttp && calls.isEmpty() && SdkLog.httpLoggingAvailable -> {
                outcome = Outcome.FAILED
                result = "The SDK made no request, so this did not reach Kinde. " + success(value)
            }
            else -> {
                outcome = Outcome.PASSED
                result = success(value) + if (recovered) " (Kinde first refused the token; the SDK refreshed it and retried.)" else ""
            }
        }
        return Check(group, name, did, outcome, http, result)
    }

    fun skipped(group: String, name: String, did: String, why: String) =
        Check(group, name, did, Outcome.SKIPPED, NO_HTTP, why)

    fun shown(value: Any?): String = when (value) {
        true -> "true"
        false -> "false"
        null, "" -> "empty"
        else -> value.toString()
    }
}
