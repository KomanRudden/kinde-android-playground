package com.kinde.playground

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinde.playground.checks.LiveChecks
import com.kinde.playground.checks.Outcome
import com.kinde.playground.core.KindeHub
import com.kinde.playground.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live checks against your Kinde business with the session you signed in with
 * in the app. Skipped when signed out. Run with ./scripts/live-test.sh, which
 * keeps the app's data (a plain connectedAndroidTest uninstalls it afterwards).
 */
@RunWith(AndroidJUnit4::class)
class LiveChecksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Expected to fail until the SDK is fixed; reported, not counted. See the README. */
    private val knownSdkIssues = setOf("Management API (SDK)", "Roles from the token")

    @Test
    fun liveChecksPassForTheSignedInUser() {
        val client = KindeHub.clientOrNull(context)
        assumeTrue("Not configured: fill in .env and rebuild.", client != null)
        // A facade attaches the listener, so SDK exceptions show up in the results.
        ActivityScenario.launch(MainActivity::class.java).use {
            assumeTrue("Signed out: sign in in the app, then run ./scripts/live-test.sh.", client!!.isAuthenticated())
            // The SDK pauses refresh in the background and refreshes on return to the
            // foreground. If the token expired meanwhile, wait for that refresh first.
            val secondsLeft = { com.kinde.playground.core.Jwt.decode(client.getToken(au.kinde.sdk.model.TokenType.ACCESS_TOKEN))?.secondsLeft() ?: 0 }
            val expiredAtStart = secondsLeft() <= 0
            // SDK 2.2.0's isAuthenticated() still reports such a session as signed in.
            assumeTrue(
                "The stored session has expired and has no refresh token, so it can't be renewed: sign in again, then run ./scripts/live-test.sh.",
                !(expiredAtStart && client.getRefreshToken().isNullOrBlank())
            )
            val deadline = System.currentTimeMillis() + 20_000
            while (secondsLeft() <= 10 && System.currentTimeMillis() < deadline) Thread.sleep(250)
            if (expiredAtStart) {
                Log.i("LiveChecks", "INFO Tokens / Resume: the access token had expired while the app was in the background " +
                    "and isAuthenticated() still returned true (SDK finding 7); the foreground refresh renewed it.")
            }
            assertTrue("The SDK's foreground refresh didn't renew the expired access token within 20s.", secondsLeft() > 10)
            val run = LiveChecks.run(context)
            run.checks.forEach { c ->
                Log.i("LiveChecks", "${c.outcome} ${c.group} / ${c.name}: ${c.result} | ${c.http}")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\n[${c.outcome}] ${c.group} / ${c.name}\n    ${c.result}\n")
                })
            }
            val failed = run.checks.filter { it.outcome == Outcome.FAILED && it.group !in knownSdkIssues && it.name !in knownSdkIssues }
            assertTrue(failed.joinToString("\n") { "${it.group} / ${it.name}: ${it.result} (${it.http})" }, failed.isEmpty())
        }
    }
}
