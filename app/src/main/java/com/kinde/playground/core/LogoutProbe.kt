package com.kinde.playground.core

import android.content.Context
import au.kinde.sdk.KindeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Answers "is the refresh token dead after logout?". Keeps a copy of the refresh
 * token before logout(), and once onLogout arrives offers that copy to Kinde's
 * token endpoint once, [PROBE_AFTER_MS] later, as anyone holding a leaked copy
 * could. Only once: Kinde rotates refresh tokens, so using the copy retires it.
 * If Kinde accepts it, the replacement it hands out is revoked so nothing is
 * left behind.
 */
object LogoutProbe {
    // Kinde applies a revocation within about two minutes; wait past that.
    private const val PROBE_AFTER_MS = 150_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    @Volatile
    private var armed: Armed? = null
    private class Armed(val refreshToken: String, val loggedOutFrom: Long)

    private fun now() = later(System.currentTimeMillis())
    private fun later(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** Copies the current refresh token. Returns false when there is none. */
    fun arm(context: Context): Boolean {
        val token = runCatching { KindeClient.getInstance(context).getRefreshToken() }.getOrNull()
        if (token.isNullOrBlank()) {
            _result.value = "No refresh token to keep: sign in first."
            return false
        }
        armed = Armed(token, System.currentTimeMillis())
        _result.value = "Kept a copy of the refresh token ${Redact.mask(token)}. Waiting for onLogout…"
        return true
    }

    /** Called from the hub's onLogout. */
    fun onLogout() {
        val probe = armed ?: return
        armed = null
        thread(name = "logout-probe") {
            // Let the SDK finish whatever it does after logout first.
            Thread.sleep(3_000)
            val calls = SdkLog.httpCalls(SdkLog.read(probe.loggedOutFrom, listOf("ApiClient")))
                .filter { "/oauth2/" in it.url }
            val head = buildString {
                append("onLogout at ${now()}\n")
                append("SDK calls after logout(): ")
                append(if (calls.isEmpty()) "none" else calls.joinToString { "${it.url.substringAfter("/oauth2/")} ${it.status ?: "—"}" })
                append("\n\nOffering the old refresh token to Kinde:\n")
            }
            _result.value = head + "Waiting until ${later(probe.loggedOutFrom + PROBE_AFTER_MS)}…"
            Thread.sleep((probe.loggedOutFrom + PROBE_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0))
            val (status, accepted) = tryOldToken(probe.refreshToken)
            val after = (System.currentTimeMillis() - probe.loggedOutFrom) / 1000
            val verdict = when (accepted) {
                false -> "Rejected ${after}s after logout: the old refresh token is dead."
                true -> "Still accepted ${after}s after logout: anyone holding a copy can keep minting access tokens."
                null -> "Couldn't reach Kinde."
            }
            _result.value = head + "${now()}  POST /oauth2/token → $status\n\n$verdict"
        }
    }

    /** Status text, and true/false for accepted/rejected (null when Kinde couldn't be reached). */
    private fun tryOldToken(refreshToken: String): Pair<String, Boolean?> {
        val config = KindeHub.config
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .build()
        val request = Request.Builder().url("https://${config.domain}/oauth2/token").post(form).build()
        return try {
            http.newCall(request).execute().use { resp ->
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                if (resp.isSuccessful && json?.has("access_token") == true) {
                    // Kinde rotates refresh tokens: revoke the replacement so nothing is left behind.
                    val cleanup = json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { "; the playground revoked the replacement it got: ${revoke(it)}" }.orEmpty()
                    "${resp.code} accepted, new access token ${Redact.mask(json.optString("access_token"))}$cleanup" to true
                } else {
                    "${resp.code} rejected (${json?.optString("error") ?: "no body"})" to false
                }
            }
        } catch (e: Exception) {
            Redact.error(e) to null
        }
    }

    private fun revoke(token: String): String = try {
        val config = KindeHub.config
        val form = FormBody.Builder()
            .add("token", token)
            .add("token_type_hint", "refresh_token")
            .add("client_id", config.clientId)
            .build()
        http.newCall(Request.Builder().url("https://${config.domain}/oauth2/revoke").post(form).build())
            .execute().use { "revoke ${it.code}" }
    } catch (e: Exception) {
        "revoke failed: ${Redact.error(e)}"
    }
}
