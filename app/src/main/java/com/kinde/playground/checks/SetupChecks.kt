package com.kinde.playground.checks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import au.kinde.sdk.store.Store
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.Http
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import org.json.JSONObject

/** Checks that need no sign-in: configuration, Kinde endpoints, and this device. */
object SetupChecks {
    private const val GROUP = "Setup"

    fun run(context: Context, config: PlaygroundConfig = KindeHub.config): CheckRun {
        val started = System.currentTimeMillis()
        val checks = mutableListOf<Check>()
        checks += configCheck(config)
        if (config.isConfigured) {
            checks += startupCheck()
            checks += discoveryChecks(config)
            checks += jwksChecks(context, config)
        }
        checks += redirectCheck(context, config)
        checks += browserCheck(context)
        return CheckRun("Setup", checks, System.currentTimeMillis() - started)
    }

    private fun configCheck(config: PlaygroundConfig): Check {
        val problems = config.problems()
        val blocking = problems.filter { it.blocking }
        val outcome = when {
            blocking.isNotEmpty() -> Outcome.FAILED
            problems.isNotEmpty() -> Outcome.WARN
            else -> Outcome.PASSED
        }
        val result = if (problems.isEmpty()) {
            "Domain, client ID, redirect scheme and scopes are valid."
        } else {
            problems.joinToString(" ") { "${it.key}: ${it.message}" } +
                if (blocking.isNotEmpty()) " Fill in .env and rebuild." else ""
        }
        return Check(GROUP, "Configuration", "Validates the values compiled in from .env.", outcome, Judge.NO_HTTP, result)
    }

    private fun startupCheck(): Check {
        val error = KindeHub.startupError
        return Check(
            GROUP, "KindeClient started",
            "Creates the process-wide KindeClient in Application.onCreate.",
            if (error == null) Outcome.PASSED else Outcome.FAILED,
            Judge.NO_HTTP,
            if (error == null) "The client was created with ${KindeHub.config.configMode.name.lowercase()} configuration."
            else "The SDK refused the configuration: ${Redact.error(error)}",
        )
    }

    private fun discoveryChecks(config: PlaygroundConfig): List<Check> {
        val base = "https://${config.domain}"
        val resp = Http.getJson("$base/.well-known/openid-configuration")
        val http = "GET ${resp.url} " + (resp.status?.let { "came back $it." } ?: "did not complete.")
        val json = resp.json
        if (resp.status != 200 || json == null) {
            return listOf(
                Check(
                    GROUP, "OIDC discovery", "Fetches the domain's OpenID configuration.", Outcome.FAILED, http,
                    resp.error?.let { "No response: ${Redact.error(it)}. Check KINDE_DOMAIN and the emulator's network." }
                        ?: "Expected HTTP 200 with JSON. Is ${config.domain} a Kinde domain?"
                )
            )
        }
        val issuer = json.optString("issuer")
        val issuerOk = issuer.trimEnd('/') == base
        val checks = mutableListOf(
            Check(
                GROUP, "OIDC discovery", "Fetches the domain's OpenID configuration.",
                if (issuerOk) Outcome.PASSED else Outcome.WARN, http,
                if (issuerOk) "The issuer is $issuer." else "The issuer is $issuer, not $base. Tokens will name a different issuer than the domain the SDK calls."
            )
        )
        // The SDK does not use discovery: it builds these URLs itself. Make sure they agree.
        val expected = mapOf(
            "authorization_endpoint" to "$base/oauth2/auth",
            "token_endpoint" to "$base/oauth2/token",
            "end_session_endpoint" to "$base/logout",
        )
        val mismatches = expected.filter { (key, url) -> json.optString(key).trimEnd('/') != url }
        checks += Check(
            GROUP, "SDK endpoints match discovery",
            "Compares the endpoint paths the SDK hard-codes with the ones Kinde advertises.",
            if (mismatches.isEmpty()) Outcome.PASSED else Outcome.FAILED,
            "Uses the discovery document above.",
            if (mismatches.isEmpty()) "Authorize, token and logout URLs all match."
            else mismatches.entries.joinToString(" ") { "${it.key}: SDK uses ${it.value}, Kinde says ${json.optString(it.key)}." }
        )
        // The SDK fetches /.well-known/jwks.json; discovery may advertise another path
        // (custom domains advertise /.well-known/jwks). What matters is the same keys.
        val advertised = json.optString("jwks_uri")
        val sdkJwks = "$base/.well-known/jwks.json"
        if (advertised.isNotBlank() && advertised != sdkJwks) {
            val kids = { url: String -> Http.getJson(url).json?.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("kid") }.toSet() } }
            val fromSdk = kids(sdkJwks)
            val fromDiscovery = kids(advertised)
            val same = fromSdk != null && fromSdk == fromDiscovery
            checks += Check(
                GROUP, "SDK JWKS path", "Fetches the JWKS from the path the SDK uses and from the one discovery advertises.",
                if (same) Outcome.PASSED else Outcome.FAILED,
                "GET $sdkJwks and GET $advertised.",
                if (same) "Different paths, same keys: discovery advertises $advertised, the SDK uses $sdkJwks."
                else "The SDK's $sdkJwks serves ${fromSdk ?: "no keys"} but discovery's $advertised serves ${fromDiscovery ?: "no keys"}. Token verification will fail."
            )
        }
        val methods = json.optJSONArray("code_challenge_methods_supported")
        val s256 = methods != null && (0 until methods.length()).any { methods.optString(it) == "S256" }
        checks += Check(
            GROUP, "PKCE supported", "Reads code_challenge_methods_supported from discovery.",
            if (s256) Outcome.PASSED else Outcome.WARN, "Uses the discovery document above.",
            if (s256) "Kinde supports S256 PKCE. Sign in with GrantType.PKCE: the SDK only sends a code challenge when asked."
            else "S256 is not listed."
        )
        return checks
    }

    private fun jwksChecks(context: Context, config: PlaygroundConfig): List<Check> {
        val url = "https://${config.domain}/.well-known/jwks.json"
        val resp = Http.getJson(url)
        val http = "GET $url " + (resp.status?.let { "came back $it." } ?: "did not complete.")
        val keys = resp.json?.optJSONArray("keys")
        if (resp.status != 200 || keys == null) {
            return listOf(Check(GROUP, "Signing keys", "Fetches the JWKS the SDK verifies tokens with.", Outcome.FAILED, http,
                resp.error?.let { Redact.error(it) } ?: "No keys array in the response."))
        }
        val liveKids = (0 until keys.length()).map { keys.getJSONObject(it).optString("kid") }
        val checks = mutableListOf(
            Check(
                GROUP, "Signing keys", "Fetches the JWKS the SDK verifies tokens with.",
                if (keys.length() == 1) Outcome.PASSED else Outcome.WARN, http,
                if (keys.length() == 1) "1 key (${liveKids.first()})."
                else "${keys.length()} keys, e.g. during a key rotation. SDK 2.2.0 verifies with the first key only and ignores the token's kid, so tokens signed by another key read as signed out; newer SDKs use the kid."
            )
        )
        val stored = runCatching { Store(context, config.domain).getKeys() }.getOrNull()
        val storedKids = stored?.let { s ->
            runCatching {
                val arr = JSONObject(s).getJSONArray("keys")
                (0 until arr.length()).map { arr.getJSONObject(it).optString("kid") }
            }.getOrNull()
        }
        checks += when {
            stored == null -> Check(GROUP, "Keys cached by the SDK", "Reads the JWKS the SDK saved on this device.", Outcome.INFO,
                Judge.NO_HTTP, "The SDK hasn't saved keys yet. It fetches them when the client starts.")
            storedKids == liveKids -> Check(GROUP, "Keys cached by the SDK", "Reads the JWKS the SDK saved on this device.", Outcome.PASSED,
                Judge.NO_HTTP, "The SDK's saved keys match Kinde's current keys.")
            else -> Check(GROUP, "Keys cached by the SDK", "Reads the JWKS the SDK saved on this device.", Outcome.FAILED,
                Judge.NO_HTTP, "The SDK saved ${storedKids ?: "unreadable keys"} but Kinde now serves $liveKids. SDK 2.2.0 never refreshes saved keys, so sessions fail verification until the app's data is cleared; newer SDKs refresh them when a token names an unknown key.")
        }
        return checks
    }

    private fun redirectCheck(context: Context, config: PlaygroundConfig): Check {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.loginRedirect)).setPackage(context.packageName)
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val names = handlers.map { it.activityInfo.name.substringAfterLast('.') }
        val ok = names.any { it == "RedirectUriReceiverActivity" }
        return Check(
            GROUP, "Redirect scheme", "Resolves ${config.loginRedirect} inside this app.",
            if (ok) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP,
            if (ok) "AppAuth's RedirectUriReceiverActivity receives it (manifest placeholder appAuthRedirectScheme=${config.redirectScheme}). Register ${config.loginRedirect} and ${config.logoutRedirect} in Kinde."
            else "No activity handles it: appAuthRedirectScheme doesn't match KINDE_REDIRECT_SCHEME. Found: $names"
        )
    }

    private fun browserCheck(context: Context): Check {
        val pm = context.packageManager
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.distinct()
        val tabs = browsers.filter { pkg ->
            pm.resolveService(Intent("android.support.customtabs.action.CustomTabsService").setPackage(pkg), 0) != null
        }
        return Check(
            GROUP, "Browser for sign-in", "Looks for a browser that supports Custom Tabs.",
            when {
                tabs.isNotEmpty() -> Outcome.PASSED
                browsers.isNotEmpty() -> Outcome.WARN
                else -> Outcome.FAILED
            },
            Judge.NO_HTTP,
            when {
                tabs.isNotEmpty() -> "Sign-in opens in a Custom Tab (${tabs.first()})."
                browsers.isNotEmpty() -> "Sign-in opens in ${browsers.first()}, which has no Custom Tabs support."
                else -> "No browser is installed. Sign-in can't start: use an emulator image with Google APIs (it ships Chrome)."
            }
        )
    }
}
