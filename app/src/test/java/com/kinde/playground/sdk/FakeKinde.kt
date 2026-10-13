package com.kinde.playground.sdk

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake Kinde business over real HTTPS on localhost.
 *
 * The SDK hard-codes https://<domain>/, so the server presents a certificate for
 * "localhost" from a test CA that [TestTls] adds to the JVM trust store, and the
 * SDK is configured with the domain "localhost:<port>" through manifest
 * meta-data (the manifest path accepts a port; KindeConfig does not).
 *
 * It serves the JWKS, the token endpoint (authorization_code and refresh_token
 * grants, rotating refresh tokens), the OAuth user profile endpoints, the
 * Account API and the two Management endpoints the SDK calls. Every request is
 * recorded for assertions, and any route can be overridden per test.
 */
class FakeKinde : AutoCloseable {

    class SigningKey(val kid: String, val pair: KeyPair = rsa())

    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val counter = AtomicInteger(0)

    /** Keys served at /.well-known/jwks.json, in order. Tokens are signed with [signer]. */
    val jwks = CopyOnWriteArrayList(listOf(SigningKey("key-1")))
    var signer: SigningKey = jwks[0]

    /** Access tokens the resource endpoints accept. Anything else gets [rejectStatus]. */
    val validAccessTokens: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    var rejectStatus = 403

    /** Refresh tokens the token endpoint accepts. */
    val validRefreshTokens: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    var clientId = "playground_test_client"
    var accessTokenLifetimeSec = 3600L
    var orgCode = "org_test"
    var permissions = listOf("read:reports", "write:reports")
    var roles = listOf("admin")
    /** feature_flags claim: code -> (type letter, value). */
    var tokenFlags: Map<String, Pair<String, Any>> = mapOf(
        "beta_dashboard" to ("b" to true),
        "theme" to ("s" to "dark"),
        "max_items" to ("i" to 25),
    )
    /** Account API flags: code -> (type name, value). */
    var apiFlags: Map<String, Pair<String, Any>> = mapOf(
        "beta_dashboard" to ("Boolean" to true),
        "theme" to ("String" to "dark"),
        "max_items" to ("Integer" to 25),
    )

    /** Per-path overrides, checked before the defaults. Key is the path without query. */
    val overrides = java.util.concurrent.ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    val port: Int get() = server.port
    val domain: String get() = "localhost:$port"
    val base: String get() = "https://$domain"

    init {
        TestTls.install()
        server.useHttps(TestTls.serverCertificates.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
                overrides[path]?.let { return it(request) }
                return route(path, request)
            }
        }
        server.start()
    }

    override fun close() = runCatching { server.shutdown() }.let { }

    // -- Tokens ------------------------------------------------------------------------

    fun claims(sub: String = "kp_test_user", extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val now = System.currentTimeMillis() / 1000
        return mapOf(
            "iss" to base, "sub" to sub, "aud" to listOf<String>(), "azp" to clientId,
            "iat" to now, "exp" to now + accessTokenLifetimeSec, "jti" to "jti-${counter.incrementAndGet()}",
            // Kinde's access tokens list roles as {id, key, name} objects.
            "org_code" to orgCode, "permissions" to permissions,
            "roles" to roles.mapIndexed { i, k -> mapOf("id" to "role_$i", "key" to k, "name" to k) },
            "feature_flags" to tokenFlags.mapValues { (_, v) -> mapOf("t" to v.first, "v" to v.second) },
            "scp" to listOf("openid", "offline", "email", "profile"),
        ) + extra
    }

    fun idClaims(sub: String = "kp_test_user"): Map<String, Any?> {
        val now = System.currentTimeMillis() / 1000
        return mapOf(
            "iss" to base, "sub" to sub, "aud" to listOf(clientId), "iat" to now, "exp" to now + 3600,
            "email" to "tester@example.com", "given_name" to "Test", "family_name" to "User",
            "picture" to "https://example.com/p.png", "org_codes" to listOf(orgCode, "org_other"),
        )
    }

    fun mint(claims: Map<String, Any?>, key: SigningKey = signer): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = JSONObject(mapOf("alg" to "RS256", "typ" to "JWT", "kid" to key.kid)).toString()
        val payload = toJson(claims).toString()
        val signingInput = enc.encodeToString(header.toByteArray()) + "." + enc.encodeToString(payload.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(key.pair.private)
            update(signingInput.toByteArray())
        }.sign()
        return signingInput + "." + enc.encodeToString(sig)
    }

    data class Issued(val access: String, val id: String, val refresh: String)

    fun issue(accessClaims: Map<String, Any?> = claims(), key: SigningKey = signer): Issued {
        val issued = Issued(mint(accessClaims, key), mint(idClaims(), key), "rt_${counter.incrementAndGet()}_${System.nanoTime()}")
        validAccessTokens += issued.access
        validRefreshTokens += issued.refresh
        return issued
    }

    fun tokenJson(issued: Issued): String = JSONObject(mapOf(
        "access_token" to issued.access, "id_token" to issued.id, "refresh_token" to issued.refresh,
        "token_type" to "bearer", "expires_in" to accessTokenLifetimeSec, "scope" to "openid offline email profile",
    )).toString()

    fun jwksJson(keys: List<SigningKey> = jwks): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        fun b64(i: BigInteger) = enc.encodeToString(i.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it })
        return JSONObject(mapOf("keys" to keys.map { k ->
            val pub = k.pair.public as RSAPublicKey
            mapOf("kty" to "RSA", "alg" to "RS256", "use" to "sig", "kid" to k.kid, "e" to b64(pub.publicExponent), "n" to b64(pub.modulus))
        })).toString()
    }

    // -- Requests -------------------------------------------------------------------------

    fun paths(): List<String> = requests.map { it.requestUrl?.encodedPath ?: it.path.orEmpty() }
    fun count(path: String) = paths().count { it == path }
    fun tokenRequests() = requests.filter { it.requestUrl?.encodedPath == "/oauth2/token" }

    fun form(request: RecordedRequest): Map<String, String> =
        request.body.clone().readUtf8().split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    // -- Default routes ---------------------------------------------------------------------

    private fun json(body: Any, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun authorized(request: RecordedRequest): Boolean {
        val header = request.getHeader("Authorization") ?: return false
        return header.startsWith("Bearer ") && header.removePrefix("Bearer ") in validAccessTokens
    }

    private fun route(path: String, request: RecordedRequest): MockResponse = when (path) {
        "/.well-known/jwks.json" -> json(jwksJson())
        "/oauth2/token" -> tokenEndpoint(request)
        else -> if (!authorized(request)) {
            // Kinde's real body for a missing/invalid token on the Account API.
            json(JSONArray(listOf(JSONObject(mapOf("Code" to "TOKEN_ERROR", "Message" to "Missing or invalid token")))), rejectStatus)
        } else resource(path, request)
    }

    private fun tokenEndpoint(request: RecordedRequest): MockResponse {
        val form = form(request)
        return when (form["grant_type"]) {
            "authorization_code" -> json(tokenJson(issue()))
            "refresh_token" -> {
                val presented = form["refresh_token"]
                if (presented == null || !validRefreshTokens.remove(presented)) {
                    json(JSONObject(mapOf("error" to "invalid_grant", "error_description" to "The refresh token is invalid.")), 400)
                } else json(tokenJson(issue()))
            }
            else -> json(JSONObject(mapOf("error" to "unsupported_grant_type")), 400)
        }
    }

    private fun resource(path: String, request: RecordedRequest): MockResponse = when (path) {
        "/oauth2/user_profile" -> json(JSONObject(mapOf(
            "id" to "kp_test_user", "preferred_email" to "tester@example.com", "provided_id" to null,
            "last_name" to "User", "first_name" to "Test", "picture" to "https://example.com/p.png",
        )))
        "/oauth2/v2/user_profile" -> json(JSONObject(mapOf(
            "id" to "kp_test_user", "sub" to "kp_test_user", "provided_id" to null, "name" to "Test User",
            "given_name" to "Test", "family_name" to "User", "updated_at" to 1700000000,
            "email" to "tester@example.com", "email_verified" to true, "picture" to "https://example.com/p.png",
        )))
        "/account_api/v1/permissions" -> json(JSONObject(mapOf("success" to true, "data" to mapOf(
            "org_code" to orgCode,
            "permissions" to permissions.mapIndexed { i, k -> mapOf("id" to "perm_$i", "key" to k, "name" to k) },
        ), "metadata" to mapOf("has_more" to false))))
        "/account_api/v1/roles" -> json(JSONObject(mapOf("success" to true, "data" to mapOf(
            "org_code" to orgCode,
            "roles" to roles.mapIndexed { i, k -> mapOf("id" to "role_$i", "key" to k, "name" to k) },
        ), "metadata" to mapOf("has_more" to false))))
        "/account_api/v1/feature_flags" -> json(JSONObject(mapOf("success" to true, "data" to mapOf(
            "feature_flags" to apiFlags.entries.mapIndexed { i, (k, v) -> mapOf("id" to "flag_$i", "key" to k, "name" to k, "type" to v.first, "value" to v.second) },
        ))))
        "/account_api/v1/entitlements" -> json(JSONObject(mapOf(
            "data" to mapOf(
                "org_code" to orgCode,
                "plans" to listOf(mapOf("key" to "pro", "name" to "Pro", "subscribed_on" to "2026-01-01T00:00:00Z")),
                "entitlements" to listOf(entitlement("pro_reports"), entitlement("seats", max = 10)),
            ),
            "metadata" to mapOf("has_more" to false, "next_page_starting_after" to null),
        )))
        "/account_api/v1/entitlement" -> json(JSONObject(mapOf(
            "data" to mapOf("org_code" to orgCode, "entitlement" to entitlement(request.requestUrl?.queryParameter("key") ?: "unknown")),
            "metadata" to mapOf<String, Any>(),
        )))
        // Kinde's real Management API shape (users_response).
        "/api/v1/users" -> json(JSONObject(mapOf(
            "code" to "OK", "message" to "Success",
            "users" to listOf(mapOf("id" to "kp_0a1b2c", "email" to "tester@example.com", "full_name" to "Test User",
                "first_name" to "Test", "last_name" to "User", "is_suspended" to false)),
            "next_token" to "next_abc",
        )))
        "/api/v1/user" -> json(JSONObject(mapOf(
            "id" to "kp_new_user", "created" to true,
            "identities" to listOf(mapOf("type" to "email", "result" to mapOf("created" to true, "identity_id" to 1234))),
        )))
        else -> MockResponse().setResponseCode(404)
    }

    private fun entitlement(key: String, max: Int = 1) = mapOf(
        "id" to "ent_$key", "fixed_charge" to 1500, "price_name" to "Pro", "unit_amount" to null,
        "feature_key" to key, "feature_name" to key.replace('_', ' '), "entitlement_limit_max" to max, "entitlement_limit_min" to null,
    )

    companion object {
        fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun toJson(value: Any?): Any? = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
            is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            else -> value
        }
    }
}

/** A test CA trusted by the JVM's default trust store, plus the localhost certificate it signs. */
object TestTls {
    private val root: HeldCertificate = HeldCertificate.Builder().certificateAuthority(0).commonName("Playground Test CA").build()
    private val localhost: HeldCertificate = HeldCertificate.Builder()
        .commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
        .signedBy(root).build()

    val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(localhost, root.certificate).build()

    @Volatile
    private var installed = false

    /**
     * OkHttp builds its trust manager from TrustManagerFactory.init(null), which
     * reads javax.net.ssl.trustStore. Point that at the JDK's CAs plus ours, so
     * the SDK's own (unmodified) OkHttp client trusts the fake server and real
     * hosts still work for the live tests.
     */
    @Synchronized
    fun install() {
        if (installed) return
        val password = "playground".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, password) }
        val javaHome = System.getProperty("java.home")
        listOf("$javaHome/lib/security/cacerts", "$javaHome/jre/lib/security/cacerts").map(::File).firstOrNull { it.isFile }?.let { cacerts ->
            val jdk = KeyStore.getInstance(KeyStore.getDefaultType()).apply { cacerts.inputStream().use { load(it, "changeit".toCharArray()) } }
            jdk.aliases().toList().forEach { alias -> jdk.getCertificate(alias)?.let { store.setCertificateEntry("jdk-$alias", it) } }
        }
        store.setCertificateEntry("playground-test-ca", root.certificate)
        val file = File.createTempFile("playground-trust", ".p12").apply { deleteOnExit() }
        file.outputStream().use { store.store(it, password) }
        System.setProperty("javax.net.ssl.trustStore", file.absolutePath)
        System.setProperty("javax.net.ssl.trustStorePassword", String(password))
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
        installed = true
    }
}
