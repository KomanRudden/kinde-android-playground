package com.kinde.playground.sdk

import au.kinde.sdk.model.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restoring a saved session on launch, and how isAuthenticated() decides. */
class SessionRestoreTest : SdkTestBase() {

    @Test
    fun `fresh install fetches the JWKS and replays onLogout`() {
        facade()

        // Fetched twice on a cold start: once when the client is created and again
        // when the first facade attaches before the first response has arrived.
        assertTrue("JWKS fetched", awaitCondition { kinde.count("/.well-known/jwks.json") >= 1 && store().getKeys() != null })
        assertEquals(1, listener.logoutCount)
        assertTrue(listener.tokens.isEmpty())
        assertFalse(client().isAuthenticated())
        assertNull(client().getToken(TokenType.ACCESS_TOKEN))
    }

    @Test
    fun `JWKS fetched on a fresh install is saved for later launches`() {
        facade()
        assertTrue(awaitCondition { store().getKeys() != null })
        idle()
        val fetches = kinde.count("/.well-known/jwks.json")

        resetKindeClient()
        facade(RecordingListener())
        idle()

        assertEquals("keys are not fetched again", fetches, kinde.count("/.well-known/jwks.json"))
    }

    @Test
    fun `saved session with a valid signature is restored and replayed to the listener`() {
        val issued = seedSession()

        facade()

        assertTrue(client().isAuthenticated())
        assertEquals(issued.access, client().getToken(TokenType.ACCESS_TOKEN))
        assertEquals(issued.id, client().getToken(TokenType.ID_TOKEN))
        assertEquals(issued.refresh, client().getRefreshToken())
        assertEquals(listOf(issued.access), listener.tokens.map { it.token })
        assertEquals(0, listener.logoutCount)
    }

    @Test
    fun `token signed by an unknown key is not authenticated`() {
        val stranger = FakeKinde.SigningKey("stranger")
        seedSession(issued = kinde.issue(key = stranger))

        facade()

        assertFalse(client().isAuthenticated())
        assertTrue("no token replayed", listener.tokens.isEmpty())
    }

    @Test
    fun `tampered payload fails signature verification`() {
        val issued = kinde.issue()
        val parts = issued.access.split('.')
        val forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(FakeKinde.toJson(kinde.claims(extra = mapOf("permissions" to listOf("admin:everything")))).toString().toByteArray())
        seedSession(issued = issued.copy(access = "${parts[0]}.$forgedPayload.${parts[2]}"))

        facade()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `isAuthenticated reads storage so every facade sees a sign-out`() {
        seedSession()
        facade()
        assertTrue(client().isAuthenticated())

        store().clearState()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `expired access token is not authenticated`() {
        val now = System.currentTimeMillis() / 1000
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("iat" to now - 7200, "exp" to now - 3600))), refreshToken = null)

        facade()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `expired access token with a refresh token still counts as signed in`() {
        // The SDK renews it (e.g. on returning to the foreground); signing the user out would be wrong.
        val now = System.currentTimeMillis() / 1000
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("iat" to now - 7200, "exp" to now - 3600))))

        facade()

        assertTrue(client().isAuthenticated())
    }

    @Test
    fun `token signed by Kinde's key but naming another issuer is not authenticated`() {
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("iss" to "https://evil.example.com"))))

        facade()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `with an audience configured, a token for another audience is not authenticated`() {
        resetKindeClient()
        installMetaData(kinde.domain, kinde.clientId, audience = "https://api.example.com")
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("aud" to listOf("https://other.example.com")))))

        facade()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `with an audience configured, a token for it is authenticated`() {
        resetKindeClient()
        installMetaData(kinde.domain, kinde.clientId, audience = "https://api.example.com")
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("aud" to listOf("https://api.example.com")))))

        facade()

        assertTrue(client().isAuthenticated())
    }

    @Test
    @KnownIssue("Signature check uses the first JWKS key and ignores the token's kid, so key rotation (two keys published) signs users out")
    fun `token signed by the second published key is authenticated`() {
        val next = FakeKinde.SigningKey("key-2")
        kinde.jwks += next
        seedSession(issued = kinde.issue(key = next))

        facade()

        assertTrue(client().isAuthenticated())
    }

    @Test
    @KnownIssue("The saved JWKS is never refreshed, so after Kinde rotates keys every new token fails verification until app data is cleared")
    fun `keys are refreshed when a token is signed by a key the SDK has not seen`() {
        // Device cached key-1. Kinde has since rotated to key-2 only.
        val oldKeys = kinde.jwksJson()
        val rotated = FakeKinde.SigningKey("key-2")
        kinde.jwks.clear()
        kinde.jwks += rotated
        kinde.signer = rotated
        seedSession(keysJson = oldKeys)

        facade()
        idle(500)

        assertTrue(client().isAuthenticated())
    }

    @Test
    fun `missing keys at launch are fetched and the restored session is then announced`() {
        val issued = seedSession(keysJson = null)

        facade()

        assertTrue(awaitCondition { listener.tokens.any { it.token == issued.access } })
        assertTrue(client().isAuthenticated())
    }

    @Test
    @KnownIssue("With a saved session but no cached keys, a failed JWKS fetch (HTTP 503) leaves the listener with no event at all: no onNewToken, onLogout or onException")
    fun `offline launch with an unverifiable session tells the listener something definite`() {
        seedSession(keysJson = null)
        kinde.overrides["/.well-known/jwks.json"] = { okhttp3.mockwebserver.MockResponse().setResponseCode(503) }

        facade()
        idle(800)

        assertTrue("expected onNewToken or onLogout", listener.tokens.isNotEmpty() || listener.logoutCount > 0)
    }
}
