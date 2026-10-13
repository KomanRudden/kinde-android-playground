package com.kinde.playground.sdk

import au.kinde.sdk.api.model.CreateUserRequest
import au.kinde.sdk.api.model.CreateUserRequestIdentitiesInner
import au.kinde.sdk.api.model.CreateUserRequestIdentitiesInnerDetails
import au.kinde.sdk.api.model.CreateUserRequestProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** getUsers() and createUser(): the two Management API calls the SDK exposes. */
class ManagementApiTest : SdkTestBase() {

    private fun signedIn() = seedSession().also { facade() }

    @Test
    fun `getUsers sends its query parameters with the user's token`() {
        val issued = signedIn()

        onBackground { client().getUsers(sort = "name_asc", pageSize = 5, userId = 7, nextToken = "abc") }

        val request = kinde.requests.last { it.requestUrl?.encodedPath == "/api/v1/users" }
        assertEquals("name_asc", request.requestUrl?.queryParameter("sort"))
        assertEquals("5", request.requestUrl?.queryParameter("page_size"))
        assertEquals("7", request.requestUrl?.queryParameter("user_id"))
        assertEquals("abc", request.requestUrl?.queryParameter("next_token"))
        assertEquals("Bearer ${issued.access}", request.getHeader("Authorization"))
    }

    @Test
    @KnownIssue("getUsers() expects a JSON array of users with Int ids; Kinde returns {code, message, users:[...], next_token} with kp_ string ids, so it always returns null")
    fun `getUsers parses Kinde's users response`() {
        signedIn()

        val users = onBackground { client().getUsers(pageSize = 1) }

        assertEquals("exceptions: ${listener.exceptions.map { it.javaClass.simpleName }}", 1, users?.size)
    }

    @Test
    fun `createUser posts the profile and identities`() {
        signedIn()

        val created = onBackground {
            client().createUser(CreateUserRequest(
                profile = CreateUserRequestProfile(givenName = "Ada", familyName = "Lovelace"),
                identities = listOf(CreateUserRequestIdentitiesInner("email", CreateUserRequestIdentitiesInnerDetails("ada@example.com"))),
            ))
        }

        assertNotNull(created)
        assertEquals("kp_new_user", created?.id)
        assertEquals(true, created?.created)
        assertEquals(1234, created?.identities?.single()?.result?.identityId)
        val body = JSONObject(kinde.requests.last { it.requestUrl?.encodedPath == "/api/v1/user" }.body.readUtf8())
        assertEquals("Ada", body.getJSONObject("profile").getString("given_name"))
        assertEquals("ada@example.com", body.getJSONArray("identities").getJSONObject(0).getJSONObject("details").getString("email"))
        assertTrue(kinde.requests.last { it.requestUrl?.encodedPath == "/api/v1/user" }.method == "POST")
    }
}
