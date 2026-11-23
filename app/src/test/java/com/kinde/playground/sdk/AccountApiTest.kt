package com.kinde.playground.sdk

import au.kinde.sdk.api.ApiOptions
import au.kinde.sdk.model.FlagType
import au.kinde.sdk.model.TokenType
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The OAuth profile endpoints and the Account API with forceApi, including caching and 403 handling. */
class AccountApiTest : SdkTestBase() {

    private val api = ApiOptions(forceApi = true)
    private val fresh = ApiOptions(forceApi = true, useCache = false)

    private fun signedIn(): FakeKinde.Issued = seedSession().also { facade() }

    private fun bearer(path: String) = kinde.requests.last { it.requestUrl?.encodedPath == path }.getHeader("Authorization")

    // -- Profile ----------------------------------------------------------------------------

    @Test
    fun `getUser calls the v1 profile endpoint with the access token`() {
        val issued = signedIn()

        val profile = onBackground { client().getUser() }

        assertEquals("kp_test_user", profile?.id)
        assertEquals("tester@example.com", profile?.preferredEmail)
        assertEquals("Bearer ${issued.access}", bearer("/oauth2/user_profile"))
    }

    @Test
    fun `getUserProfileV2 parses the v2 profile`() {
        signedIn()

        val profile = onBackground { client().getUserProfileV2() }

        assertEquals("kp_test_user", profile?.id)
        assertEquals("Test User", profile?.name)
        assertEquals("Test", profile?.givenName)
        assertEquals("1700000000", profile?.updatedAt)
    }

    @Test
    @KnownIssue("UserProfileV2 drops email, email_verified, picture, sub and preferred_username, which Kinde's v2 profile returns")
    fun `getUserProfileV2 exposes the email Kinde returns`() {
        signedIn()

        val profile = onBackground { client().getUserProfileV2() }!!

        val fields = profile.javaClass.declaredFields.map { it.name }
        assertTrue("UserProfileV2 fields: $fields", "email" in fields)
    }

    @Test
    fun `network calls on the main thread return null and report WrongThreadException`() {
        signedIn()

        val profile = client().getUser()

        assertNull(profile)
        assertTrue(listener.exceptionNames().contains("WrongThreadException"))
        assertEquals(0, kinde.count("/oauth2/user_profile"))
    }

    @Test
    fun `calls while signed out return null without a request`() {
        facade()
        idle()

        assertNull(onBackground { client().getUser() })
        assertEquals(0, kinde.count("/oauth2/user_profile"))
    }

    // -- Permissions and roles ----------------------------------------------------------------

    @Test
    fun `permissions come from the token unless forceApi is set`() {
        signedIn()

        val fromToken = client().getPermissions()
        val fromApi = onBackground { client().getPermissions(fresh) }

        assertEquals(listOf("read:reports", "write:reports"), fromToken.permissions)
        assertEquals("org_test", fromToken.orgCode)
        assertEquals(fromToken.permissions, fromApi.permissions)
        assertEquals("org_test", fromApi.orgCode)
        assertEquals(1, kinde.count("/account_api/v1/permissions"))
    }

    @Test
    fun `getPermission and getRole check one key in either source`() {
        signedIn()

        assertTrue(client().getPermission("read:reports").isGranted)
        assertFalse(client().getPermission("delete:everything").isGranted)
        assertTrue(onBackground { client().getPermission("write:reports", fresh) }.isGranted)
        assertTrue(client().getRole("admin").isGranted)
        assertFalse(onBackground { client().getRole("owner", fresh) }.isGranted)
    }

    @Test
    fun `API results are cached for repeated calls until clearCache`() {
        signedIn()

        onBackground { client().getRoles(api) }
        onBackground { client().getRoles(api) }
        onBackground { client().getRole("admin", api) }
        assertEquals("cached", 1, kinde.count("/account_api/v1/roles"))

        onBackground { client().getRoles(fresh) }
        assertEquals("useCache=false bypasses", 2, kinde.count("/account_api/v1/roles"))

        client().clearCache()
        onBackground { client().getRoles(api) }
        assertEquals("clearCache empties", 3, kinde.count("/account_api/v1/roles"))
    }

    @Test
    fun `forceApi failures throw instead of returning empty results`() {
        signedIn()
        kinde.overrides["/account_api/v1/permissions"] = {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"success":false}""")
        }

        assertThrows(Exception::class.java) { onBackground { client().getPermissions(fresh) } }
    }

    @Test
    @KnownIssue("Kinde's roles claim is an array of {id, key, name} objects (verified live); the SDK reads it as strings, so token getRoles() returns raw JSON and getRole(key) is always false")
    fun `roles from the token work with Kinde's object-shaped roles claim`() {
        val roleObjects = listOf(mapOf("id" to "r1", "key" to "role_admin", "name" to "Admin"), mapOf("id" to "r2", "key" to "role_read", "name" to "Read"))
        seedSession(kinde.issue(kinde.claims(extra = mapOf("roles" to roleObjects))))
        facade()

        assertEquals(listOf("role_admin", "role_read"), client().getRoles().roles)
        assertTrue(client().getRole("role_admin").isGranted)
    }

    // -- Feature flags ---------------------------------------------------------------------------

    @Test
    fun `typed flags from the Account API`() {
        signedIn()

        assertEquals(true, onBackground { client().getBooleanFlag("beta_dashboard", false, fresh) })
        assertEquals("dark", onBackground { client().getStringFlag("theme", "light", fresh) })
        assertEquals(25, onBackground { client().getIntegerFlag("max_items", 0, fresh) })
        assertEquals("missing flag uses the default", "x", onBackground { client().getStringFlag("nope", "x", fresh) })
        assertEquals("type mismatch uses the default", false, onBackground { client().getBooleanFlag("theme", false, fresh) })
    }

    @Test
    fun `unknown flag types from the API are skipped`() {
        signedIn()
        kinde.apiFlags = kinde.apiFlags + ("json_cfg" to ("Json" to "{}"))

        val flags = onBackground { client().getAllFlags(fresh) }

        assertFalse(flags.containsKey("json_cfg"))
        assertEquals(setOf("beta_dashboard", "theme", "max_items"), flags.keys)
    }

    @Test
    @KnownIssue("Integer flags from getAllFlags(forceApi) are Doubles (Gson), while the token path gives Ints")
    fun `integer flags have the same type from the token and the API`() {
        signedIn()

        val fromApi = onBackground { client().getAllFlags(fresh) }["max_items"]!!.value
        val fromToken = client().getAllFlags()["max_items"]!!.value

        assertEquals(fromToken.javaClass, fromApi.javaClass)
    }

    @Test
    fun `token flags with defaults and types`() {
        signedIn()

        assertEquals(true, client().getBooleanFlag("beta_dashboard", false))
        assertEquals("dark", client().getStringFlag("theme"))
        assertEquals(25, client().getIntegerFlag("max_items"))
        val missing = client().getFlag("not_there", "fallback")!!
        assertTrue(missing.isDefault)
        assertEquals("fallback", missing.value)
        assertNull("no default, no flag", client().getFlag("not_there"))
        assertEquals(FlagType.Integer, client().getAllFlags()["max_items"]?.type)
    }

    @Test
    @KnownIssue("On the token path a flag of the wrong type returns null and ignores the default; the API path returns the default")
    fun `token flag of the wrong type returns the default`() {
        signedIn()

        assertEquals(false, client().getBooleanFlag("theme", false))
    }

    // -- Billing -----------------------------------------------------------------------------------

    @Test
    fun `entitlements and a single entitlement`() {
        signedIn()

        val all = onBackground { client().getEntitlements() }!!
        val one = onBackground { client().getEntitlement("pro_reports") }!!

        assertEquals("org_test", all.data.orgCode)
        assertEquals(listOf("pro_reports", "seats"), all.data.entitlements.map { it.featureKey })
        assertEquals("pro", all.data.plans.single().key)
        assertEquals("pro_reports", one.data.entitlement.featureKey)
        assertEquals("pro_reports", kinde.requests.last { it.requestUrl?.encodedPath == "/account_api/v1/entitlement" }.requestUrl?.queryParameter("key"))
    }

    @Test
    @KnownIssue("EntitlementsMetadata.nextPageStartingAfter is a non-null String but is null on the last page, so Kotlin callers get an NPE")
    fun `last page of entitlements is safe to read`() {
        signedIn()

        val metadata = onBackground { client().getEntitlements() }!!.metadata
        val cursor: String = metadata.nextPageStartingAfter
        assertNotNull(cursor.length)
    }

    // -- Expired tokens --------------------------------------------------------------------------------

    @Test
    fun `a 403 refreshes the token once and retries with the new one`() {
        val old = signedIn()
        kinde.validAccessTokens.remove(old.access)

        val profile = onBackground { client().getUser() }

        assertEquals("kp_test_user", profile?.id)
        val refresh = kinde.tokenRequests().single()
        assertEquals("refresh_token", kinde.form(refresh)["grant_type"])
        assertEquals(old.refresh, kinde.form(refresh)["refresh_token"])
        val newToken = client().getToken(TokenType.ACCESS_TOKEN)
        assertNotEquals(old.access, newToken)
        assertEquals("Bearer $newToken", bearer("/oauth2/user_profile"))
        assertEquals("the rotated refresh token is saved", kinde.validRefreshTokens.single(), client().getRefreshToken())
    }

    @Test
    fun `an expired token on the Account API (HTTP 400) refreshes and retries`() {
        val old = signedIn()
        kinde.validAccessTokens.remove(old.access)
        kinde.rejectStatus = 400

        val roles = onBackground { runCatching { client().getRoles(fresh) }.getOrNull() }

        assertEquals(listOf("admin"), roles?.roles)
    }

    @Test
    fun `a 401 refreshes the token and retries`() {
        val old = signedIn()
        kinde.validAccessTokens.remove(old.access)
        kinde.rejectStatus = 401

        val profile = onBackground { client().getUser() }

        assertEquals("kp_test_user", profile?.id)
    }

    @Test
    fun `a listener that throws on a new token doesn't block later refreshes`() {
        val old = seedSession()
        var armed = false
        facade(object : au.kinde.sdk.SDKListener by RecordingListener() {
            override fun onNewToken(token: String) {
                if (armed) throw IllegalStateException("bug in the app's listener")
            }
        })
        armed = true
        kinde.rejectStatus = 401
        kinde.validAccessTokens.remove(old.access)

        runCatching { onBackground { client().getUser() } }
        kinde.validAccessTokens.clear()
        runCatching { onBackground { client().getUser() } }

        assertEquals("the second refresh wasn't left waiting", 2, kinde.tokenRequests().size)
    }

    @Test
    fun `listeners end on the newest token when a listener's own call refreshes it`() {
        val old = seedSession()
        var armed = false
        facade(object : au.kinde.sdk.SDKListener by RecordingListener() {
            override fun onNewToken(token: String) {
                if (!armed) return
                armed = false
                // The app reacts to the new token with an API call that finds it rejected.
                kinde.validAccessTokens.clear()
                client().getUser()
            }
        })
        val later = RecordingListener()
        facade(later)
        val replayed = later.tokens.size
        armed = true
        kinde.rejectStatus = 401
        kinde.validAccessTokens.remove(old.access)

        onBackground { client().getUser() }

        assertEquals(2, kinde.tokenRequests().size)
        val heard = later.tokens.drop(replayed).map { it.token }
        assertEquals("last token heard: $heard", client().getToken(TokenType.ACCESS_TOKEN), heard.last())
    }

    @Test
    fun `a token refreshed while a listener runs still reaches listeners when that listener throws`() {
        val old = seedSession()
        var armed = false
        facade(object : au.kinde.sdk.SDKListener by RecordingListener() {
            override fun onNewToken(token: String) {
                if (!armed) return
                armed = false
                // Another thread's call refreshes again while this listener is still running...
                kinde.validAccessTokens.clear()
                val done = CountDownLatch(1)
                Thread { runCatching { client().getUser() }; done.countDown() }.start()
                done.await(10, TimeUnit.SECONDS)
                // ...and then the listener fails.
                throw IllegalStateException("bug in the app's listener")
            }
        })
        val later = RecordingListener()
        facade(later)
        val replayed = later.tokens.size
        armed = true
        kinde.rejectStatus = 401
        kinde.validAccessTokens.remove(old.access)

        runCatching { onBackground { client().getUser() } }

        assertEquals(2, kinde.tokenRequests().size)
        val heard = later.tokens.drop(replayed).map { it.token }
        assertEquals("last token heard: $heard", client().getToken(TokenType.ACCESS_TOKEN), heard.lastOrNull())
    }

    @Test
    fun `a listener whose own calls keep failing doesn't drive endless refreshes`() {
        val old = seedSession()
        var armed = false
        var callbacks = 0
        facade(object : au.kinde.sdk.SDKListener by RecordingListener() {
            override fun onNewToken(token: String) {
                // The cap only stops this test from hanging if the SDK does loop.
                if (!armed || ++callbacks > 10) return
                runCatching { client().getUser() }
            }
        })
        armed = true
        // Kinde keeps rejecting the profile call whatever token it gets.
        kinde.overrides["/oauth2/user_profile"] = {
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""[{"Code":"TOKEN_ERROR","Message":"Missing or invalid token"}]""")
        }
        kinde.validAccessTokens.remove(old.access)

        runCatching { onBackground { client().getUser() } }

        // The app's call, the listener's call for that token (announced once), and the
        // listener's call for the announced token, which stops there.
        assertTrue("token requests: ${kinde.tokenRequests().size}", kinde.tokenRequests().size <= 3)
    }

    @Test
    fun `an onException listener that refreshes after a failed refresh doesn't hang`() {
        val old = seedSession()
        var heard: Exception? = null
        var listenerCall: String? = null
        facade(object : au.kinde.sdk.SDKListener by RecordingListener() {
            override fun onException(exception: Exception) {
                if (heard != null) return
                heard = exception
                // The app retries straight away, on the same thread, with a call that needs a refresh.
                listenerCall = runCatching { client().getUser()?.id }.getOrElse { it.toString() }
            }
        })
        kinde.rejectStatus = 401
        kinde.validAccessTokens.remove(old.access)
        // The first refresh fails at Kinde; the next one works.
        kinde.overrides["/oauth2/token"] = {
            kinde.overrides.remove("/oauth2/token")
            MockResponse().setResponseCode(503)
        }

        val caller = Thread { runCatching { client().getUser() } }
        caller.start()
        caller.join(8_000)

        assertNotNull("onException was called", heard)
        assertFalse("the listener's call is still waiting on the failed refresh", caller.isAlive)
        assertEquals("kp_test_user", listenerCall)
    }

    @Test
    fun `a call rejected with a token another thread already replaced retries without refreshing again`() {
        val old = seedSession()
        facade()
        kinde.rejectStatus = 401
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Hold the first profile call at "Kinde" until another thread has refreshed, then reject it.
        kinde.overrides["/oauth2/user_profile"] = {
            kinde.overrides.remove("/oauth2/user_profile")
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""[{"Code":"TOKEN_ERROR","Message":"Missing or invalid token"}]""")
        }
        var profileId: String? = null
        val slow = Thread { profileId = runCatching { client().getUser()?.id }.getOrNull() }
        slow.start()
        assertTrue(arrived.await(10, TimeUnit.SECONDS))

        kinde.validAccessTokens.remove(old.access)
        onBackground { client().getRoles(fresh) }
        release.countDown()
        slow.join(10_000)

        assertEquals("kp_test_user", profileId)
        assertEquals("only the other thread refreshed", 1, kinde.tokenRequests().size)
        assertEquals("Bearer ${client().getToken(TokenType.ACCESS_TOKEN)}", bearer("/oauth2/user_profile"))
    }

    @Test
    fun `a 403 for a missing scope does not refresh the session`() {
        signedIn()
        kinde.overrides["/api/v1/users"] = { MockResponse().setResponseCode(403).setBody("""{"errors":[{"code":"FORBIDDEN"}]}""") }

        onBackground { client().getUsers(pageSize = 1) }

        assertTrue("token requests: ${kinde.tokenRequests().size}", kinde.tokenRequests().isEmpty())
    }

    @Test
    fun `a refresh triggered by an API call tells the listener about the new token`() {
        val old = signedIn()
        val announced = listener.tokens.size
        kinde.validAccessTokens.remove(old.access)
        kinde.rejectStatus = 400

        onBackground { client().getRoles(fresh) }
        idle()

        val newToken = client().getToken(TokenType.ACCESS_TOKEN)
        assertNotEquals(old.access, newToken)
        assertEquals(newToken, listener.tokens.drop(announced).lastOrNull()?.token)
    }

    @Test
    fun `a 400 that isn't about the token does not refresh`() {
        signedIn()
        kinde.overrides["/account_api/v1/entitlement"] = {
            MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"errors":[{"code":"ENTITLEMENT_NOT_FOUND"}]}""")
        }

        assertNull(onBackground { client().getEntitlement("no_such_feature") })
        assertTrue(kinde.tokenRequests().isEmpty())
    }

    @Test
    fun `parallel calls with an expired token never reuse a rotated refresh token`() {
        val old = signedIn()
        kinde.validAccessTokens.remove(old.access)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val results = java.util.concurrent.CopyOnWriteArrayList<Any?>()
        repeat(4) {
            Thread {
                start.await()
                results.add(client().getRoles(fresh).roles)
                done.countDown()
            }.start()
        }
        start.countDown()
        val deadline = System.currentTimeMillis() + 15_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            idle(50)
            done.await(20, TimeUnit.MILLISECONDS)
        }

        assertEquals(4, results.size)
        assertTrue(results.all { it == listOf("admin") })
        // A refresh token presented twice would get invalid_grant and surface as a TokenException.
        assertFalse("refresh token reused: ${listener.exceptionNames()}", listener.exceptionNames().contains("TokenException"))
        assertTrue(kinde.tokenRequests().isNotEmpty())
    }
}
