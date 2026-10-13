package com.kinde.playground.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The playground's own HTTP client, for setup checks the SDK has no API for
 * (OIDC discovery, comparing the live JWKS with what the SDK stored). Never
 * sends a token.
 */
object Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Response(val url: String, val status: Int?, val json: JSONObject?, val error: Throwable?)

    fun getJson(url: String): Response = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Response(url, resp.code, runCatching { JSONObject(body) }.getOrNull(), null)
        }
    } catch (e: Exception) {
        Response(url, null, null, e)
    }
}
