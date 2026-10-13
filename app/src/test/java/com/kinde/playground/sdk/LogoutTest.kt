package com.kinde.playground.sdk

import android.app.Activity
import android.content.Intent
import au.kinde.sdk.model.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** logout(): the end-session round trip, cancellation, and what is cleared. */
class LogoutTest : SdkTestBase() {

    @Before
    fun browser() = installFakeBrowser()

    private fun startLogout(f: Facade): Intent {
        f.sdk.logout()
        val started = awaitStartedForResult(f.activity)
        assertNotNull("end-session page opened", started)
        return started!!
    }

    @Test
    fun `logout opens Kinde's end-session page with the logout redirect`() {
        seedSession()
        val f = facade()

        val url = browserUrl(startLogout(f))

        assertEquals("${kinde.base}/logout", url.buildUpon().clearQuery().build().toString())
        assertEquals(LOGOUT_REDIRECT, url.getQueryParameter("redirect"))
        assertEquals(LOGOUT_REDIRECT, url.getQueryParameter("post_logout_redirect_uri"))
    }

    @Test
    fun `completing logout clears the session and notifies the listener`() {
        seedSession()
        val f = facade()
        val started = startLogout(f)

        deliverResult(f, started, Activity.RESULT_OK, Intent())

        assertTrue(awaitCondition { listener.logoutCount == 1 })
        assertFalse(client().isAuthenticated())
        assertNull(client().getToken(TokenType.ACCESS_TOKEN))
        assertNull(client().getRefreshToken())
        assertNull("session removed from storage", store().getState())
        assertNotNull("signing keys are kept", store().getKeys())
    }

    @Test
    fun `cancelling the end-session page keeps the session`() {
        val issued = seedSession()
        val f = facade()
        val started = startLogout(f)

        deliverResult(f, started, Activity.RESULT_CANCELED, null)
        idle()

        assertEquals(0, listener.logoutCount)
        assertTrue(client().isAuthenticated())
        assertEquals(issued.access, client().getToken(TokenType.ACCESS_TOKEN))
    }

    @Test
    fun `logout can be started again after a cancel`() {
        seedSession()
        val f = facade()
        deliverResult(f, startLogout(f), Activity.RESULT_CANCELED, null)

        assertNotNull(startLogout(f))
    }

    @Test
    fun `every facade hears the logout`() {
        seedSession()
        val other = RecordingListener()
        facade(other)
        val f = facade()

        deliverResult(f, startLogout(f), Activity.RESULT_OK, Intent())

        assertTrue(awaitCondition { listener.logoutCount == 1 && other.logoutCount == 1 })
    }

    @Test
    fun `logout from a second activity signs out the first`() {
        seedSession()
        val first = facade()
        val second = facade(RecordingListener())

        deliverResult(second, startLogout(second), Activity.RESULT_OK, Intent())

        assertTrue(awaitCondition { listener.logoutCount == 1 })
        assertFalse(first.sdk.isAuthenticated())
    }

    @Test
    @KnownIssue("logout() never revokes the refresh token, so it stays valid at Kinde if the end-session page fails or the app is signed out locally")
    fun `logout revokes the refresh token`() {
        seedSession()
        val f = facade()
        deliverResult(f, startLogout(f), Activity.RESULT_OK, Intent())
        awaitCondition { listener.logoutCount == 1 }

        assertTrue("requests: ${kinde.paths()}", kinde.paths().any { it.contains("revoke") })
    }
}
