package com.kinde.playground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import au.kinde.sdk.api.ApiOptions
import au.kinde.sdk.model.FlagType
import au.kinde.sdk.model.TokenType
import com.kinde.playground.checks.Outcome
import com.kinde.playground.core.Jwt
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import com.kinde.playground.ui.LocalEnv
import com.kinde.playground.ui.Runner
import com.kinde.playground.ui.components.*
import com.kinde.playground.ui.rememberRunner
import kotlinx.coroutines.delay

/** Button plus its latest result. */
@Composable
private fun Call(runner: Runner, label: String, enabled: Boolean = true, secondary: Boolean = false, onMain: Boolean = false, block: () -> Any?) {
    ActionButton(label, { runner.run(label, onMain, block) }, enabled = enabled && runner.busy == null, busy = runner.busy == label, secondary = secondary)
}

@Composable
private fun Results(runner: Runner, vararg labels: String) {
    labels.forEach { l -> runner.results[l]?.let { CallResultView(it) } }
}

@Composable
private fun ApiToggles(forceApi: Boolean, onForce: (Boolean) -> Unit, useCache: Boolean, onCache: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(forceApi, onForce)
        Text("forceApi", style = MaterialTheme.typography.bodySmall)
        Switch(useCache, onCache, enabled = forceApi)
        Text("useCache", style = MaterialTheme.typography.bodySmall)
    }
}

// -- Profile ----------------------------------------------------------------------------

@Composable
fun ProfilePage() {
    val sdk = LocalEnv.current.sdk
    val runner = rememberRunner()
    SectionCard("From the ID token", "getUserDetails() reads sub, given_name, family_name, email and picture claims. No network.") {
        ButtonRow { Call(runner, "getUserDetails()", sdk != null) { sdk?.getUserDetails() } }
        Results(runner, "getUserDetails()")
    }
    SectionCard("From Kinde", "Both call the OAuth user profile endpoints with the access token, on a background thread.") {
        ButtonRow {
            Call(runner, "getUser()", sdk != null) { sdk?.getUser() }
            Call(runner, "getUserProfileV2()", sdk != null, secondary = true) { sdk?.getUserProfileV2() }
        }
        Results(runner, "getUser()", "getUserProfileV2()")
        Note("UserProfileV2 has no email, picture, sub or email_verified fields, although Kinde returns them. Use getUserDetails() or claims for those.")
    }
    SectionCard("On the main thread", "Network calls on the main thread are refused: the SDK returns null and reports WrongThreadException.") {
        ButtonRow { Call(runner, "getUser() on main", sdk != null, secondary = true, onMain = true) { sdk?.getUser() } }
        Results(runner, "getUser() on main")
    }
    HowItWorks(
        """
        val details = sdk.getUserDetails()       // claims, any thread
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { sdk.getUser() }   // null on failure
            val v2 = withContext(Dispatchers.IO) { sdk.getUserProfileV2() }
        }
        """
    )
}

// -- Tokens -----------------------------------------------------------------------------

@Composable
fun TokensPage() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val sdk = env.sdk
    val revision by KindeHub.revision.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val access = remember(revision, now / 5000) { sdk?.getToken(TokenType.ACCESS_TOKEN) }
    val id = remember(revision, now / 5000) { sdk?.getToken(TokenType.ID_TOKEN) }
    val refresh = remember(revision, now / 5000) { sdk?.getRefreshToken() }
    val decoded = Jwt.decode(access)
    val left = decoded?.secondsLeft(now)

    SectionCard("Access token", "getToken(TokenType.ACCESS_TOKEN). Masked; ${if (env.config.debug) "PLAYGROUND_DEBUG allows revealing it." else "set PLAYGROUND_DEBUG=true to allow revealing it."}") {
        KeyValue("Value", if (reveal && env.config.debug) access.orEmpty() else Redact.mask(access), mono = true)
        KeyValue("Expires in", left?.let { if (it > 0) "${it}s" else "expired ${-it}s ago" } ?: "—", mono = true)
        KeyValue("Next refresh", left?.let { if (it > 10) "in ~${it - 10}s (exp − 10s, foreground only)" else "due now" } ?: "—", mono = true)
        KeyValue("Issuer", decoded?.iss ?: "—", mono = true)
        KeyValue("Audience", decoded?.audiences?.joinToString().orEmpty().ifBlank { "—" }, mono = true)
        KeyValue("Key ID", decoded?.kid ?: "—", mono = true)
        if (env.config.debug) ButtonRow { ActionButton(if (reveal) "Hide" else "Reveal", { reveal = !reveal }, secondary = true) }
    }
    SectionCard("ID token and refresh token") {
        KeyValue("ID token", Redact.mask(id), mono = true)
        KeyValue("ID token aud", Jwt.decode(id)?.audiences?.joinToString().orEmpty().ifBlank { "—" }, mono = true)
        KeyValue("Refresh token", if (refresh == null) "none (needs the offline scope)" else Redact.mask(refresh), mono = true)
    }
    SectionCard("Refresh", "The SDK has no public refresh method. It refreshes 10s before expiry while the app is in the foreground, pauses in the background, and retries every 10s on failure. A scheduled refresh doesn't call onNewToken: the Events page notes when the token changes silently.") {
        ButtonRow {
            ActionButton("refreshState()", { sdk?.refreshState(); KindeHub.refresh(context) }, enabled = sdk != null, secondary = true)
            ActionButton("clearCache()", { sdk?.clearCache(); KindeHub.note("clearCache()") }, enabled = sdk != null, secondary = true)
        }
        if (left != null && left <= 0 && sdk?.isAuthenticated() == true) {
            Note("The access token has expired but isAuthenticated() still returns true: the SDK checks the signature, not exp.", Outcome.FAILED)
        }
    }
    decoded?.let {
        SectionCard("Decoded access token", "Header and payload. Not verified here; the SDK verifies the signature in isAuthenticated().") {
            CodeBlock("header " + Jwt.render(it.header) + "\npayload " + Jwt.render(it.payload))
        }
    }
    HowItWorks(
        """
        val access = sdk.getToken(TokenType.ACCESS_TOKEN)
        val id = sdk.getToken(TokenType.ID_TOKEN)
        val refresh = sdk.getRefreshToken()

        // Without an activity (interceptor, worker):
        KindeClient.getInstance(context).getToken(TokenType.ACCESS_TOKEN)
        """
    )
}

// -- Claims -----------------------------------------------------------------------------

@Composable
fun ClaimsPage() {
    val sdk = LocalEnv.current.sdk
    val runner = rememberRunner()
    val revision by KindeHub.revision.collectAsState()
    var name by rememberSaveable { mutableStateOf("email") }
    var useId by rememberSaveable { mutableStateOf(true) }
    val type = if (useId) TokenType.ID_TOKEN else TokenType.ACCESS_TOKEN

    SectionCard("getClaim()", "Values come back as strings, whatever their JSON type: exp is \"1730000000\", a list is its JSON text.") {
        Field("claim", name, { name = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = useId, onClick = { useId = true }, label = { Text("ID_TOKEN") })
            FilterChip(selected = !useId, onClick = { useId = false }, label = { Text("ACCESS_TOKEN") })
        }
        ButtonRow {
            Call(runner, "getClaim()", sdk != null) { sdk?.getClaim(name.trim(), type) }
            listOf("sub", "exp", "org_code", "permissions", "feature_flags").forEach { c ->
                Call(runner, "getClaim(\"$c\")", sdk != null, secondary = true) { sdk?.getClaim(c, TokenType.ACCESS_TOKEN) }
            }
        }
        runner.results.values.forEach { CallResultView(it) }
    }
    listOf(TokenType.ACCESS_TOKEN, TokenType.ID_TOKEN).forEach { t ->
        val decoded = remember(revision) { Jwt.decode(sdk?.getToken(t)) }
        SectionCard("All ${t.name} claims", "Decoded locally for display.") {
            if (decoded == null) Text("No token.", style = MaterialTheme.typography.bodySmall)
            else decoded.payload.forEach { (k, v) -> KeyValue(k, Jwt.render(v), mono = true) }
        }
    }
    HowItWorks(
        """
        val email = sdk.getClaim("email", TokenType.ID_TOKEN).value as String?
        val exp = sdk.getClaim("exp").value      // "1730000000" — a String
        """
    )
}

// -- Permissions and roles -----------------------------------------------------------------

@Composable
fun AccessPage() {
    val env = LocalEnv.current
    val sdk = env.sdk
    val runner = rememberRunner()
    var forceApi by rememberSaveable { mutableStateOf(true) }
    var useCache by rememberSaveable { mutableStateOf(true) }
    var permission by rememberSaveable { mutableStateOf(env.config.demoPermission) }
    var role by rememberSaveable { mutableStateOf(env.config.demoRole) }
    val options = ApiOptions(forceApi = forceApi, useCache = useCache)
    val suffix = if (forceApi) " [API${if (useCache) ", cached" else ""}]" else " [token]"

    SectionCard("Token claims vs Account API", "Without forceApi the SDK reads the permissions and roles claims. With it, it calls /account_api/v1/… and caches the answer for 60 seconds unless useCache is off.") {
        ApiToggles(forceApi, { forceApi = it }, useCache, { useCache = it })
        ButtonRow {
            Call(runner, "getPermissions()$suffix", sdk != null) { sdk?.getPermissions(options) }
            Call(runner, "getRoles()$suffix", sdk != null, secondary = true) { sdk?.getRoles(options) }
            ActionButton("clearCache()", { sdk?.clearCache(); KindeHub.note("clearCache()") }, enabled = sdk != null, secondary = true)
        }
        runner.results.filterKeys { it.startsWith("getPermissions()") || it.startsWith("getRoles()") }.values.forEach { CallResultView(it) }
    }
    SectionCard("Check one", "getPermission(key) and getRole(key) with the same options.") {
        Field("permission", permission, { permission = it })
        Field("role", role, { role = it })
        ButtonRow {
            Call(runner, "getPermission()$suffix", sdk != null) { sdk?.getPermission(permission.trim(), options) }
            Call(runner, "getRole()$suffix", sdk != null, secondary = true) { sdk?.getRole(role.trim(), options) }
        }
        runner.results.filterKeys { it.startsWith("getPermission()") || it.startsWith("getRole()") }.values.forEach { CallResultView(it) }
        Note("forceApi calls throw on failure (\"Failed to fetch … from API\"), while the token versions return empty results. Wrap them in try/catch.")
    }
    HowItWorks(
        """
        sdk.getPermissions()                                   // permissions claim
        sdk.getPermission("read:reports").isGranted
        sdk.getRoles(ApiOptions(forceApi = true))              // Account API, cached 60s
        sdk.getRole("admin", ApiOptions(forceApi = true, useCache = false))
        sdk.clearCache()                                       // e.g. after switching org
        """
    )
}

// -- Feature flags ---------------------------------------------------------------------------

@Composable
fun FlagsPage() {
    val env = LocalEnv.current
    val sdk = env.sdk
    val runner = rememberRunner()
    var forceApi by rememberSaveable { mutableStateOf(false) }
    var useCache by rememberSaveable { mutableStateOf(true) }
    var code by rememberSaveable { mutableStateOf(env.config.demoFlag) }
    var default by rememberSaveable { mutableStateOf("false") }
    val options = ApiOptions(forceApi = forceApi, useCache = useCache)
    val src = if (forceApi) " [API]" else " [token]"

    SectionCard("Typed helpers", "getBooleanFlag, getStringFlag and getIntegerFlag return the default when the flag is missing. On the token path a flag of another type returns null instead of the default; on the API path it returns the default.") {
        ApiToggles(forceApi, { forceApi = it }, useCache, { useCache = it })
        Field("flag code", code, { code = it })
        Field("default", default, { default = it })
        ButtonRow {
            Call(runner, "getBooleanFlag()$src", sdk != null) { sdk?.getBooleanFlag(code.trim(), default.toBooleanStrictOrNull(), options) }
            Call(runner, "getStringFlag()$src", sdk != null, secondary = true) { sdk?.getStringFlag(code.trim(), default, options) }
            Call(runner, "getIntegerFlag()$src", sdk != null, secondary = true) { sdk?.getIntegerFlag(code.trim(), default.toIntOrNull(), options) }
        }
        ButtonRow {
            listOf(env.config.demoFlag to FlagType.Boolean, env.config.demoStringFlag to FlagType.String, env.config.demoIntFlag to FlagType.Integer).forEach { (c, t) ->
                Call(runner, "getFlag(\"$c\", type=$t) [token]", sdk != null, secondary = true) { sdk?.getFlag(c, null, t) }
            }
        }
        runner.results.filterKeys { !it.startsWith("getAllFlags") }.values.forEach { CallResultView(it) }
    }
    SectionCard("All flags", "Token flags carry a type letter (b, s, i). API integer flags arrive as Double through Gson.") {
        ButtonRow {
            Call(runner, "getAllFlags() [token]", sdk != null) { sdk?.getAllFlags() }
            Call(runner, "getAllFlags() [API]", sdk != null, secondary = true) { sdk?.getAllFlags(ApiOptions(forceApi = true, useCache = false)) }
        }
        Results(runner, "getAllFlags() [token]", "getAllFlags() [API]")
    }
    HowItWorks(
        """
        val beta: Boolean? = sdk.getBooleanFlag("beta_dashboard", false)
        val theme: String? = sdk.getStringFlag("theme", "light", ApiOptions(forceApi = true))
        val max: Int? = sdk.getIntegerFlag("max_items", 10)
        val flag: Flag? = sdk.getFlag("beta_dashboard", false, FlagType.Boolean)   // isDefault tells you
        val all: Map<String, Flag> = sdk.getAllFlags()
        """
    )
}

// -- Billing ------------------------------------------------------------------------------------

@Composable
fun BillingPage() {
    val env = LocalEnv.current
    val sdk = env.sdk
    val runner = rememberRunner()
    var key by rememberSaveable { mutableStateOf(env.config.demoEntitlement) }
    var pricing by rememberSaveable { mutableStateOf(env.config.pricingTableKey.orEmpty()) }
    var plan by rememberSaveable { mutableStateOf("") }

    SectionCard("Entitlements", "Account API, for the user's current org. getEntitlements() returns the first page only: the SDK takes no page size or cursor.") {
        Field("feature key", key, { key = it })
        ButtonRow {
            Call(runner, "getEntitlements()", sdk != null) { sdk?.getEntitlements() }
            Call(runner, "getEntitlement()", sdk != null, secondary = true) { sdk?.getEntitlement(key.trim()) }
        }
        Results(runner, "getEntitlements()", "getEntitlement()")
    }
    SectionCard("Plan selection", "register() with a pricing table shows plans during sign-up.") {
        Field("pricingTableKey", pricing, { pricing = it })
        Field("planInterest", plan, { plan = it })
        ButtonRow {
            ActionButton("register(pricingTableKey)", {
                sdk?.register(type = au.kinde.sdk.GrantType.PKCE, pricingTableKey = pricing.trim().ifBlank { null }, planInterest = plan.trim().ifBlank { null })
            }, enabled = sdk != null)
        }
    }
    HowItWorks(
        """
        val all = withContext(Dispatchers.IO) { sdk.getEntitlements() }   // EntitlementsResponse?
        val one = withContext(Dispatchers.IO) { sdk.getEntitlement("pro_reports") }
        val max = one?.data?.entitlement?.entitlementLimitMax
        """
    )
}
