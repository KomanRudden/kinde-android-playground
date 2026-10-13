package com.kinde.playground.checks

import android.content.Context
import au.kinde.sdk.KindeClient
import au.kinde.sdk.api.ApiOptions
import au.kinde.sdk.model.ClaimData
import au.kinde.sdk.model.Flag
import au.kinde.sdk.model.TokenType
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.Jwt
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.SdkLog
import com.kinde.playground.checks.Judge.shown

/**
 * Live reads against your Kinde business with the signed-in user's token: the
 * token claims, the OAuth user profile endpoints and the Account API. Must run
 * off the main thread. A missing permission or plan fails one check only.
 */
object LiveChecks {

    fun run(context: Context, config: PlaygroundConfig = KindeHub.config): CheckRun {
        val started = System.currentTimeMillis()
        val client = KindeHub.clientOrNull(context)
        val checks = mutableListOf<Check>()
        if (client == null) {
            checks += Judge.skipped("Signed-in user", "Your user", "Would read your user's data from Kinde.",
                "The playground isn't configured. Fill in .env and rebuild.")
        } else if (!client.isAuthenticated()) {
            checks += Judge.skipped("Signed-in user", "Your user", "Would read claims, profile, permissions, roles, flags and entitlements.",
                "Sign in first (Sign-in lab), then run these again.")
        } else {
            checks += tokenChecks(client, config)
            checks += profileChecks(client)
            checks += accessChecks(client, config)
            checks += flagChecks(client, config)
            checks += cacheChecks(client, config)
            checks += billingChecks(client, config)
            checks += managementChecks(client)
        }
        return CheckRun("Live", checks, System.currentTimeMillis() - started)
    }

    private class Ran(val value: Any?, val error: Throwable?, val http: List<SdkLog.HttpCall>, val sdkExceptions: List<String>)

    private fun ran(block: () -> Any?): Ran {
        val before = KindeHub.events.value.firstOrNull()?.id ?: -1
        val (outcome, http) = SdkLog.capture { runCatching(block) }
        val exceptions = KindeHub.events.value
            .filter { it.id > before && it.type == KindeHub.EventType.EXCEPTION }
            .map { it.detail }
        return Ran(outcome.getOrNull(), outcome.exceptionOrNull(), http, exceptions)
    }

    private fun check(
        group: String, name: String, did: String, expectsHttp: Boolean, subject: String = name,
        nullIsFailure: Boolean = true, success: (Any?) -> String, block: () -> Any?,
    ): Check {
        val r = ran(block)
        return Judge.judge(group, name, did, r.value, r.error, r.http, r.sdkExceptions, expectsHttp, subject, success, nullIsFailure)
    }

    // -- Tokens ---------------------------------------------------------------

    private fun tokenChecks(client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "Tokens"
        val access = Jwt.decode(client.getToken(TokenType.ACCESS_TOKEN))
        val id = Jwt.decode(client.getToken(TokenType.ID_TOKEN))
        val checks = mutableListOf<Check>()
        val issuer = "https://${config.domain}"

        checks += if (access == null) {
            Check(g, "Access token", "Decodes the access token.", Outcome.FAILED, Judge.NO_HTTP_TOKEN, "There is no readable access token.")
        } else {
            val left = access.secondsLeft() ?: 0
            val problems = buildList {
                if (access.iss?.trimEnd('/') != issuer) add("issuer is ${access.iss}, expected $issuer")
                if (left <= 0) add("it expired ${-left}s ago, yet isAuthenticated() is true")
                if (config.audience != null && config.audience !in access.audiences) add("audience ${access.audiences} lacks ${config.audience}")
            }
            Check(g, "Access token", "Decodes the access token and checks issuer, expiry and audience.",
                if (problems.isEmpty()) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP_TOKEN,
                if (problems.isEmpty()) "Issued by ${access.iss}, expires in ${left}s, signed with ${access.alg} key ${access.kid}."
                else problems.joinToString("; ").replaceFirstChar { it.uppercase() } + ".")
        }

        checks += if (id == null) {
            Check(g, "ID token", "Decodes the ID token.", if ("openid" in config.scopes) Outcome.FAILED else Outcome.SKIPPED,
                Judge.NO_HTTP_TOKEN, "There is no ID token. It needs the openid scope.")
        } else {
            val audOk = config.clientId in id.audiences
            Check(g, "ID token", "Checks the ID token was issued to this app.",
                if (audOk) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP_TOKEN,
                if (audOk) "Issued to this client for ${id.payload["email"] ?: id.sub}." else "Audience ${id.audiences} doesn't include this client ID.")
        }

        val refresh = client.getRefreshToken()
        checks += Check(g, "Refresh token", "Checks the session can be refreshed.",
            when {
                !refresh.isNullOrBlank() -> Outcome.PASSED
                "offline" in config.scopes -> Outcome.FAILED
                else -> Outcome.WARN
            },
            Judge.NO_HTTP_TOKEN,
            if (!refresh.isNullOrBlank()) "A refresh token is present. The SDK refreshes 10s before the access token expires while the app is in the foreground."
            else "No refresh token. Add the offline scope, and allow refresh tokens on the Kinde application.")

        checks += check(g, "Claims helper", "Reads sub, email and names with getUserDetails().", false,
            success = { d -> (d as au.kinde.sdk.model.UserDetails).let { "Signed in as ${it.email.ifBlank { "(no email claim)" }} (${it.id})." } }
        ) { client.getUserDetails().takeIf { it.id.isNotBlank() } }
        return checks
    }

    // -- Profile ----------------------------------------------------------------

    private fun profileChecks(client: KindeClient): List<Check> {
        val g = "User profile"
        return listOf(
            check(g, "getUser()", "Calls GET /oauth2/user_profile.", true, "the user profile",
                success = { p -> (p as au.kinde.sdk.api.model.UserProfile).let { "Profile ${it.id}: ${it.firstName.orEmpty()} ${it.lastName.orEmpty()} <${it.preferredEmail.orEmpty()}>." } }
            ) { client.getUser() },
            check(g, "getUserProfileV2()", "Calls GET /oauth2/v2/user_profile.", true, "the v2 user profile",
                success = { p -> (p as au.kinde.sdk.api.model.UserProfileV2).let { "Profile ${it.id}: ${it.name.orEmpty()}, updated ${it.updatedAt}." } }
            ) { client.getUserProfileV2() },
        )
    }

    // -- Permissions and roles ------------------------------------------------------

    private fun accessChecks(client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "Permissions & roles"
        val api = ApiOptions(forceApi = true, useCache = false)
        val checks = mutableListOf<Check>()
        val fromToken = client.getPermissions()
        var fromApi: ClaimData.Permissions? = null
        checks += check(g, "Permissions from the Account API", "Calls getPermissions(ApiOptions(forceApi = true)).", true, "permissions",
            success = { p -> (p as ClaimData.Permissions).let { "${it.permissions.size} permission(s) in ${it.orgCode.ifBlank { "no org" }}: ${it.permissions.take(5)}." } }
        ) { client.getPermissions(api).also { fromApi = it } }
        fromApi?.let { a ->
            val same = a.permissions.toSet() == fromToken.permissions.toSet()
            checks += Check(g, "Token and API agree on permissions", "Compares the permissions claim with the Account API.",
                if (same) Outcome.PASSED else Outcome.WARN, Judge.NO_HTTP_TOKEN,
                if (same) "Both list the same ${a.permissions.size} permission(s)."
                else "The token has ${fromToken.permissions}, the API has ${a.permissions}. Add permissions to the access token (application > Tokens) or use forceApi.")
        }
        checks += check(g, "Permission ${config.demoPermission}", "Calls getPermission(\"${config.demoPermission}\", forceApi).", true, config.demoPermission,
            success = { p -> (p as ClaimData.Permission).let { "${config.demoPermission} is ${if (it.isGranted) "granted" else "not granted"}${if (it.orgCode.isNotBlank()) " in ${it.orgCode}" else ""}." } }
        ) { client.getPermission(config.demoPermission, api) }

        val rolesToken = client.getRoles()
        var rolesApi: ClaimData.Roles? = null
        checks += check(g, "Roles from the Account API", "Calls getRoles(ApiOptions(forceApi = true)).", true, "roles",
            success = { r -> (r as ClaimData.Roles).let { "${it.roles.size} role(s): ${it.roles.take(5)}." } }
        ) { client.getRoles(api).also { rolesApi = it } }
        rolesApi?.let { a ->
            val same = a.roles.toSet() == rolesToken.roles.toSet()
            val objectShaped = rolesToken.roles.any { it.trimStart().startsWith("{") }
            val keys = rolesToken.roles.map { r -> runCatching { org.json.JSONObject(r).optString("key") }.getOrNull()?.takeIf { it.isNotBlank() } ?: r }
            checks += when {
                same -> Check(g, "Token and API agree on roles", "Compares the roles claim with the Account API.",
                    Outcome.PASSED, Judge.NO_HTTP_TOKEN, "Both list the same ${a.roles.size} role(s).")
                objectShaped -> Check(g, "Roles from the token", "Reads the roles claim with getRoles() and getRole(\"${config.demoRole}\").",
                    Outcome.FAILED, Judge.NO_HTTP_TOKEN,
                    "Kinde's roles claim holds objects ($keys), but the SDK returns each one as raw JSON text, so getRole(\"${config.demoRole}\") from the token is ${client.getRole(config.demoRole).isGranted}. Use ApiOptions(forceApi = true) until the SDK is fixed.")
                rolesToken.roles.isEmpty() -> Check(g, "Token and API agree on roles", "Compares the roles claim with the Account API.",
                    Outcome.WARN, Judge.NO_HTTP_TOKEN, "The token has no roles but the API has ${a.roles}. Add roles to the access token (application > Tokens) or use forceApi.")
                else -> Check(g, "Token and API agree on roles", "Compares the roles claim with the Account API.",
                    Outcome.WARN, Judge.NO_HTTP_TOKEN, "The token has ${rolesToken.roles}, the API has ${a.roles}. Token claims can be stale until the next refresh.")
            }
        }
        checks += check(g, "Role ${config.demoRole}", "Calls getRole(\"${config.demoRole}\", forceApi).", true, config.demoRole,
            success = { r -> (r as ClaimData.Role).let { "Role ${config.demoRole} is ${if (it.isGranted) "assigned" else "not assigned"}." } }
        ) { client.getRole(config.demoRole, api) }
        val org = client.getOrganization().orgCode
        val orgs = client.getUserOrganizations().orgCodes
        checks += Check(g, "Organizations", "Reads org_code from the access token and org_codes from the ID token.",
            if (org.isNotBlank()) Outcome.PASSED else Outcome.WARN, Judge.NO_HTTP_TOKEN,
            if (org.isNotBlank()) "Current org $org; member of ${orgs.ifEmpty { listOf("(org_codes claim missing)") }}."
            else "No org_code claim. Permissions and roles are org-scoped, so they'll be empty.")
        return checks
    }

    // -- Feature flags -----------------------------------------------------------

    private fun flagSentence(flag: Flag?, where: String): String = when {
        flag == null -> "No flag came back (missing, or a different type than asked for)."
        flag.isDefault -> "${flag.code} isn't on $where, so the default ${shown(flag.value)} was used."
        else -> "${flag.code} came back from $where as ${flag.type} ${shown(flag.value)} (${flag.value.javaClass.simpleName})."
    }

    private fun flagChecks(client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "Feature flags"
        val api = ApiOptions(forceApi = true, useCache = false)
        val checks = mutableListOf<Check>()
        checks += check(g, "Flag ${config.demoFlag} from the token", "Calls getFlag(\"${config.demoFlag}\", false).", false,
            success = { flagSentence(it as Flag?, "the token") }
        ) { client.getFlag(config.demoFlag, false) }
        checks += check(g, "Boolean flag from the Account API", "Calls getBooleanFlag(\"${config.demoFlag}\", false, forceApi).", true, config.demoFlag,
            nullIsFailure = false, success = { "${config.demoFlag} = ${shown(it)}." }
        ) { client.getBooleanFlag(config.demoFlag, false, api) }
        checks += check(g, "String flag from the Account API", "Calls getStringFlag(\"${config.demoStringFlag}\", \"default\", forceApi).", true, config.demoStringFlag,
            nullIsFailure = false, success = { "${config.demoStringFlag} = ${shown(it)}." }
        ) { client.getStringFlag(config.demoStringFlag, "default", api) }
        checks += check(g, "Integer flag from the Account API", "Calls getIntegerFlag(\"${config.demoIntFlag}\", -1, forceApi).", true, config.demoIntFlag,
            nullIsFailure = false, success = { "${config.demoIntFlag} = ${shown(it)}." }
        ) { client.getIntegerFlag(config.demoIntFlag, -1, api) }

        var apiFlags: Map<String, Flag> = emptyMap()
        checks += check(g, "All flags from the Account API", "Calls getAllFlags(forceApi).", true, "flags",
            success = { m -> (m as Map<*, *>).let { if (it.isEmpty()) "No flags came back." else "${it.size} flag(s): ${it.keys.take(6)}." } }
        ) { client.getAllFlags(api).also { apiFlags = it } }
        val tokenFlags = client.getAllFlags()
        if (apiFlags.isNotEmpty() || tokenFlags.isNotEmpty()) {
            val diffs = (apiFlags.keys + tokenFlags.keys).filter { key ->
                val a = apiFlags[key]?.value
                val t = tokenFlags[key]?.value
                !(a == t || (a is Number && t is Number && a.toDouble() == t.toDouble()))
            }
            checks += Check(g, "Token and API agree on flags", "Compares the feature_flags claim with the Account API.",
                if (diffs.isEmpty()) Outcome.PASSED else Outcome.WARN, Judge.NO_HTTP_TOKEN,
                if (diffs.isEmpty()) "Both report the same ${apiFlags.size} flag value(s)."
                else "These differ: ${diffs.take(5)}. Token flags can be stale until the next refresh.")
            val intTypes = apiFlags.values.filter { it.type == au.kinde.sdk.model.FlagType.Integer }.map { it.value.javaClass.simpleName }.distinct()
            if (intTypes.isNotEmpty()) {
                checks += Check(g, "Integer flag value type", "Looks at the runtime type of integer flags from getAllFlags(forceApi).",
                    if (intTypes == listOf("Integer")) Outcome.PASSED else Outcome.WARN, Judge.NO_HTTP_TOKEN,
                    if (intTypes == listOf("Integer")) "Integer flags are Ints."
                    else "Integer flags arrive as $intTypes from the API but as Integer from the token. getIntegerFlag converts; getAllFlags/getFlag callers see 5.0 instead of 5.")
            }
        }
        return checks
    }

    // -- Caching -------------------------------------------------------------------

    private fun cacheChecks(client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "Caching"
        if (!SdkLog.httpLoggingAvailable) {
            return listOf(Judge.skipped(g, "Account API cache", "Would count requests across cached calls.",
                "The SDK in this build doesn't log HTTP calls, so requests can't be counted."))
        }
        client.clearCache()
        val first = ran { client.getRoles(ApiOptions(forceApi = true)) }
        val second = ran { client.getRoles(ApiOptions(forceApi = true)) }
        val bypass = ran { client.getRoles(ApiOptions(forceApi = true, useCache = false)) }
        client.clearCache()
        val afterClear = ran { client.getRoles(ApiOptions(forceApi = true)) }
        val n = { r: Ran -> r.http.count { it.url.contains("account_api/v1/roles") } }
        val ok = n(first) == 1 && n(second) == 0 && n(bypass) == 1 && n(afterClear) == 1
        return listOf(Check(g, "Account API cache", "Calls getRoles(forceApi) four times: cold, cached, useCache=false, after clearCache().",
            if (ok) Outcome.PASSED else Outcome.FAILED,
            "Requests to /account_api/v1/roles per call: ${n(first)}, ${n(second)}, ${n(bypass)}, ${n(afterClear)}.",
            if (ok) "The 60-second cache served the second call; useCache=false and clearCache() went to Kinde."
            else "Expected 1, 0, 1, 1."))
    }

    // -- Billing ---------------------------------------------------------------------

    private fun billingChecks(client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "Billing"
        var keys: List<String>? = null
        val all = check(g, "getEntitlements()", "Calls GET /account_api/v1/entitlements.", true, "entitlements",
            success = { e -> (e as au.kinde.sdk.api.model.entitlements.EntitlementsResponse).let {
                "${it.data.entitlements.size} entitlement(s), plans ${it.data.plans.map { p -> p.key }}. More pages: ${it.metadata.hasMore} (the SDK can't request them)."
            } }
        ) { client.getEntitlements().also { r -> keys = r?.data?.entitlements?.map { it.featureKey } } }
        // Asking for a feature the org isn't entitled to gets HTTP 400, so only ask for one that exists.
        val available = keys
        val key = when {
            available == null -> config.demoEntitlement
            config.demoEntitlement in available -> config.demoEntitlement
            else -> available.firstOrNull()
        }
        val one = if (key == null) {
            Judge.skipped(g, "getEntitlement()", "Would call GET /account_api/v1/entitlement?key=…",
                "This org has no entitlements (no billing plan), so there is no feature to look up. Set up billing in Kinde to run this.")
        } else {
            val note = if (key != config.demoEntitlement) " ${config.demoEntitlement} isn't one of this org's entitlements, so $key was used." else ""
            check(g, "getEntitlement(\"$key\")", "Calls GET /account_api/v1/entitlement?key=$key.", true, key,
                success = { e -> (e as au.kinde.sdk.api.model.entitlements.EntitlementResponse).data.entitlement.let {
                    "${it.featureKey}: limit ${it.entitlementLimitMin ?: 0}–${it.entitlementLimitMax}.$note"
                } }
            ) { client.getEntitlement(key) }
        }
        return listOf(all, one)
    }

    // -- Management API via the SDK -------------------------------------------------------

    @Suppress("DEPRECATION") // Shows what the deprecated call does against Kinde.
    private fun managementChecks(client: KindeClient): List<Check> {
        val g = "Management API (SDK)"
        return listOf(
            check(g, "getUsers(pageSize = 1)", "Calls GET /api/v1/users with the user's access token.", true, "users",
                success = { u -> (u as List<*>).let { "${it.size} user(s) came back." } }
            ) { client.getUsers(pageSize = 1) }.let { c ->
                if (c.outcome == Outcome.FAILED) c.copy(result = c.result + " The Management API expects an M2M token (getUsers() is deprecated in newer SDKs for this reason), and the SDK's model expects a JSON array of users with numeric ids; Kinde returns an object with a users array and kp_ string ids.") else c
            },
        )
    }
}
