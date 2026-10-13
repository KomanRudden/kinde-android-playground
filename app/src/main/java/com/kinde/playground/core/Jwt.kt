package com.kinde.playground.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes a JWT for display. Does not verify anything: verification is the
 * SDK's job (see KindeClient.isAuthenticated), and the security page checks it.
 */
data class DecodedJwt(val header: Map<String, Any?>, val payload: Map<String, Any?>) {
    val exp: Long? get() = (payload["exp"] as? Number)?.toLong()
    val iat: Long? get() = (payload["iat"] as? Number)?.toLong()
    val iss: String? get() = payload["iss"] as? String
    val sub: String? get() = payload["sub"] as? String
    val kid: String? get() = header["kid"] as? String
    val alg: String? get() = header["alg"] as? String

    /** "aud" may be a string or a list. */
    val audiences: List<String>
        get() = when (val aud = payload["aud"]) {
            is String -> listOf(aud)
            is List<*> -> aud.filterIsInstance<String>()
            else -> emptyList()
        }

    fun secondsLeft(nowMs: Long = System.currentTimeMillis()): Long? = exp?.let { it - nowMs / 1000 }
}

object Jwt {
    fun decode(token: String?): DecodedJwt? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        return try {
            DecodedJwt(toMap(JSONObject(b64(parts[0]))), toMap(JSONObject(b64(parts[1]))))
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(part: String): String =
        String(java.util.Base64.getUrlDecoder().decode(part.trimEnd('=')), Charsets.UTF_8)

    fun toMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { unwrap(obj.opt(it)) }.toSortedMap()

    private fun unwrap(value: Any?): Any? = when (value) {
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    /** Pretty, stable rendering of claim values for the UI. */
    fun render(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
            prefix = "{\n", postfix = "\n$indent}", separator = ",\n"
        ) { "$indent  ${it.key}: ${render(it.value, "$indent  ")}" }
        is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(prefix = "[", postfix = "]") { render(it, indent) }
        is String -> "\"$value\""
        else -> value.toString()
    }
}
