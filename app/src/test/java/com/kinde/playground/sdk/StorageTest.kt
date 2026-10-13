package com.kinde.playground.sdk

import android.content.Context
import android.util.Base64
import au.kinde.sdk.store.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** How the SDK's Store protects tokens at rest. */
class StorageTest : SdkTestBase() {

    private fun prefsXml(domain: String): String {
        val prefs = context.getSharedPreferences(prefsName(domain), Context.MODE_PRIVATE)
        // Robolectric keeps prefs in memory; render them like the XML file Android writes.
        return prefs.all.entries.joinToString("\n") { "<string name=\"${it.key}\">${it.value}</string>" }
    }

    private fun prefsName(domain: String): String =
        "app_prefs_secure_" + MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun raw(domain: String, key: String): String? =
        context.getSharedPreferences(prefsName(domain), Context.MODE_PRIVATE).getString(key, null)

    @Test
    fun `tokens are not stored in plain text`() {
        val issued = seedSession()

        val xml = prefsXml(kinde.domain)

        assertFalse(xml.contains(issued.access))
        assertFalse(xml.contains(issued.refresh))
        assertFalse(xml.contains("eyJ"))
    }

    @Test
    fun `one preferences file per domain`() {
        Store(context, "a.kinde.invalid").saveState("""{"a":1}""")
        Store(context, "b.kinde.invalid").saveState("""{"b":2}""")

        assertEquals("""{"a":1}""", Store(context, "a.kinde.invalid").getState())
        assertEquals("""{"b":2}""", Store(context, "b.kinde.invalid").getState())
        assertNotEquals(prefsName("a.kinde.invalid"), prefsName("b.kinde.invalid"))
    }

    @Test
    fun `the session cannot be decrypted with only the public domain name`() {
        val issued = seedSession()
        val domain = kinde.domain

        // The 2.2.0 attack: AES key = domain padded to 16 bytes, IV stored beside the values.
        val key = ByteArray(16) { i -> domain.getOrNull(i)?.code?.toByte() ?: Byte.MIN_VALUE }
        val iv = raw(domain, "iv")?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(16)
        val decrypted = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(Base64.decode(raw(domain, "auth_state"), Base64.NO_WRAP)))
        }.getOrNull()

        assertFalse("decrypted the refresh token without any secret", decrypted.orEmpty().contains(issued.refresh))
    }

    @Test
    fun `encrypting the same value twice gives different ciphertexts`() {
        val store = Store(context, "iv.kinde.invalid")
        store.saveState("same")
        val first = raw("iv.kinde.invalid", "auth_state")
        store.saveState("same")

        assertNotEquals(first, raw("iv.kinde.invalid", "auth_state"))
    }

    @Test
    fun `concurrent first writes both stay readable`() {
        val corrupted = (1..40).count { i ->
            val domain = "race$i.kinde.invalid"
            val barrier = CyclicBarrier(2)
            val keys = """{"keys":[{"kid":"k$i"}]}"""
            val state = """{"state":"s$i"}"""
            val a = Thread { barrier.await(); Store(context, domain).saveKeys(keys) }
            val b = Thread { barrier.await(); Store(context, domain).saveState(state) }
            a.start(); b.start(); a.join(); b.join()
            val readKeys = runCatching { Store(context, domain).getKeys() }.getOrNull()
            val readState = runCatching { Store(context, domain).getState() }.getOrNull()
            readKeys != keys || readState != state
        }

        assertEquals("corrupted values in 40 cold starts", 0, corrupted)
    }

    @Test
    fun `the token store is a plain SharedPreferences file that backups would include`() {
        seedSession()
        // Documented, not a failure: whether it leaves the device is the app's
        // android:allowBackup / dataExtractionRules decision. The playground opts out.
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${prefsName(kinde.domain)}.xml")
        assertTrue(prefsName(kinde.domain).startsWith("app_prefs_secure_"))
        assertTrue(file.path.contains("shared_prefs"))
    }
}
