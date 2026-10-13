package com.kinde.playground

import android.app.Application
import com.kinde.playground.checks.HostReport
import com.kinde.playground.checks.Judge
import com.kinde.playground.checks.Outcome
import com.kinde.playground.config.ConfigMode
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.Calls
import com.kinde.playground.core.Jwt
import com.kinde.playground.core.Redact
import com.kinde.playground.core.SdkLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The playground's own helpers: config validation, redaction, JWT display, log parsing, check judging. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaygroundUnitTest {

    private fun config(
        domain: String = "acme.kinde.com",
        clientId: String = "abc123",
        scheme: String = "com.kinde.playground",
        mode: String = "programmatic",
        scopes: List<String> = listOf("openid", "offline", "email", "profile"),
    ) = PlaygroundConfig(domain, clientId, null, scheme, mode, scopes, "read:reports", "admin", "beta", "theme", "max",
        "pro", null, null, null, allowMutations = false, debug = false, sdkSource = "test")

    // -- Config -------------------------------------------------------------------------

    @Test
    fun `valid config has no problems`() {
        assertTrue(config().problems().isEmpty())
        assertTrue(config().isConfigured)
        assertEquals("com.kinde.playground://kinde_callback", config().loginRedirect)
        assertEquals("com.kinde.playground://kinde_logout", config().logoutRedirect)
    }

    @Test
    fun `missing or malformed domain and client ID block startup`() {
        assertFalse(config(domain = "").isConfigured)
        assertFalse(config(domain = "acme.kinde.com/path").isConfigured)
        assertFalse(config(domain = "acme.kinde.com:443").isConfigured)
        assertFalse(config(clientId = "").isConfigured)
        assertFalse(config(clientId = "a b").isConfigured)
        assertFalse(config(scheme = "1bad").isConfigured)
    }

    @Test
    fun `scope and mode problems are warnings only`() {
        val c = config(mode = "weird", scopes = listOf("email"))
        assertTrue(c.isConfigured)
        assertEquals(ConfigMode.PROGRAMMATIC, c.configMode)
        assertEquals(setOf("PLAYGROUND_CONFIG_MODE", "PLAYGROUND_SCOPES"), c.problems().map { it.key }.toSet())
        assertEquals(ConfigMode.MANIFEST, config(mode = "MANIFEST").configMode)
    }

    @Test
    fun `dashboard masks the client ID`() {
        val rows = config(clientId = "0123456789abcdef").describe().toMap()
        assertEquals("0123…cdef (16 chars)", rows["KINDE_CLIENT_ID"])
    }

    // -- Redaction ------------------------------------------------------------------------

    @Test
    fun `mask keeps only the ends`() {
        assertEquals("(empty)", Redact.mask(null))
        assertEquals("•••••", Redact.mask("short"))
        assertEquals("abcd…wxyz (26 chars)", Redact.mask("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `text redaction removes JWTs, codes and known secrets`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJrcF8xIn0.c2lnbmF0dXJl"
        val out = Redact.text("token=$jwt url=https://x/cb?code=abc123&state=s1 secret=rt_live_123456", listOf("rt_live_123456"))
        assertFalse(out.contains("eyJ"))
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("rt_live_123456"))
        assertTrue(out.contains("state=s1"))
    }

    @Test
    fun `errors show type and status, never URLs`() {
        val e = Exception("response is unsuccessful for https://acme.kinde.com/account_api/v1/roles?code=x:403 Forbidden")
        val shown = Redact.error(e)
        assertTrue(shown, shown.startsWith("Exception (HTTP 403)"))
        assertFalse(shown.contains("acme.kinde.com"))
    }

    // -- JWT --------------------------------------------------------------------------------

    @Test
    fun `decode reads header and payload without padding`() {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val token = enc.encodeToString("""{"alg":"RS256","kid":"k1"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"kp_1","exp":4102444800,"aud":["a","b"],"iss":"https://acme.kinde.com"}""".toByteArray()) + ".sig"
        val d = Jwt.decode(token)!!
        assertEquals("k1", d.kid)
        assertEquals("kp_1", d.sub)
        assertEquals(4102444800L, d.exp)
        assertEquals(listOf("a", "b"), d.audiences)
        assertNull(Jwt.decode("not-a-jwt"))
        assertNull(Jwt.decode(null))
    }

    // -- SDK log parsing ----------------------------------------------------------------------

    @Test
    fun `logcat lines pair URLs with response codes`() {
        val out = """
            1728712345.100  4242  4260 D ApiClient: URL: https://acme.kinde.com/account_api/v1/roles
            1728712345.180  4242  4260 D ApiClient: Response code: 403
            1728712345.190  4242  4260 D ApiClient: Response body length: 10
            1728712345.300  4242  4261 D ApiClient: URL: https://acme.kinde.com/oauth2/token
            1728712345.400  4242  4261 D ApiClient: Response code: 200
            1728712345.500  4242  4262 D ApiClient: URL: https://acme.kinde.com/account_api/v1/roles
        """.trimIndent()
        val lines = SdkLog.parse(out)
        assertEquals(6, lines.size)
        assertEquals(1728712345100L, lines[0].epochMs)
        val calls = SdkLog.httpCalls(lines)
        assertEquals(listOf(403, 200, null), calls.map { it.status })
        assertTrue(calls[1].url.endsWith("/oauth2/token"))
    }

    // -- Judging checks -----------------------------------------------------------------------

    @Test
    fun `judge fails on errors, HTTP errors and null results`() {
        val ok = Judge.judge("g", "n", "did", "value", null, listOf(SdkLog.HttpCall("https://x/a", 200)), emptyList(), true, "s", { "fine" })
        val http = Judge.judge("g", "n", "did", null, null, listOf(SdkLog.HttpCall("https://x/a", 403)), listOf("Exception (HTTP 403)"), true, "roles", { "fine" })
        val nul = Judge.judge("g", "n", "did", null, null, emptyList(), emptyList(), false, "s", { "fine" })
        val thrown = Judge.judge("g", "n", "did", null, IllegalStateException("boom"), emptyList(), emptyList(), false, "s", { "fine" })
        assertEquals(Outcome.PASSED, ok.outcome)
        assertEquals(Outcome.FAILED, http.outcome)
        assertTrue(http.result.contains("HTTP 403"))
        assertTrue(http.result.contains("scope"))
        assertEquals(Outcome.FAILED, nul.outcome)
        assertEquals(Outcome.FAILED, thrown.outcome)
        assertTrue(ok.http.startsWith("GET https://x/a came back 200"))
    }

    @Test
    fun `a refusal the SDK recovered from by refreshing still passes`() {
        val calls = listOf(
            SdkLog.HttpCall("https://x/oauth2/user_profile", 403),
            SdkLog.HttpCall("https://x/oauth2/token", 200),
            SdkLog.HttpCall("https://x/oauth2/user_profile", 200),
        )
        val c = Judge.judge("g", "n", "did", "profile", null, calls, emptyList(), true, "s", { "fine" })
        assertEquals(Outcome.PASSED, c.outcome)
        assertTrue(c.result.contains("refreshed"))
        val stillRefused = Judge.judge("g", "n", "did", null, null, calls.dropLast(1), emptyList(), true, "s", { "fine" })
        assertEquals(Outcome.FAILED, stillRefused.outcome)
    }

    @Test
    fun `describe renders SDK types that are not data classes`() {
        val perms = au.kinde.sdk.model.ClaimData.Permissions("org_1", listOf("a"))
        assertEquals("Permissions(orgCode=\"org_1\", permissions=[a])", Calls.describe(perms))
        val flag = au.kinde.sdk.model.Flag("f", au.kinde.sdk.model.FlagType.Integer, 5.0, false)
        assertTrue(Calls.describe(flag).contains("5.0 : Double"))
    }

    // -- Host report -----------------------------------------------------------------------------

    @Test
    fun `host report parses suites, files and outcomes`() {
        val json = """
            {"generatedAt":"2026-10-12 10:00","git":"abc123","suites":[{"name":"SDK unit","about":"x","passed":2,"failed":1,"skipped":0,"known":1,
             "files":[{"name":"StorageTest","area":"Storage","explain":"e","tests":[
               {"name":"a","outcome":"passed","message":""},{"name":"b","outcome":"known","message":"KNOWN ISSUE: x"}]},
              {"name":"LoginFlowTest","area":"Auth","explain":"","tests":[{"name":"c","outcome":"failed","message":"boom"}]}]}]}
        """.trimIndent()
        val r = HostReport.parse(json)
        assertEquals("abc123", r.git)
        assertEquals(Outcome.WARN, r.suites[0].files[0].outcome)
        assertEquals(Outcome.FAILED, r.suites[0].files[1].outcome)
    }
}
