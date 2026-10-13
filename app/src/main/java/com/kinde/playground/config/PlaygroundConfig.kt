package com.kinde.playground.config

import com.kinde.playground.BuildConfig
import com.kinde.playground.core.Redact

enum class ConfigMode { PROGRAMMATIC, MANIFEST }

/**
 * Everything the playground was built with, from .env via app/build.gradle.
 * Constructed from plain values so it can be validated in unit tests.
 */
data class PlaygroundConfig(
    val domain: String,
    val clientId: String,
    val audience: String?,
    val redirectScheme: String,
    val configModeRaw: String,
    val scopes: List<String>,
    val demoPermission: String,
    val demoRole: String,
    val demoFlag: String,
    val demoStringFlag: String,
    val demoIntFlag: String,
    val demoEntitlement: String,
    val demoOrgCode: String?,
    val connectionId: String?,
    val pricingTableKey: String?,
    val allowMutations: Boolean,
    val debug: Boolean,
    val sdkSource: String,
) {
    val loginRedirect: String get() = "$redirectScheme://kinde_callback"
    val logoutRedirect: String get() = "$redirectScheme://kinde_logout"

    val configMode: ConfigMode
        get() = if (configModeRaw.equals("manifest", ignoreCase = true)) ConfigMode.MANIFEST else ConfigMode.PROGRAMMATIC

    val isConfigured: Boolean get() = problems().none { it.blocking }

    data class Problem(val key: String, val message: String, val blocking: Boolean)

    /** Same rules as the SDK, so a bad .env is reported here instead of crashing on launch. */
    fun problems(): List<Problem> = buildList {
        if (domain.isBlank()) {
            add(Problem("KINDE_DOMAIN", "Not set. Use your Kinde domain, e.g. your-business.kinde.com.", true))
        } else if (!HOST.matches(domain)) {
            add(Problem("KINDE_DOMAIN", "Must be a bare host name like your-business.kinde.com (no path or port).", true))
        }
        if (clientId.isBlank()) {
            add(Problem("KINDE_CLIENT_ID", "Not set. Copy it from your Android application in Kinde.", true))
        } else if (clientId.any { it.isWhitespace() || it.isISOControl() }) {
            add(Problem("KINDE_CLIENT_ID", "Contains whitespace.", true))
        }
        if (!SCHEME.matches(redirectScheme)) {
            add(Problem("KINDE_REDIRECT_SCHEME", "Must be a URI scheme such as com.kinde.playground.", true))
        }
        if (!configModeRaw.equals("manifest", true) && !configModeRaw.equals("programmatic", true)) {
            add(Problem("PLAYGROUND_CONFIG_MODE", "Use programmatic or manifest. Falling back to programmatic.", false))
        }
        if ("openid" !in scopes) {
            add(Problem("PLAYGROUND_SCOPES", "Without openid there is no ID token, so profile claims are empty.", false))
        }
        if ("offline" !in scopes) {
            add(Problem("PLAYGROUND_SCOPES", "Without offline there is no refresh token, so the session ends when the access token expires.", false))
        }
    }

    /** Rows for the dashboard. Client ID is masked: it is not secret, but it is not ours to print in screenshots. */
    fun describe(): List<Pair<String, String>> = listOf(
        "KINDE_DOMAIN" to domain.ifBlank { "(not set)" },
        "KINDE_CLIENT_ID" to (if (clientId.isBlank()) "(not set)" else Redact.mask(clientId)),
        "KINDE_AUDIENCE" to (audience ?: "(none)"),
        "Config mode" to when (configMode) {
            ConfigMode.PROGRAMMATIC -> "KindeClient.getInstance(context, KindeConfig(...))"
            ConfigMode.MANIFEST -> "AndroidManifest meta-data"
        },
        "Login redirect" to loginRedirect,
        "Logout redirect" to logoutRedirect,
        "Scopes" to scopes.joinToString(" "),
        "SDK" to sdkSource,
        "Management writes" to if (allowMutations) "enabled" else "off",
        "Token reveal" to if (debug) "enabled" else "off",
    )

    companion object {
        private val HOST =
            Regex("^(?!.{256,})[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")

        val current: PlaygroundConfig by lazy {
            PlaygroundConfig(
                domain = BuildConfig.KINDE_DOMAIN,
                clientId = BuildConfig.KINDE_CLIENT_ID,
                audience = BuildConfig.KINDE_AUDIENCE.ifBlank { null },
                redirectScheme = BuildConfig.KINDE_REDIRECT_SCHEME,
                configModeRaw = BuildConfig.PLAYGROUND_CONFIG_MODE,
                scopes = BuildConfig.PLAYGROUND_SCOPES.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() },
                demoPermission = BuildConfig.PLAYGROUND_DEMO_PERMISSION,
                demoRole = BuildConfig.PLAYGROUND_DEMO_ROLE,
                demoFlag = BuildConfig.PLAYGROUND_DEMO_FLAG,
                demoStringFlag = BuildConfig.PLAYGROUND_DEMO_STRING_FLAG,
                demoIntFlag = BuildConfig.PLAYGROUND_DEMO_INT_FLAG,
                demoEntitlement = BuildConfig.PLAYGROUND_DEMO_ENTITLEMENT,
                demoOrgCode = BuildConfig.PLAYGROUND_DEMO_ORG_CODE.ifBlank { null },
                connectionId = BuildConfig.PLAYGROUND_CONNECTION_ID.ifBlank { null },
                pricingTableKey = BuildConfig.PLAYGROUND_PRICING_TABLE.ifBlank { null },
                allowMutations = BuildConfig.PLAYGROUND_ALLOW_MUTATIONS,
                debug = BuildConfig.PLAYGROUND_DEBUG,
                sdkSource = BuildConfig.SDK_SOURCE,
            )
        }
    }
}
