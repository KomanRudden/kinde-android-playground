package com.kinde.playground.sdk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import au.kinde.sdk.GrantType
import au.kinde.sdk.model.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * What login(), register(), createOrg() and handleInvitation() put on the
 * authorize URL, and the code exchange that follows. The URL is read from the
 * intent the SDK hands to the browser.
 */
class LoginFlowTest : SdkTestBase() {

    @Before
    fun browser() = installFakeBrowser()

    /**
     * Signed out, with the JWKS already cached. On the JVM Retrofit runs callbacks on
     * OkHttp threads, so the SDK's two cold-start JWKS fetches save concurrently and
     * can corrupt the keys through the Store IV race (StorageTest covers that).
     * Seeding the keys keeps these tests about sign-in.
     */
    private fun signedOutFacade(): Facade {
        store().saveKeys(kinde.jwksJson())
        return facade().also { idle() }
    }

    private fun started(f: Facade): Intent {
        val intent = awaitStartedForResult(f.activity)
        assertNotNull("SDK started the browser", intent)
        return intent!!
    }

    private fun Uri.param(name: String): String? = getQueryParameter(name)

    @Test
    fun `login builds an authorization code request for this client and redirect`() {
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE)
        val url = browserUrl(started(f))

        assertEquals("${kinde.base}/oauth2/auth", url.buildUpon().clearQuery().build().toString())
        assertEquals("code", url.param("response_type"))
        assertEquals(kinde.clientId, url.param("client_id"))
        assertEquals(LOGIN_REDIRECT, url.param("redirect_uri"))
        assertEquals("openid offline email profile", url.param("scope"))
        assertNotNull("state", url.param("state"))
    }

    @Test
    fun `login with GrantType PKCE sends an S256 code challenge`() {
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE)
        val url = browserUrl(started(f))

        assertEquals("S256", url.param("code_challenge_method"))
        assertNotNull(url.param("code_challenge"))
    }

    @Test
    @KnownIssue("The default login() sends no PKCE code challenge (the SDK calls setCodeVerifier(null) unless GrantType.PKCE); RFC 8252 requires PKCE for native apps")
    fun `default login uses PKCE`() {
        val f = signedOutFacade()

        f.sdk.login()

        assertNotNull(browserUrl(started(f)).param("code_challenge"))
    }

    @Test
    @KnownIssue("No nonce is sent (setNonce(null)) and the ID token is never validated, so ID token replay is not detected")
    fun `login sends a nonce`() {
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE)

        assertNotNull(browserUrl(started(f)).param("nonce"))
    }

    @Test
    fun `login options become authorize parameters`() {
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE, orgCode = "org_42", loginHint = "ada@example.com", connectionId = "conn_google")
        val url = browserUrl(started(f))

        assertEquals("org_42", url.param("org_code"))
        assertEquals("ada@example.com", url.param("login_hint"))
        assertEquals("conn_google", url.param("connection_id"))
        assertNull(url.param("start_page"))
    }

    @Test
    fun `custom scopes are sent as given`() {
        val f = facade(scopes = listOf("openid", "profile")).also { idle() }

        f.sdk.login(type = GrantType.PKCE)

        assertEquals("openid profile", browserUrl(started(f)).param("scope"))
    }

    @Test
    fun `audience from meta-data is sent on every request`() {
        resetKindeClient()
        installMetaData(kinde.domain, kinde.clientId, audience = "https://api.example.com")
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE)

        assertEquals("https://api.example.com", browserUrl(started(f)).param("audience"))
    }

    @Test
    fun `register opens the registration page with pricing options`() {
        val f = signedOutFacade()

        f.sdk.register(type = GrantType.PKCE, pricingTableKey = "pricing_main", planInterest = "pro", loginHint = "new@example.com")
        val url = browserUrl(started(f))

        assertEquals("registration", url.param("start_page"))
        assertEquals("pricing_main", url.param("pricing_table_key"))
        assertEquals("pro", url.param("plan_interest"))
        assertEquals("new@example.com", url.param("login_hint"))
    }

    @Test
    fun `createOrg asks Kinde to create an organization`() {
        val f = signedOutFacade()

        f.sdk.createOrg(type = GrantType.PKCE, orgName = "Acme Labs")
        val url = browserUrl(started(f))

        assertEquals("registration", url.param("start_page"))
        assertEquals("true", url.param("is_create_org"))
        assertEquals("Acme Labs", url.param("org_name"))
    }

    @Test
    fun `createOrg with a blank name throws before opening anything`() {
        val f = signedOutFacade()

        assertThrows(IllegalArgumentException::class.java) { f.sdk.createOrg(orgName = "  ") }
        assertNull(shadowOf(f.activity).nextStartedActivityForResult)
    }

    @Test
    fun `invitation code starts registration once`() {
        val f = signedOutFacade()

        f.sdk.handleInvitation("inv_123", type = GrantType.PKCE)
        val url = browserUrl(started(f))
        f.sdk.handleInvitation("inv_123", type = GrantType.PKCE)

        assertEquals("inv_123", url.param("invitation_code"))
        assertEquals("true", url.param("is_invitation"))
        assertEquals("registration", url.param("start_page"))
        assertTrue(f.sdk.isHandlingInvitation())
        assertNull("same code is not handled twice", shadowOf(f.activity).nextStartedActivityForResult)
    }

    @Test
    fun `invitation link in the launching intent is handled when the activity resumes`() {
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse("kindeplayground://invite?invitation_code=inv_link"))

        val f = facade(launchIntent = launch, resume = true)

        assertEquals("inv_link", browserUrl(started(f)).param("invitation_code"))
    }

    @Test
    fun `cancelling an invitation lets the same code be used again`() {
        val f = signedOutFacade()
        f.sdk.handleInvitation("inv_retry")
        val first = started(f)

        deliverResult(f, first, Activity.RESULT_CANCELED, null)
        f.sdk.handleInvitation("inv_retry")

        assertNotNull(awaitStartedForResult(f.activity))
    }

    @Test
    fun `malformed domain override is reported and nothing opens`() {
        val f = signedOutFacade()

        f.sdk.login(domain = "https://evil.example.com/path")
        idle()

        assertNull(shadowOf(f.activity).nextStartedActivityForResult)
        assertTrue(listener.exceptions.any { it is IllegalArgumentException })
    }

    @Test
    fun `domain and client ID overrides change the authorize request`() {
        val f = signedOutFacade()

        f.sdk.login(type = GrantType.PKCE, domain = "other.kinde.invalid", clientId = "other_client")
        val url = browserUrl(started(f))

        assertEquals("other.kinde.invalid", url.host)
        assertEquals("other_client", url.param("client_id"))
    }

    @Test
    fun `authorization code is exchanged with the PKCE verifier and the SDK header`() {
        val f = signedOutFacade()
        f.sdk.login(type = GrantType.PKCE)
        val started = started(f)
        val verifier = authRequest(started).codeVerifier

        deliverAuthorizationCode(f, started, code = "code_abc")

        assertTrue("token exchange", awaitCondition { kinde.tokenRequests().isNotEmpty() })
        val exchange = kinde.tokenRequests().single()
        val form = kinde.form(exchange)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code_abc", form["code"])
        assertEquals(LOGIN_REDIRECT, form["redirect_uri"])
        assertEquals(kinde.clientId, form["client_id"])
        assertEquals(verifier, form["code_verifier"])
        assertTrue("Kinde-SDK header", exchange.getHeader("Kinde-SDK").orEmpty().startsWith("Kotlin/"))
    }

    @Test
    fun `successful exchange signs in, persists the session and notifies the listener`() {
        val f = signedOutFacade()
        f.sdk.login(type = GrantType.PKCE)
        deliverAuthorizationCode(f, started(f))

        assertTrue(awaitCondition { listener.tokens.isNotEmpty() })
        val token = listener.tokens.last().token
        assertEquals(token, client().getToken(TokenType.ACCESS_TOKEN))
        assertTrue(client().isAuthenticated())
        assertNotNull(client().getRefreshToken())

        // A new process restores the same session from storage.
        resetKindeClient()
        assertEquals(token, client().getToken(TokenType.ACCESS_TOKEN))
    }

    @Test
    fun `onNewToken after sign-in arrives on a background thread`() {
        val f = signedOutFacade()
        f.sdk.login(type = GrantType.PKCE)
        deliverAuthorizationCode(f, started(f))

        assertTrue(awaitCondition { listener.tokens.isNotEmpty() })
        assertFalse("apps must hop to the main thread before touching UI", listener.tokens.last().onMain)
    }

    @Test
    fun `cancelled sign-in reports onLogout and stays signed out`() {
        val f = signedOutFacade()
        val before = listener.logoutCount
        f.sdk.login(type = GrantType.PKCE)

        deliverResult(f, started(f), Activity.RESULT_CANCELED, null)

        assertEquals(before + 1, listener.logoutCount)
        assertFalse(client().isAuthenticated())
        assertTrue(kinde.tokenRequests().isEmpty())
    }

    @Test
    fun `rejected code exchange is reported and leaves the user signed out`() {
        kinde.overrides["/oauth2/token"] = {
            okhttp3.mockwebserver.MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant","error_description":"code expired"}""")
        }
        val f = signedOutFacade()
        f.sdk.login(type = GrantType.PKCE)
        deliverAuthorizationCode(f, started(f))

        assertTrue(awaitCondition { listener.exceptions.any { it.javaClass.simpleName == "TokenException" } })
        assertFalse(client().isAuthenticated())
    }
}
