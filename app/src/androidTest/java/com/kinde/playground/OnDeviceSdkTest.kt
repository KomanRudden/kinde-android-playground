package com.kinde.playground

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.kinde.sdk.KindeClient
import au.kinde.sdk.model.TokenType
import au.kinde.sdk.store.Store
import com.kinde.playground.checks.Outcome
import com.kinde.playground.checks.SecurityChecks
import com.kinde.playground.checks.SelfChecks
import com.kinde.playground.checks.SetupChecks
import com.kinde.playground.core.KindeHub
import com.kinde.playground.sdk.KnownIssueRule
import com.kinde.playground.ui.MainActivity
import com.kinde.playground.ui.SecondActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier

/**
 * The SDK on a real Android runtime: storage, the process-wide client across
 * activities and recreation, and the on-device self-checks and security audit.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceSdkTest {

    @get:Rule
    val knownIssues = KnownIssueRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The SDK names its prefs file after a SHA-256 of the domain; delete test files so they don't pile up in the app's data. */
    private fun deleteStore(domain: String) {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }
        context.deleteSharedPreferences("app_prefs_secure_$hash")
    }

    private fun configured() = assumeTrue("Fill in .env and rebuild to run this.", KindeHub.clientOrNull(context) != null)

    private fun failures(run: com.kinde.playground.checks.CheckRun, except: Set<String> = emptySet()) =
        run.checks.filter { it.outcome == Outcome.FAILED && it.name !in except }.joinToString("\n") { "${it.group} / ${it.name}: ${it.result}" }

    @Test
    fun selfChecksPass() {
        val run = SelfChecks.run(context)
        assertEquals(failures(run), 0, run.failed)
    }

    @Test
    fun setupChecksPassWhenConfigured() {
        configured()
        val run = SetupChecks.run(context)
        assertEquals(failures(run), 0, run.failed)
    }

    @Test
    fun storeEncryptsWithTheAndroidKeystore() {
        val domain = "keystore-test.kinde.invalid"
        Store(context, domain).saveState("""{"refreshToken":"rt_device_secret"}""")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }
        val raw = context.getSharedPreferences("app_prefs_secure_$hash", Context.MODE_PRIVATE).getString("auth_state", null)!!
        deleteStore(domain)
        assertTrue("expected a Keystore-encrypted value (k1:), got ${raw.take(3)}", raw.startsWith("k1:"))
        assertTrue(!raw.contains("rt_device_secret"))
    }

    @Test
    fun storeRoundTripsOnThisDevice() {
        val store = Store(context, "device-test.kinde.invalid")
        store.saveState("""{"x":1}""")
        store.saveKeys("""{"keys":[]}""")
        assertEquals("""{"x":1}""", store.getState())
        assertEquals("""{"keys":[]}""", store.getKeys())
        store.clearState()
        assertEquals(null, store.getState())
        deleteStore("device-test.kinde.invalid")
    }

    @Test
    fun concurrentFirstWritesStayReadable() {
        // Probabilistic on a real device (a few percent per cold start), so keep trying
        // until it shows rather than declaring it fixed after a lucky handful.
        var attempts = 0
        var corrupted = false
        while (!corrupted && attempts < 300) {
            attempts++
            val domain = "device-race-$attempts-${System.nanoTime()}.kinde.invalid"
            val barrier = CyclicBarrier(2)
            val a = Thread { barrier.await(); Store(context, domain).saveKeys("keys-$attempts") }
            val b = Thread { barrier.await(); Store(context, domain).saveState("state-$attempts") }
            a.start(); b.start(); a.join(); b.join()
            corrupted = runCatching { Store(context, domain).getKeys() }.getOrNull() != "keys-$attempts" ||
                runCatching { Store(context, domain).getState() }.getOrNull() != "state-$attempts"
            deleteStore(domain)
        }
        assertEquals("corrupted after $attempts cold starts", false, corrupted)
    }

    @Test
    fun oneClientSurvivesRecreation() {
        configured()
        val before = KindeClient.getInstance(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(before, KindeClient.getInstance(activity))
                assertEquals(before.getToken(TokenType.ACCESS_TOKEN), activity.sdk?.getToken(TokenType.ACCESS_TOKEN))
            }
        }
    }

    @Test
    fun secondActivitySharesTheSession() {
        configured()
        ActivityScenario.launch<SecondActivity>(Intent(context, SecondActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(KindeClient.getInstance(context), KindeClient.getInstance(activity))
                assertEquals(KindeClient.getInstance(context).isAuthenticated(), KindeClient.getInstance(activity).isAuthenticated())
            }
        }
    }

    @Test
    fun securityAuditFindsNoLeaksAndRejectsForgedRedirects() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val run = SecurityChecks.run(context, probes = true)
            // These describe SDK design issues reported in the README, not leaks in this app.
            val sdkDesign = setOf("Encryption key", "isAuthenticated() and expiry")
            assertEquals(failures(run, sdkDesign), 0, run.checks.count { it.outcome == Outcome.FAILED && it.name !in sdkDesign })
            val logs = run.checks.first { it.name == "No tokens in logcat" }
            assertTrue(logs.result, logs.outcome != Outcome.FAILED)
        }
    }
}
