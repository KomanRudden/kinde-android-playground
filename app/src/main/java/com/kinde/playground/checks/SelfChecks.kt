package com.kinde.playground.checks

import android.content.Context
import android.os.Handler
import android.os.Looper
import au.kinde.sdk.InvitationState
import au.kinde.sdk.KindeClient
import au.kinde.sdk.KindeConfig
import au.kinde.sdk.store.Store
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.Jwt
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device checks that need no network and no sign-in: SDK guard rails and
 * building blocks exercised on a real Android runtime (the host suites use
 * Robolectric). Run off the main thread.
 */
object SelfChecks {

    fun run(context: Context, config: PlaygroundConfig = KindeHub.config): CheckRun {
        val started = System.currentTimeMillis()
        val checks = mutableListOf<Check>()
        checks += storeRoundTrip(context)
        checks += invitationState()
        checks += configValidation(context)
        val client = KindeHub.clientOrNull(context)
        if (client != null) {
            checks += singleton(context, client, config)
            checks += mainThreadGuard(client)
            checks += signedOutGuard(client)
            checks += backgroundAccess(context, client)
        } else {
            checks += Judge.skipped("SDK guards", "Client checks", "Would exercise the shared KindeClient.",
                "The playground isn't configured, so there is no client.")
        }
        checks += playgroundHelpers()
        return CheckRun("On-device", checks, System.currentTimeMillis() - started)
    }

    private fun pass(g: String, n: String, did: String, ok: Boolean, good: String, bad: String) =
        Check(g, n, did, if (ok) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP, if (ok) good else bad)

    private fun storeRoundTrip(context: Context): Check {
        val g = "SDK storage"
        val store = Store(context, "self-check.kinde.invalid")
        val value = """{"probe":"${System.nanoTime()}"}"""
        store.saveState(value)
        val read = store.getState()
        store.clearState()
        val cleared = store.getState()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("self-check.kinde.invalid".toByteArray()).joinToString("") { "%02x".format(it) }
        context.deleteSharedPreferences("app_prefs_secure_$hash")
        return pass(g, "Store round trip", "Saves, reads and clears a value with the SDK's Store on this device.",
            read == value && cleared == null, "Read back exactly what was saved; clearState() removed it.",
            "Read ${read?.length ?: 0} chars back (expected ${value.length}); after clear: ${cleared != null}.")
    }

    private fun invitationState(): Check {
        val s = InvitationState()
        s.startHandling("inv_1")
        val handling = s.isHandling && s.isProcessed("inv_1") && !s.isProcessed("inv_2")
        s.completeHandling()
        val completed = !s.isHandling && s.isProcessed("inv_1")
        s.reset()
        val reset = !s.isProcessed("inv_1")
        return pass("SDK guards", "Invitation de-duplication", "Drives InvitationState through start, complete and reset.",
            handling && completed && reset, "A processed code is remembered until reset, so a deep link isn't handled twice.",
            "handling=$handling completed=$completed reset=$reset")
    }

    private fun configValidation(context: Context): Check {
        val rejected = listOf("https://example.kinde.com", "example.kinde.com/path", "example.kinde.com:443", "bad domain")
            .filter { bad ->
                runCatching { KindeClient.getInstance(context, KindeConfig(bad, "client")) }
                    .exceptionOrNull() is IllegalArgumentException
            }
        return pass("SDK guards", "KindeConfig validation", "Passes four malformed domains to KindeClient.getInstance(context, config).",
            rejected.size == 4, "All four were rejected with IllegalArgumentException before touching the shared client.",
            "Only ${rejected.size} of 4 were rejected: $rejected")
    }

    private fun singleton(context: Context, client: KindeClient, config: PlaygroundConfig): List<Check> {
        val g = "SDK guards"
        val same = KindeClient.getInstance(context.applicationContext) === client
        val conflict = runCatching {
            KindeClient.getInstance(context, KindeConfig("other.kinde.invalid", config.clientId))
        }.exceptionOrNull()
        return listOf(
            pass(g, "One client per process", "Calls KindeClient.getInstance from two contexts.", same,
                "Both return the same instance, so every activity and worker shares one token state.", "Got two different instances."),
            pass(g, "Configuration is fixed", "Asks for a client with a different domain.", conflict is IllegalStateException,
                "Rejected with IllegalStateException: switching environments needs a process restart.",
                "Expected IllegalStateException, got ${conflict?.let { Redact.error(it) } ?: "a client"}."),
        )
    }

    private fun mainThreadGuard(client: KindeClient): Check {
        val before = KindeHub.events.value.firstOrNull()?.id ?: -1
        val latch = CountDownLatch(1)
        var result: Any? = "unset"
        val (_, http) = com.kinde.playground.core.SdkLog.capture {
            Handler(Looper.getMainLooper()).post {
                result = client.getUser()
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
        }
        val requests = http.count { it.url.contains("user_profile") }
        val reported = KindeHub.events.value.filter { it.id > before && it.type == KindeHub.EventType.EXCEPTION }.map { it.detail }
        val signedIn = client.isAuthenticated()
        // The listener only hears WrongThreadException when a facade is attached;
        // without one the SDK logs it. The guard itself is: null result, no request.
        val ok = result == null && requests == 0
        return pass("SDK guards", "Network calls refused on the main thread", "Calls getUser() on the main thread.",
            ok,
            when {
                !signedIn -> "Returned null without a request (signed out, so NotAuthorizedException is checked first)."
                reported.any { "WrongThread" in it } -> "Returned null without a request and reported WrongThreadException to the listener."
                else -> "Returned null without a request. No listener was attached, so the SDK only logged WrongThreadException."
            },
            "Returned ${result?.let { "a value" } ?: "null"} after $requests request(s); listener saw $reported.")
    }

    private fun signedOutGuard(client: KindeClient): Check {
        if (client.isAuthenticated()) {
            return Judge.skipped("SDK guards", "Calls need a session", "Would call getUser() while signed out.", "You're signed in. Sign out to run this one.")
        }
        val result = runCatching { client.getUser() }
        return pass("SDK guards", "Calls need a session", "Calls getUser() while signed out.",
            result.isSuccess && result.getOrNull() == null,
            "Returned null without a network call (NotAuthorizedException).",
            "Got ${result.exceptionOrNull()?.let { Redact.error(it) } ?: "a value"}.")
    }

    private fun backgroundAccess(context: Context, client: KindeClient): Check {
        var fromThread: String? = "unset"
        val t = Thread {
            fromThread = KindeClient.getInstance(context.applicationContext).getToken(au.kinde.sdk.model.TokenType.ACCESS_TOKEN)
        }
        t.start()
        t.join(3000)
        val direct = client.getToken(au.kinde.sdk.model.TokenType.ACCESS_TOKEN)
        return pass("SDK guards", "Token access without an activity", "Reads the access token from a plain background thread via KindeClient.getInstance.",
            fromThread == direct,
            if (direct == null) "Both report no token (signed out)." else "The background read matches the facade's token ${Redact.mask(direct)}.",
            "Background thread saw a different token.")
    }

    private fun playgroundHelpers(): Check {
        val token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJrcF8xMjMiLCJleHAiOjQxMDI0NDQ4MDB9.c2ln"
        val decoded = Jwt.decode(token)
        val redacted = Redact.text("x $token y https://a/cb?code=abc&state=s")
        val ok = decoded?.sub == "kp_123" && decoded.exp == 4102444800 && "eyJ" !in redacted && "abc" !in redacted
        return pass("Playground", "Decode and redact", "Decodes a sample JWT and redacts it from text.", ok,
            "Claims decode, and tokens and codes are removed from displayed text.", "Got sub=${decoded?.sub}, redacted=\"$redacted\".")
    }
}
