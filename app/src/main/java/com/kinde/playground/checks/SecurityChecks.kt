package com.kinde.playground.checks

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Base64
import au.kinde.sdk.model.TokenType
import au.kinde.sdk.store.Store
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.Jwt
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import com.kinde.playground.core.SdkLog
import net.openid.appauth.AuthState
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security self-audit of the integration on this device: what the SDK stores
 * and how, what reaches logcat, how the last sign-in was protected, and whether
 * a forged redirect is accepted. Run off the main thread.
 */
object SecurityChecks {

    fun run(context: Context, config: PlaygroundConfig = KindeHub.config, probes: Boolean = true): CheckRun {
        val started = System.currentTimeMillis()
        val checks = mutableListOf<Check>()
        checks += postureChecks(context, config)
        if (config.isConfigured && KindeHub.startupError == null) {
            checks += storageChecks(context, config)
            checks += signInChecks(context, config)
            checks += sessionChecks(context)
            if (probes) checks += forgedRedirectProbe(context, config)
        }
        checks += logcatCheck(context)
        return CheckRun("Security", checks, System.currentTimeMillis() - started)
    }

    // -- Posture -------------------------------------------------------------------

    private fun postureChecks(context: Context, config: PlaygroundConfig): List<Check> {
        val g = "Posture"
        val flags = context.applicationInfo.flags
        val backup = flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        val cleartext = flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        val debuggable = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return listOf(
            Check(g, "Backups exclude the token store", "Reads android:allowBackup for this app.",
                if (backup) Outcome.FAILED else Outcome.PASSED, Judge.NO_HTTP,
                if (backup) "Backups are on, so the SDK's shared_prefs/app_prefs_secure_*.xml can leave the device. Set allowBackup=false or exclude it."
                else "allowBackup=false and data-extraction rules exclude shared prefs. The SDK doesn't do this for you: every app must."),
            Check(g, "Cleartext traffic", "Reads usesCleartextTraffic for this app.",
                if (cleartext) Outcome.WARN else Outcome.PASSED, Judge.NO_HTTP,
                if (cleartext) "Cleartext HTTP is allowed." else "Cleartext HTTP is blocked. The SDK only uses https:// URLs."),
            Check(g, "Token reveal", "Reads PLAYGROUND_DEBUG.", if (config.debug) Outcome.WARN else Outcome.PASSED, Judge.NO_HTTP,
                if (config.debug) "Reveal buttons are enabled on the Tokens page." else "Tokens are only ever shown masked."),
            Check(g, "Management writes", "Reads PLAYGROUND_ALLOW_MUTATIONS.", if (config.allowMutations) Outcome.WARN else Outcome.PASSED, Judge.NO_HTTP,
                if (config.allowMutations) "createUser() is enabled on the Management page." else "Read-only."),
            Check(g, "Debuggable build", "Reads FLAG_DEBUGGABLE.", Outcome.INFO, Judge.NO_HTTP,
                if (debuggable) "Debuggable: `adb shell run-as` can read this app's files, which is how the storage checks below would look to an attacker with a backup or a rooted device."
                else "Not debuggable."),
        )
    }

    // -- Storage --------------------------------------------------------------------

    fun prefsFile(context: Context, domain: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.applicationInfo.dataDir, "shared_prefs/app_prefs_secure_$hash.xml")
    }

    /**
     * Decrypts a value the way anyone holding the prefs file could: the SDK's AES
     * key is the domain name padded to 16 bytes, and the IV sits next to it.
     */
    fun decryptWithDomainOnly(domain: String, ivB64: String, cipherB64: String): String? = runCatching {
        val key = ByteArray(16) { i -> domain.getOrNull(i)?.code?.toByte() ?: Byte.MIN_VALUE }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun prefValue(xml: String, name: String): String? =
        Regex("<string name=\"${Regex.escape(name)}\">([^<]*)</string>").find(xml)?.groupValues?.get(1)

    private fun storageChecks(context: Context, config: PlaygroundConfig): List<Check> {
        val g = "Token storage"
        val file = prefsFile(context, config.domain)
        if (!file.isFile) {
            return listOf(Check(g, "Token store", "Looks for the SDK's preferences file.", Outcome.INFO, Judge.NO_HTTP,
                "${file.name} doesn't exist yet. It appears after the SDK saves keys or a session."))
        }
        val xml = file.readText()
        val secrets = KindeHub.knownSecrets(context)
        val plaintext = Redact.JWT.containsMatchIn(xml) || secrets.any { it.length >= 8 && it in xml }
        val checks = mutableListOf(
            Check(g, "No plaintext tokens on disk", "Reads ${file.name} as raw bytes.",
                if (plaintext) Outcome.FAILED else Outcome.PASSED, Judge.NO_HTTP,
                if (plaintext) "A token is stored in plain text." else "The file holds only ciphertext (${xml.length} bytes).")
        )
        val iv = prefValue(xml, "iv")
        val state = prefValue(xml, "auth_state")
        val keys = prefValue(xml, "keys")
        val sample = state ?: keys
        val what = if (state != null) "saved session" else "saved keys"
        when {
            sample == null -> Unit
            sample.startsWith("k1:") -> checks += Check(g, "Encryption key", "Reads how the $what is encrypted.", Outcome.PASSED, Judge.NO_HTTP,
                "AES-256-GCM with a key held in the Android Keystore and a fresh IV per value. A copy of this file can't be read off the device.")
            sample.startsWith("s1:") -> checks += Check(g, "Encryption key", "Reads how the $what is encrypted.", Outcome.WARN, Judge.NO_HTTP,
                "AES-256-GCM with a random software key: the Android Keystore wasn't available on this device. Safer than the old scheme, but the key sits in app storage.")
            iv != null -> {
                val decrypted = decryptWithDomainOnly(config.domain, iv, sample)
                val readable = decrypted != null && decrypted.trimStart().startsWith("{")
                checks += Check(g, "Encryption key", "Tries to decrypt the $what using only the public domain name.",
                    if (readable) Outcome.FAILED else Outcome.PASSED, Judge.NO_HTTP,
                    if (readable) "Decrypted ${decrypted.length} characters of JSON${if (state != null) " including the refresh token" else ""}. The SDK's AES key is the domain name, so this is obfuscation, not encryption. A Keystore-backed key would stop a copied file from being read."
                    else "Could not decrypt with the domain alone.")
                checks += Check(g, "IV reuse", "Reads the IV stored beside the ciphertext.", Outcome.WARN, Judge.NO_HTTP,
                    "Every value is encrypted with the same stored IV, so identical plaintexts give identical ciphertexts (AES-CBC with a fixed IV).")
            }
        }
        return checks
    }

    // -- How the last sign-in was protected ---------------------------------------------

    private fun signInChecks(context: Context, config: PlaygroundConfig): List<Check> {
        val g = "Last sign-in"
        val json = runCatching { Store(context, config.domain).getState() }.getOrNull()
            ?: return listOf(Check(g, "Authorization request", "Reads the saved AuthState.", Outcome.INFO, Judge.NO_HTTP,
                "No saved session. Sign in, then run this again."))
        val request = runCatching { AuthState.jsonDeserialize(json).lastAuthorizationResponse?.request }.getOrNull()
            ?: return listOf(Check(g, "Authorization request", "Reads the saved AuthState.", Outcome.INFO, Judge.NO_HTTP,
                "The saved session has no authorization request (restored from an older version?)."))
        return listOf(
            Check(g, "PKCE", "Looks for a code challenge on the last authorization request.",
                if (request.codeVerifier != null) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP,
                if (request.codeVerifier != null) "PKCE (${request.codeVerifierChallengeMethod}) protected the authorization code."
                else "No PKCE. The SDK only adds a code challenge for login(type = GrantType.PKCE); the default login() sends none, so an intercepted code can be redeemed. RFC 8252 requires PKCE for native apps."),
            Check(g, "Nonce", "Looks for a nonce on the last authorization request.",
                if (request.nonce != null) Outcome.PASSED else Outcome.WARN, Judge.NO_HTTP,
                if (request.nonce != null) "A nonce bound the ID token to this request."
                else "No nonce: the SDK calls setNonce(null), and it doesn't validate the ID token itself, so ID token replay isn't detected."),
            Check(g, "State", "Looks for the state parameter.",
                if (request.state != null) Outcome.PASSED else Outcome.FAILED, Judge.NO_HTTP,
                if (request.state != null) "AppAuth generated a state value and checks it on the redirect." else "No state value."),
            Check(g, "Scopes requested", "Reads the scopes on the last request.", Outcome.INFO, Judge.NO_HTTP,
                "${request.scope}. Audience: ${request.additionalParameters["audience"] ?: "none"}."),
        )
    }

    // -- Session -----------------------------------------------------------------------------

    private fun sessionChecks(context: Context): List<Check> {
        val g = "Session"
        val client = KindeHub.clientOrNull(context) ?: return emptyList()
        val authed = client.isAuthenticated()
        val access = Jwt.decode(client.getToken(TokenType.ACCESS_TOKEN))
        val left = access?.secondsLeft()
        val checks = mutableListOf<Check>()
        checks += when {
            !authed -> Check(g, "isAuthenticated() and expiry", "Compares isAuthenticated() with the token's exp.", Outcome.INFO, Judge.NO_HTTP_TOKEN, "Signed out.")
            left != null && left <= 0 -> Check(g, "isAuthenticated() and expiry", "Compares isAuthenticated() with the token's exp.", Outcome.FAILED, Judge.NO_HTTP_TOKEN,
                "isAuthenticated() is true but the access token expired ${-left}s ago. The SDK checks the signature only, not exp, iss or aud.")
            else -> Check(g, "isAuthenticated() and expiry", "Compares isAuthenticated() with the token's exp.", Outcome.INFO, Judge.NO_HTTP_TOKEN,
                "Signed in, token valid for ${left}s. Note: isAuthenticated() verifies the signature only; it would stay true after exp until a refresh fails.")
        }
        return checks
    }

    // -- Forged redirect -----------------------------------------------------------------------

    private fun forgedRedirectProbe(context: Context, config: PlaygroundConfig): Check {
        val g = "Probes"
        val client = KindeHub.clientOrNull(context) ?: return Judge.skipped(g, "Forged redirect", "", "No client.")
        val before = client.getToken(TokenType.ACCESS_TOKEN)
        val lastEvent = KindeHub.events.value.firstOrNull()?.id ?: -1
        val forged = "${config.loginRedirect}?code=forged-${System.nanoTime()}&state=forged"
        val since = System.currentTimeMillis()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(forged)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2500)
        val after = client.getToken(TokenType.ACCESS_TOKEN)
        val newTokens = KindeHub.events.value.count { it.id > lastEvent && it.type == KindeHub.EventType.NEW_TOKEN }
        val tokenCalls = SdkLog.httpCalls(SdkLog.read(since, listOf("ApiClient"))).filter { it.url.endsWith("/oauth2/token") }
        val accepted = after != before || newTokens > 0 || tokenCalls.isNotEmpty()
        return Check(g, "Forged redirect", "Opens ${Redact.text(forged)} as another app could, without starting a sign-in.",
            if (accepted) Outcome.FAILED else Outcome.PASSED,
            if (tokenCalls.isEmpty()) "Nothing is sent to Kinde: no token exchange was attempted." else "The SDK sent ${tokenCalls.size} token request(s).",
            if (accepted) "The forged code changed the session." else "Ignored: AppAuth only accepts a redirect for a request this app started, with a matching state.")
    }

    // -- Logcat --------------------------------------------------------------------------------

    private fun logcatCheck(context: Context): Check {
        val lines = SdkLog.read(0, tags = null)
        val secrets = KindeHub.knownSecrets(context)
        val hits = lines.filter { l -> Redact.JWT.containsMatchIn(l.message) || secrets.any { it.length >= 8 && it in l.message } }
        val tags = hits.map { it.tag }.distinct()
        return Check("Logs", "No tokens in logcat", "Scans every log line this process wrote (${lines.size} lines) for JWTs and the current tokens.",
            when {
                lines.isEmpty() -> Outcome.SKIPPED
                hits.isEmpty() -> Outcome.PASSED
                else -> Outcome.FAILED
            },
            Judge.NO_HTTP,
            when {
                lines.isEmpty() -> "Logcat couldn't be read on this device."
                hits.isEmpty() -> "None found. The SDK's debug HTTP log prints URLs and status codes only."
                else -> "${hits.size} line(s) contain a token, from: $tags."
            })
    }

    /** For tests and the regression page: does a decrypted session JSON contain a refresh token? */
    fun hasRefreshToken(json: String): Boolean = runCatching { JSONObject(json).has("refreshToken") }.getOrDefault(false)
}
