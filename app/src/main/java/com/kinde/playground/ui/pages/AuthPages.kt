package com.kinde.playground.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import au.kinde.sdk.GrantType
import au.kinde.sdk.store.Store
import com.kinde.playground.checks.Outcome
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.LogoutProbe
import com.kinde.playground.core.Redact
import com.kinde.playground.ui.LocalEnv
import com.kinde.playground.ui.components.*
import com.kinde.playground.ui.rememberRunner
import net.openid.appauth.AuthState

private fun String.orNullIfBlank() = trim().ifBlank { null }

/** The real request the SDK sent last time, read back from its saved AuthState. */
internal fun lastAuthorizationRequest(context: Context): List<Pair<String, String>>? {
    val config = KindeHub.config
    if (!config.isConfigured) return null
    val json = runCatching { Store(context, config.domain).getState() }.getOrNull() ?: return null
    val req = runCatching { AuthState.jsonDeserialize(json).lastAuthorizationResponse?.request }.getOrNull() ?: return null
    return buildList {
        add("authorize" to req.configuration.authorizationEndpoint.toString())
        add("client_id" to Redact.mask(req.clientId))
        add("redirect_uri" to req.redirectUri.toString())
        add("scope" to (req.scope ?: "(none)"))
        add("PKCE" to (req.codeVerifierChallengeMethod?.let { "yes ($it)" } ?: "no"))
        add("nonce" to (if (req.nonce != null) "yes" else "no"))
        add("login_hint" to (req.loginHint ?: "—"))
        req.additionalParameters.forEach { (k, v) -> add(k to v) }
    }
}

@Composable
fun SignInPage() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val sdk = env.sdk
    val session by KindeHub.session.collectAsState()
    val revision by KindeHub.revision.collectAsState()

    var grant by rememberSaveable { mutableStateOf("PKCE") }
    var orgCode by rememberSaveable { mutableStateOf(env.config.demoOrgCode.orEmpty()) }
    var loginHint by rememberSaveable { mutableStateOf("") }
    var connectionId by rememberSaveable { mutableStateOf(env.config.connectionId.orEmpty()) }
    var domain by rememberSaveable { mutableStateOf("") }
    var clientId by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var pricing by rememberSaveable { mutableStateOf(env.config.pricingTableKey.orEmpty()) }
    var plan by rememberSaveable { mutableStateOf("") }
    var orgName by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val type: GrantType? = when (grant) {
        "PKCE" -> GrantType.PKCE
        "NONE" -> GrantType.NONE
        else -> null
    }
    val guard: (String, () -> Unit) -> Unit = { what, block ->
        error = null
        try {
            KindeHub.note("$what(type=$grant)")
            block()
        } catch (t: Throwable) {
            error = "$what threw ${Redact.error(t)}"
        }
    }

    SectionCard("Session", "isAuthenticated() verifies the stored access token's signature with the cached JWKS.") {
        KeyValue("State", session.name.lowercase().replace('_', ' '))
        KeyValue("isAuthenticated()", (sdk?.isAuthenticated() ?: false).toString(), mono = true)
        KeyValue("isHandlingInvitation()", (sdk?.isHandlingInvitation() ?: false).toString(), mono = true)
        ButtonRow {
            // Quick path: the options below are all optional.
            ActionButton("Sign in", { guard("login") { sdk?.login(type = GrantType.PKCE) } }, enabled = sdk != null)
            ActionButton("Register", { guard("register") { sdk?.register(type = GrantType.PKCE) } }, enabled = sdk != null, secondary = true)
            ActionButton("logout()", { guard("logout") { sdk?.logout() } }, enabled = sdk != null, secondary = true)
            ActionButton("refreshState()", { sdk?.refreshState(); KindeHub.refresh(context) }, enabled = sdk != null, secondary = true)
        }
    }

    val probe by LogoutProbe.result.collectAsState()
    SectionCard("Is the refresh token dead after logout?", "Keeps a copy of the refresh token, calls logout(), then, 2½ minutes later, offers the copy to Kinde's token endpoint once, the way a leaked copy could be used. (Kinde applies a revocation within about two minutes, and using the token rotates it, so it is tried only once.)") {
        ButtonRow {
            ActionButton("Sign out, then try the old refresh token", {
                guard("logout") { if (LogoutProbe.arm(context)) sdk?.logout() }
            }, enabled = sdk != null && session == KindeHub.Session.SIGNED_IN, secondary = true)
        }
        probe?.let { CodeBlock(it, wrap = true) }
    }

    SectionCard("Grant type", "PKCE adds a code challenge. Newer SDKs use it by default (null); 2.2.0 only with GrantType.PKCE. NONE opts out. Compare them on the Security page after signing in.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("PKCE", "default (null)", "NONE").forEach { g ->
                FilterChip(selected = grant == g, onClick = { grant = g }, label = { Text(g) })
            }
        }
        @Suppress("DEPRECATION")
        if (type == GrantType.NONE) Note("Without PKCE an intercepted authorization code can be exchanged by another app. Kinde may also reject the exchange for a public client.", Outcome.WARN)
    }

    SectionCard("Options", "Blank fields are not sent.") {
        Field("orgCode", orgCode, { orgCode = it }, placeholder = "org_123")
        Field("loginHint", loginHint, { loginHint = it }, placeholder = "user@example.com")
        Field("connectionId", connectionId, { connectionId = it }, placeholder = "conn_123")
        Field("domain override", domain, { domain = it }, placeholder = "another-business.kinde.com")
        Field("clientId override", clientId, { clientId = it })
        Field("invitationCode", invitation, { invitation = it })
        Field("pricingTableKey (register, createOrg)", pricing, { pricing = it })
        Field("planInterest (register, createOrg)", plan, { plan = it })
        ButtonRow {
            ActionButton("login()", {
                guard("login") {
                    sdk?.login(type, orgCode.orNullIfBlank(), loginHint.orNullIfBlank(), domain.orNullIfBlank(),
                        clientId.orNullIfBlank(), invitation.orNullIfBlank(), connectionId.orNullIfBlank())
                }
            }, enabled = sdk != null)
            ActionButton("register()", {
                guard("register") {
                    sdk?.register(type, orgCode.orNullIfBlank(), loginHint.orNullIfBlank(), pricing.orNullIfBlank(), plan.orNullIfBlank(),
                        domain.orNullIfBlank(), clientId.orNullIfBlank(), invitation.orNullIfBlank(), connectionId.orNullIfBlank())
                }
            }, enabled = sdk != null, secondary = true)
        }
        if (domain.isNotBlank()) Note("A domain override switches the SDK to that domain's own token store until logout. A malformed value is reported to onException and nothing opens.")
    }

    SectionCard("Create an organization", "createOrg() opens registration with is_create_org=true and org_name.") {
        Field("orgName", orgName, { orgName = it }, placeholder = "Playground Org")
        ButtonRow {
            ActionButton("createOrg()", {
                guard("createOrg") {
                    sdk?.createOrg(type, orgName, pricing.orNullIfBlank(), plan.orNullIfBlank(), domain.orNullIfBlank(),
                        clientId.orNullIfBlank(), connectionId.orNullIfBlank())
                }
            }, enabled = sdk != null)
        }
        Text("A blank name throws IllegalArgumentException before anything opens.", style = MaterialTheme.typography.bodySmall)
    }

    SectionCard("Invitations", "handleInvitation() starts registration with invitation_code and is_invitation=true. The same code is only handled once until logout or cancel.") {
        ButtonRow {
            ActionButton("handleInvitation()", { guard("handleInvitation") { sdk?.handleInvitation(invitation.trim(), type, orgCode.orNullIfBlank()) } },
                enabled = sdk != null && invitation.isNotBlank(), secondary = true)
        }
        Text("Or open a link: adb shell am start -d \"kindeplayground://invite?invitation_code=CODE\" com.kinde.playground",
            style = MaterialTheme.typography.bodySmall)
    }

    error?.let { Note(it, Outcome.FAILED) }

    SectionCard("Last authorization request", "Read back from the session the SDK saved: exactly what was sent to Kinde.") {
        val rows = remember(revision) { lastAuthorizationRequest(context) }
        if (rows == null) Text("No saved sign-in yet.", style = MaterialTheme.typography.bodySmall)
        rows?.forEach { (k, v) -> KeyValue(k, v, mono = true) }
    }

    HowItWorks(
        """
        sdk.login(
            type = GrantType.PKCE,          // default null: no PKCE
            orgCode = "org_123",
            loginHint = "user@example.com",
            domain = null, clientId = null, // per-login overrides
            invitationCode = null,
            connectionId = "conn_123",
        )
        sdk.register(type = GrantType.PKCE, pricingTableKey = "...", planInterest = "...")
        sdk.createOrg(type = GrantType.PKCE, orgName = "Acme")
        sdk.handleInvitation(invitationCode)
        sdk.logout()                        // opens the end-session page, then onLogout()
        """
    )
}

@Composable
fun OrganizationsPage() {
    val env = LocalEnv.current
    val sdk = env.sdk
    val runner = rememberRunner()
    var target by rememberSaveable { mutableStateOf(env.config.demoOrgCode.orEmpty()) }
    var orgName by rememberSaveable { mutableStateOf("") }

    SectionCard("From the tokens", "getOrganization() reads org_code from the access token; getUserOrganizations() reads org_codes from the ID token.") {
        ButtonRow {
            ActionButton("getOrganization()", { runner.run("getOrganization()") { sdk?.getOrganization() } }, enabled = sdk != null)
            ActionButton("getUserOrganizations()", { runner.run("getUserOrganizations()") { sdk?.getUserOrganizations() } }, enabled = sdk != null, secondary = true)
        }
        runner.results["getOrganization()"]?.let { CallResultView(it) }
        runner.results["getUserOrganizations()"]?.let { CallResultView(it) }
    }
    SectionCard("Switch organization", "Sign in again with orgCode. The new token's org_code, permissions and roles change; call clearCache() so Account API results don't come from the old org.") {
        Field("orgCode", target, { target = it }, placeholder = "org_123")
        ButtonRow {
            ActionButton("login(orgCode)", {
                sdk?.clearCache()
                KindeHub.note("login(orgCode=${target.trim()})")
                sdk?.login(type = GrantType.PKCE, orgCode = target.trim())
            }, enabled = sdk != null && target.isNotBlank())
        }
    }
    SectionCard("Create an organization", "Registration flow that creates an org and makes the user its first member.") {
        Field("orgName", orgName, { orgName = it })
        ButtonRow {
            ActionButton("createOrg()", { sdk?.createOrg(type = GrantType.PKCE, orgName = orgName.trim()) }, enabled = sdk != null && orgName.isNotBlank())
        }
    }
    HowItWorks(
        """
        val current: String = sdk.getOrganization().orgCode
        val all: List<String> = sdk.getUserOrganizations().orgCodes

        sdk.clearCache()
        sdk.login(type = GrantType.PKCE, orgCode = "org_456")
        """
    )
}
