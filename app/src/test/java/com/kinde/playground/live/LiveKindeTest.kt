package com.kinde.playground.live

import android.app.Application
import au.kinde.sdk.api.UsersApi
import au.kinde.sdk.infrastructure.ApiClient
import au.kinde.sdk.keys.Keys
import com.google.gson.Gson
import com.kinde.playground.sdk.KnownIssue
import com.kinde.playground.sdk.KnownIssueRule
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-side live checks against your Kinde business, run from your Mac:
 *
 *     ./gradlew :app:testDebugUnitTest -Plive=true --tests '*LiveKindeTest*'
 *
 * Reads .env directly. The Management API credentials are used here only and are
 * never compiled into the APK. Without -Plive=true every test is skipped.
 *
 * These ground the SDK contract suite in reality: they confirm the endpoint paths
 * the SDK hard-codes, the error shapes the fake server imitates, that your
 * Kinde application has the playground's redirect URLs registered, and how the
 * SDK's Management models fare against the real API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveKindeTest {

    @get:Rule
    val knownIssues = KnownIssueRule()

    private val env: Map<String, String> by lazy {
        val path = System.getProperty("playground.envFile") ?: return@lazy emptyMap()
        val file = File(path)
        if (!file.isFile) return@lazy emptyMap()
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val (k, v) = line.split('=', limit = 2)
                k.trim() to v.trim().removeSurrounding("\"").removeSurrounding("'")
            } + System.getenv().filterKeys { it.startsWith("KINDE_") || it.startsWith("PLAYGROUND_") }.filterValues { it.isNotBlank() }
    }

    private val domain get() = env["KINDE_DOMAIN"].orEmpty().removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val base get() = "https://$domain"
    private val clientId get() = env["KINDE_CLIENT_ID"].orEmpty()
    /** Custom domains can't be the Management API audience: use your-business.kinde.com. */
    private val mgmtBase: String
        get() = "https://" + (env["KINDE_MANAGEMENT_DOMAIN"].orEmpty().ifBlank { domain })
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val scheme get() = env["KINDE_REDIRECT_SCHEME"].orEmpty().ifBlank { "com.kinde.playground" }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    @Before
    fun requireLive() {
        assumeTrue("Live tests are off. Run with -Plive=true.", System.getProperty("playground.live") == "true")
        assumeTrue("KINDE_DOMAIN is not set in .env.", domain.isNotBlank())
    }

    private data class Resp(val status: Int, val body: String) {
        val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
    }

    private fun get(url: String, bearer: String? = null): Resp {
        val req = Request.Builder().url(url).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build()
        return http.newCall(req).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
    }

    private fun postForm(url: String, form: Map<String, String>): Resp {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).post(body).build()).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
    }

    private val m2mToken: String by lazy {
        val id = env["KINDE_MANAGEMENT_CLIENT_ID"].orEmpty()
        val secret = env["KINDE_MANAGEMENT_CLIENT_SECRET"].orEmpty()
        assumeTrue("Set KINDE_MANAGEMENT_CLIENT_ID and KINDE_MANAGEMENT_CLIENT_SECRET for Management checks.", id.isNotBlank() && secret.isNotBlank())
        val resp = postForm("$mgmtBase/oauth2/token", mapOf(
            "grant_type" to "client_credentials", "client_id" to id, "client_secret" to secret, "audience" to "$mgmtBase/api",
        ))
        val notAllowed = resp.json?.optString("error_description").orEmpty().contains("whitelisted")
        val hint = when {
            !notAllowed -> ""
            mgmtBase.endsWith(".kinde.com") ->
                " Kinde won't issue a token for $mgmtBase/api to this M2M app: authorize it for the Kinde Management API " +
                    "(Settings > APIs > Kinde Management API > Applications, enable the app, then grant its scopes)."
            else ->
                " The audience $mgmtBase/api isn't allowed. KINDE_MANAGEMENT_DOMAIN must be your Kinde subdomain " +
                    "(your-business.kinde.com, shown in Kinde under Settings > Details), not the custom domain."
        }
        if (resp.status != 200) throw com.kinde.playground.sdk.SetupFailure("M2M token request: HTTP ${resp.status} ${resp.json?.optString("error")}.$hint")
        resp.json!!.getString("access_token")
    }

    // -- Public endpoints ----------------------------------------------------------------------

    @Test
    fun `discovery advertises the endpoints the SDK hard-codes`() {
        val doc = get("$base/.well-known/openid-configuration").json
        assertNotNull("discovery JSON", doc)
        assertEquals(base, doc!!.getString("issuer").trimEnd('/'))
        assertEquals("$base/oauth2/auth", doc.getString("authorization_endpoint"))
        assertEquals("$base/oauth2/token", doc.getString("token_endpoint"))
        assertEquals("$base/logout", doc.getString("end_session_endpoint"))
    }

    @Test
    fun `the SDK's JWKS path serves the keys discovery advertises`() {
        val advertised = get("$base/.well-known/openid-configuration").json!!.getString("jwks_uri")
        val kids = { url: String -> get(url).json?.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("kid") }.toSet() } }
        println("Discovery advertises $advertised; the SDK fetches $base/.well-known/jwks.json.")
        assertEquals(kids(advertised), kids("$base/.well-known/jwks.json"))
    }

    @Test
    fun `JWKS parses into the SDK's Keys model`() {
        val body = get("$base/.well-known/jwks.json").body
        val keys = Gson().fromJson(body, Keys::class.java)
        assertTrue("at least one key", keys.keys.isNotEmpty())
        keys.keys.forEach { k ->
            assertEquals("RSA", k.keyType)
            assertTrue(k.modulus.isNotBlank() && k.exponent.isNotBlank())
        }
        println("JWKS: ${keys.keys.size} key(s) ${keys.keys.map { it.keyId }}. The SDK verifies with the first only.")
    }

    @Test
    fun `an invalid refresh token gets HTTP 400 invalid_grant, which the SDK does not parse`() {
        assumeTrue("KINDE_CLIENT_ID is not set.", clientId.isNotBlank())
        val resp = postForm("$base/oauth2/token", mapOf("grant_type" to "refresh_token", "refresh_token" to "invalid-${System.nanoTime()}", "client_id" to clientId))
        println("Token endpoint answered an invalid refresh token with HTTP ${resp.status}: ${resp.json?.optString("error")}")
        assertTrue("4xx", resp.status in 400..499)
        assertNotNull("OAuth error body", resp.json?.optString("error")?.takeIf { it.isNotBlank() })
    }

    private fun invalidTokenStatus(path: String): Int = invalidTokenReply(path).status

    private fun invalidTokenReply(path: String): Resp {
        val resp = get("$base/$path", bearer = "eyJhbGciOiJSUzI1NiJ9.e30.invalid")
        println("$path answered an invalid token with HTTP ${resp.status}: ${resp.body.take(80)}")
        return resp
    }

    @Test
    fun `the OAuth profile endpoint answers an invalid token with 403, which the SDK refreshes on`() {
        assertEquals(403, invalidTokenStatus("oauth2/user_profile"))
    }

    @Test
    fun `the Account API answers an invalid token in a way the SDK refreshes on`() {
        // The SDK refreshes on 401, and on a 400/403 whose body says TOKEN_ERROR.
        val reply = invalidTokenReply("account_api/v1/roles")
        assertTrue(
            "HTTP ${reply.status}: ${reply.body.take(80)}",
            reply.status == 401 || (reply.status in listOf(400, 403) && reply.body.contains("TOKEN_ERROR"))
        )
    }

    // -- Management API (M2M) -----------------------------------------------------------------------

    private fun appUrls(kind: String): String {
        assumeTrue("KINDE_CLIENT_ID is not set.", clientId.isNotBlank())
        val resp = get("$mgmtBase/api/v1/applications/$clientId/$kind", m2mToken)
        assumeTrue("Reading $kind needs the read:application_redirect_uris / read:application_logout_uris scopes (HTTP ${resp.status}).", resp.status == 200)
        return resp.body
    }

    @Test
    fun `the playground callback URL is registered on the Kinde application`() {
        val body = appUrls("auth_redirect_urls")
        assertTrue("Add $scheme://kinde_callback to Allowed callback URLs", body.contains("$scheme://kinde_callback"))
    }

    @Test
    fun `the playground logout URL is registered on the Kinde application`() {
        val body = appUrls("auth_logout_urls")
        assertTrue("Add $scheme://kinde_logout to Allowed logout redirect URLs", body.contains("$scheme://kinde_logout"))
    }

    private fun demoExists(path: String, key: String, label: String) {
        val resp = get("$mgmtBase$path", m2mToken)
        assumeTrue("Listing $label needs a read scope (HTTP ${resp.status}).", resp.status == 200)
        assumeTrue("$label '$key' doesn't exist in this business; the demo checks will show it as not granted.", resp.body.contains("\"$key\""))
    }

    @Test
    fun `demo permission exists`() = demoExists("/api/v1/permissions?page_size=500", env["PLAYGROUND_DEMO_PERMISSION"] ?: "read:reports", "permission")

    @Test
    fun `demo role exists`() = demoExists("/api/v1/roles?page_size=500", env["PLAYGROUND_DEMO_ROLE"] ?: "admin", "role")

    @Test
    fun `demo feature flag exists`() = demoExists("/api/v1/environment/feature_flags", env["PLAYGROUND_DEMO_FLAG"] ?: "beta_dashboard", "feature flag")

    @Test
    @KnownIssue("The SDK's UsersApi models don't match Kinde's users_response (object with users[], string kp_ ids)")
    fun `the SDK's getUsers model parses the real Management API response`() {
        val token = m2mToken
        val users = ApiClient("$mgmtBase/", authNames = arrayOf("kindeBearerAuth")).setBearerToken(token)
            .createService(UsersApi::class.java).getUsers(pageSize = 1).execute()
        // A refusal means the test never reached the SDK's model, so it isn't the known issue.
        if (users.code() != 200) throw com.kinde.playground.sdk.SetupFailure("GET /api/v1/users returned HTTP ${users.code()}: grant the M2M app the read:users scope.")
        assertNotNull(users.body())
    }
}
