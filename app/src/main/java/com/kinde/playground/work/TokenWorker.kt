package com.kinde.playground.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.kinde.sdk.KindeClient
import au.kinde.sdk.api.ApiOptions
import au.kinde.sdk.model.TokenType
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A background job with no activity in sight: reads the token and calls the
 * Account API through the process-wide KindeClient.
 */
class TokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (KindeHub.clientOrNull(applicationContext) == null) {
            return Result.success(workDataOf("summary" to "Not configured."))
        }
        val client = KindeClient.getInstance(applicationContext)
        val before = client.getToken(TokenType.ACCESS_TOKEN)
        val expiredFor = com.kinde.playground.core.Jwt.decode(before)?.secondsLeft()?.let { -it }
        val permissions = runCatching { client.getPermissions(ApiOptions(forceApi = true, useCache = false)) }
        val after = client.getToken(TokenType.ACCESS_TOKEN)
        val summary = buildString {
            append("Ran at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} on ${Thread.currentThread().name}\n")
            append("Access token before: ${Redact.mask(before)}")
            if (expiredFor != null && expiredFor > 0) append(" (expired ${expiredFor}s ago)")
            append("\n")
            append("getPermissions(forceApi): ")
            append(permissions.fold({ "${it.permissions.size} permission(s) in ${it.orgCode.ifBlank { "no org" }}" }, { Redact.error(it) }))
            append("\n")
            append("Access token after: ${if (after == before) "unchanged" else "renewed: ${Redact.mask(after)}"}")
        }
        return Result.success(workDataOf("summary" to summary))
    }
}

/** The pattern for calling your own backend: add the Kinde access token in an interceptor. */
class KindeAuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = KindeClient.getInstance(context).getToken(TokenType.ACCESS_TOKEN)
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}

object InterceptorDemo {
    /** Calls Kinde's user profile endpoint as a stand-in for "your API". */
    fun call(context: Context, domain: String): String {
        val http = OkHttpClient.Builder().addInterceptor(KindeAuthInterceptor(context.applicationContext)).build()
        val request = Request.Builder().url("https://$domain/oauth2/v2/user_profile").build()
        return http.newCall(request).execute().use { resp ->
            "GET ${request.url} came back ${resp.code}. Authorization header ${if (resp.request.header("Authorization") != null) "added by the interceptor" else "missing (signed out)"}."
        }
    }
}
