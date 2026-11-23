package com.kinde.playground.sdk

import au.kinde.sdk.model.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic refresh: scheduled 10s before expiry while the process is in
 * the foreground (ProcessLifecycleOwner STARTED), immediately if already due.
 */
class TokenRefreshTest : SdkTestBase() {

    /** A session whose access token expires in [secondsLeft] seconds, so a refresh is due now. */
    private fun dueSession(secondsLeft: Long = 5): FakeKinde.Issued {
        val now = System.currentTimeMillis() / 1000
        return seedSession(kinde.issue(kinde.claims(extra = mapOf("exp" to now + secondsLeft))))
    }

    private fun awaitRefresh() = assertTrue("refresh request sent", awaitCondition(8_000) { kinde.tokenRequests().isNotEmpty() })

    @Test
    fun `a token inside the refresh window is refreshed when the app is in the foreground`() {
        val old = dueSession()

        facade()
        foreground()
        awaitRefresh()

        val form = kinde.form(kinde.tokenRequests().first())
        assertEquals("refresh_token", form["grant_type"])
        assertEquals(old.refresh, form["refresh_token"])
        assertEquals(kinde.clientId, form["client_id"])
        assertTrue(awaitCondition { client().getToken(TokenType.ACCESS_TOKEN) != old.access })
    }

    @Test
    fun `the refreshed session is persisted for the next launch`() {
        val old = dueSession()
        facade()
        foreground()
        awaitRefresh()
        assertTrue(awaitCondition { client().getToken(TokenType.ACCESS_TOKEN) != old.access })
        val refreshed = client().getToken(TokenType.ACCESS_TOKEN)

        resetKindeClient()

        assertEquals(refreshed, client().getToken(TokenType.ACCESS_TOKEN))
        assertNotEquals(old.refresh, client().getRefreshToken())
    }

    @Test
    fun `a scheduled refresh notifies the listener of the new token`() {
        val old = dueSession()
        facade()
        val replayed = listener.tokens.size
        foreground()
        awaitRefresh()
        assertTrue(awaitCondition { client().getToken(TokenType.ACCESS_TOKEN) != old.access })
        idle(300)

        assertTrue("onNewToken calls after refresh: ${listener.tokens.size - replayed}", listener.tokens.drop(replayed).any { it.token != old.access })
    }

    @Test
    @KnownIssue("A revoked refresh token (HTTP 400 invalid_grant) is not recognised: the SDK only parses OAuth errors from 2xx bodies, so it never signs out and retries every 10s, reporting TokenException each time")
    fun `a rejected refresh token signs the user out`() {
        dueSession()
        kinde.validRefreshTokens.clear()

        facade()
        foreground()
        awaitRefresh()
        awaitCondition(4_000) { listener.logoutCount > 0 }

        assertEquals(1, listener.logoutCount)
    }

    @Test
    fun `in the background no refresh is attempted`() {
        dueSession()

        facade()
        idle(800)

        assertTrue(kinde.tokenRequests().isEmpty())
    }

    @Test
    fun `a failed refresh is reported to the listener`() {
        dueSession()
        kinde.validRefreshTokens.clear()

        facade()
        foreground()
        awaitRefresh()

        assertTrue(awaitCondition { listener.exceptionNames().contains("TokenException") })
    }

    @Test
    fun `without a refresh token the listener is told refreshing stopped`() {
        val now = System.currentTimeMillis() / 1000
        seedSession(kinde.issue(kinde.claims(extra = mapOf("exp" to now + 5))), refreshToken = null)

        facade()
        foreground()
        val heard = awaitCondition(4_000) { listener.exceptions.any { it.message.orEmpty().contains("Token refresh stopped") } }

        assertTrue("exceptions: ${listener.exceptions.map { it.message }}", heard)
        assertTrue(kinde.tokenRequests().isEmpty())
    }

    // -- refreshToken() ----------------------------------------------------------------------------

    @Test
    fun `refreshToken refreshes now and announces the new token`() {
        val old = seedSession()
        facade()
        val replayed = listener.tokens.size

        val refreshed = onBackground { client().refreshToken() }

        assertTrue(refreshed)
        assertEquals(old.refresh, kinde.form(kinde.tokenRequests().single())["refresh_token"])
        val now = client().getToken(TokenType.ACCESS_TOKEN)
        assertNotEquals(old.access, now)
        assertTrue(awaitCondition { listener.tokens.drop(replayed).any { it.token == now } })
    }

    @Test
    fun `a refresh that returns the same access token isn't announced again`() {
        val old = seedSession()
        facade()
        val replayed = listener.tokens.size
        // Kinde can hand back the current access token to an early refresh (with a new refresh token).
        kinde.overrides["/oauth2/token"] = {
            val rotated = kinde.issue()
            okhttp3.mockwebserver.MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(kinde.tokenJson(rotated.copy(access = old.access, id = old.id)))
        }

        assertTrue(onBackground { client().refreshToken() })
        idle()

        assertEquals(old.access, client().getToken(TokenType.ACCESS_TOKEN))
        assertEquals("onNewToken calls: ${listener.tokens.drop(replayed)}", replayed, listener.tokens.size)
    }

    @Test
    fun `refreshToken on the main thread does nothing and reports WrongThreadException`() {
        seedSession()
        facade()

        assertFalse(client().refreshToken())
        assertTrue(kinde.tokenRequests().isEmpty())
        assertTrue(listener.exceptionNames().toString(), "WrongThreadException" in listener.exceptionNames())
    }

    @Test
    fun `refreshToken without a session returns false without a request`() {
        facade()

        assertFalse(onBackground { client().refreshToken() })
        assertTrue(kinde.tokenRequests().isEmpty())
    }

    @Test
    fun `a refresh still in flight when logout completes doesn't sign the user back in`() {
        seedSession()
        installFakeBrowser()
        val f = facade()
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // Kinde answers the refresh only after the user has signed out.
        kinde.overrides["/oauth2/token"] = {
            kinde.overrides.remove("/oauth2/token")
            arrived.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            okhttp3.mockwebserver.MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(kinde.tokenJson(kinde.issue()))
        }
        var refreshed: Boolean? = null
        val refresh = Thread { refreshed = client().refreshToken() }
        refresh.start()
        assertTrue(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))

        f.sdk.logout()
        val endSession = awaitStartedForResult(f.activity)
        org.junit.Assert.assertNotNull("end-session page opened", endSession)
        deliverResult(f, endSession!!, android.app.Activity.RESULT_OK, android.content.Intent())
        assertTrue(awaitCondition { listener.logoutCount == 1 })
        release.countDown()
        refresh.join(10_000)
        idle()

        assertEquals(false, refreshed)
        assertFalse(client().isAuthenticated())
        org.junit.Assert.assertNull(client().getRefreshToken())
        org.junit.Assert.assertNull("no session saved", store().getState())
    }
}
