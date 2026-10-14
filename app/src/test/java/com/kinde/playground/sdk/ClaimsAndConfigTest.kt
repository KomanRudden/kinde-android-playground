package com.kinde.playground.sdk

import au.kinde.sdk.KindeClient
import au.kinde.sdk.KindeConfig
import au.kinde.sdk.model.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claim helpers, and creating the client from code or from the manifest. */
class ClaimsAndConfigTest : SdkTestBase() {

    @Test
    fun `user details come from the ID token`() {
        seedSession()
        facade()

        val details = client().getUserDetails()

        assertEquals("kp_test_user", details.id)
        assertEquals("Test", details.givenName)
        assertEquals("User", details.familyName)
        assertEquals("tester@example.com", details.email)
        assertEquals("https://example.com/p.png", details.picture)
    }

    @Test
    fun `organization claims`() {
        seedSession()
        facade()

        assertEquals("org_test", client().getOrganization().orgCode)
        assertEquals(listOf("org_test", "org_other"), client().getUserOrganizations().orgCodes)
    }

    @Test
    fun `getClaim returns every value as a string`() {
        seedSession()
        facade()

        val exp = client().getClaim("exp").value
        val email = client().getClaim("email", TokenType.ID_TOKEN).value
        val permissions = client().getClaim("permissions").value

        assertTrue(exp is String && exp.toLong() > 0)
        assertEquals("tester@example.com", email)
        assertEquals("""["read:reports","write:reports"]""", permissions)
        assertNull(client().getClaim("not_a_claim").value)
    }

    @Test
    fun `getClaimValue keeps each claim's JSON type`() {
        seedSession()
        facade()

        assertTrue(client().getClaimValue("exp") is Number)
        assertEquals("tester@example.com", client().getClaimValue("email", TokenType.ID_TOKEN))
        assertEquals(listOf("read:reports", "write:reports"), client().getClaimValue("permissions"))
        val flags = client().getClaimValue("feature_flags") as Map<*, *>
        assertEquals(mapOf("t" to "i", "v" to 25), flags["max_items"])
        assertNull(client().getClaimValue("not_a_claim"))
    }

    @Test
    fun `signed out, claim helpers return empty values rather than throwing`() {
        facade()

        assertEquals("", client().getUserDetails().id)
        assertTrue(client().getPermissions().permissions.isEmpty())
        assertTrue(client().getAllFlags().isEmpty())
        assertNull(client().getClaim("sub").value)
        assertNull(client().getClaimValue("sub"))
    }

    @Test
    fun `programmatic config rejects malformed domains before creating a client`() {
        resetKindeClient()
        listOf("https://x.kinde.com", "x.kinde.com/path", "x.kinde.com:443", " x.kinde.com", "").forEach { bad ->
            assertThrows("domain '$bad'", IllegalArgumentException::class.java) {
                KindeClient.getInstance(context, KindeConfig(bad, "client"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { KindeClient.getInstance(context, KindeConfig("x.kinde.com", "has space")) }
    }

    @Test
    fun `configuration is fixed once the client exists`() {
        val first = client()

        assertSame(first, KindeClient.getInstance(context.applicationContext))
        assertThrows(IllegalStateException::class.java) {
            KindeClient.getInstance(context, KindeConfig("other.kinde.invalid", kinde.clientId))
        }
    }

    @Test
    fun `manifest meta-data is required when no config is given`() {
        resetKindeClient()
        installMetaData(domain = null, clientId = kinde.clientId)

        assertThrows(IllegalStateException::class.java) { KindeClient.getInstance(context) }
    }
}
