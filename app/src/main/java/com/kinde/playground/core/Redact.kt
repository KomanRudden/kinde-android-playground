package com.kinde.playground.core

/** Masking and redaction. Nothing the playground shows or logs bypasses this. */
object Redact {
    val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")

    // Query values that must never be shown: OAuth codes, verifiers, tokens.
    private val SECRET_QUERY = Regex("(?i)([?&](?:code|code_verifier|refresh_token|access_token|id_token|token|client_secret|password)=)[^&#\\s]*")

    /** First 4 and last 4 characters, so two values can still be told apart. */
    fun mask(value: String?): String {
        if (value.isNullOrEmpty()) return "(empty)"
        if (value.length <= 10) return "•".repeat(value.length)
        return value.take(4) + "…" + value.takeLast(4) + " (${value.length} chars)"
    }

    /** Replaces JWTs, secret query values and any of [known] values found in [text]. */
    fun text(text: String, known: Collection<String?> = emptyList()): String {
        var out = JWT.replace(text) { "[JWT ${it.value.length} chars]" }
        out = SECRET_QUERY.replace(out) { it.groupValues[1] + "REDACTED" }
        known.filterNotNull().filter { it.length >= 8 }.forEach { secret ->
            out = out.replace(secret, "[REDACTED]")
        }
        return out
    }

    /** Errors are shown by type and HTTP status only: messages can carry URLs, codes or bodies. */
    fun error(t: Throwable?): String {
        if (t == null) return "Unknown error"
        val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
        val status = Regex("\\b([1-5]\\d\\d)\\b").find(t.message.orEmpty())?.value
        val oauth = (t as? net.openid.appauth.AuthorizationException)?.error
        return buildString {
            append(name)
            if (status != null) append(" (HTTP $status)")
            if (oauth != null) append(" [$oauth]")
            val safe = t.message?.let { text(it) }
                ?.substringBefore(" for http")
                ?.take(160)
            if (!safe.isNullOrBlank() && safe != t.javaClass.name) append(": ").append(safe)
        }
    }
}
