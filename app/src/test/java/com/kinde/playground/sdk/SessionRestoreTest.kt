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

        assertTrue("JWKS fetched", awaitCondition { store().getKeys() != null })
        idle()
        assertEquals("fetched once, not once per facade", 1, kinde.count("/.well-known/jwks.json"))
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
    @KnownIssue("isAuthenticated() verifies the signature only; an expired access token still counts as signed in")
    fun `expired access token is not authenticated`() {
        val now = System.currentTimeMillis() / 1000
        seedSession(issued = kinde.issue(kinde.claims(extra = mapOf("iat" to now - 7200, "exp" to now - 3600))), refreshToken = null)

        facade()

        assertFalse(client().isAuthenticated())
    }

    @Test
    fun `token signed by the second published key is authenticated`() {
        val next = FakeKinde.SigningKey("key-2")
        kinde.jwks += next
        seedSession(issued = kinde.issue(key = next))

        facade()

        assertTrue(client().isAuthenticated())
    }

    @Test
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
        assertTrue("the restored session is announced once verified", awaitCondition { listener.tokens.isNotEmpty() })
        assertTrue(store().getKeys()!!.contains("key-2"))
    }

    @Test
    fun `a token naming a key Kinde never publishes refreshes the keys at most once a minute`() {
        seedSession(issued = kinde.issue(key = FakeKinde.SigningKey("never-published")))
        facade()
        idle(300)

        repeat(5) { assertFalse(client().isAuthenticated()) }
        idle(300)

        assertEquals("one refresh for the unknown key", 1, kinde.count("/.well-known/jwks.json"))
    }

    @Test
    fun `missing keys at launch are fetched and the restored session is then announced`() {
        val issued = seedSession(keysJson = null)

        facade()

        assertTrue(awaitCondition { listener.tokens.any { it.token == issued.access } })
        assertTrue(client().isAuthenticated())
    }

    @Test
    fun `a failed key fetch with a saved session is reported, and the session is announced once keys arrive`() {
        val issued = seedSession(keysJson = null)
        kinde.overrides["/.well-known/jwks.json"] = { okhttp3.mockwebserver.MockResponse().setResponseCode(503) }

        facade()
        assertTrue("the failure is reported, not dropped", awaitCondition { listener.exceptions.any { it.message.orEmpty().contains("HTTP 503") } })
        assertTrue(listener.tokens.isEmpty())

        // Kinde recovers; returning to the foreground retries the fetch.
        kinde.overrides.remove("/.well-known/jwks.json")
        foreground()

        assertTrue(awaitCondition { listener.tokens.any { it.token == issued.access } })
        assertTrue(client().isAuthenticated())
    }
}
