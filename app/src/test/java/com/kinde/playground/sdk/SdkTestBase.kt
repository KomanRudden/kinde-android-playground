package com.kinde.playground.sdk

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import au.kinde.sdk.KindeClient
import au.kinde.sdk.KindeSDK
import au.kinde.sdk.SDKListener
import au.kinde.sdk.store.Store
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Records every SDKListener callback, with the thread it arrived on. */
class RecordingListener : SDKListener {
    data class Token(val token: String, val onMain: Boolean)

    val tokens = CopyOnWriteArrayList<Token>()
    val exceptions = CopyOnWriteArrayList<Exception>()
    private val logouts = AtomicInteger()
    val logoutCount get() = logouts.get()

    override fun onNewToken(token: String) {
        tokens += Token(token, Looper.myLooper() == Looper.getMainLooper())
    }

    override fun onLogout() {
        logouts.incrementAndGet()
    }

    override fun onException(exception: Exception) {
        exceptions += exception
    }

    fun exceptionNames() = exceptions.map { it.javaClass.simpleName }
}

/**
 * Base for the SDK contract suite: the real, unmodified SDK against [FakeKinde].
 *
 * Uses the public API only (plus reflection to reset the process singleton
 * between tests), so the same tests run against a source checkout or a
 * published AAR (-PkindeSdkVersion=...).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
abstract class SdkTestBase {

    @get:Rule
    val knownIssues = KnownIssueRule()

    protected val context: Application = ApplicationProvider.getApplicationContext()
    protected lateinit var kinde: FakeKinde
    protected val listener = RecordingListener()

    @Before
    fun startFakeKinde() {
        resetKindeClient()
        kinde = FakeKinde()
        installMetaData(kinde.domain, kinde.clientId)
    }

    @After
    fun stopFakeKinde() {
        background()
        kinde.close()
        resetKindeClient()
    }

    // -- App foreground state --------------------------------------------------------------------

    private val processLifecycle get() = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry

    /**
     * Moves the process to the foreground, which starts the SDK's refresh schedule.
     * Robolectric doesn't drive ProcessLifecycleOwner, so tests do it explicitly.
     */
    protected fun foreground() {
        processLifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun background() {
        if (processLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            processLifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // -- Singleton and configuration ---------------------------------------------------------

    protected fun resetKindeClient() {
        KindeClient::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    protected fun installMetaData(domain: String?, clientId: String?, audience: String? = null) {
        val bundle = Bundle().apply {
            domain?.let { putString("au.kinde.domain", it) }
            clientId?.let { putString("au.kinde.clientId", it) }
            audience?.let { putString("au.kinde.audience", it) }
        }
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData = bundle
    }

    protected fun client(): KindeClient = KindeClient.getInstance(context)

    protected fun store() = Store(context, kinde.domain)

    // -- Sessions -------------------------------------------------------------------------------

    protected fun serviceConfig() = AuthorizationServiceConfiguration(
        Uri.parse("${kinde.base}/oauth2/auth"), Uri.parse("${kinde.base}/oauth2/token"), null, Uri.parse("${kinde.base}/logout")
    )

    /**
     * Writes a signed-in session the way the SDK persists one, before the client
     * starts: an AuthState with tokens, and the JWKS used to verify them.
     */
    protected fun seedSession(
        issued: FakeKinde.Issued = kinde.issue(),
        keysJson: String? = kinde.jwksJson(),
        refreshToken: String? = issued.refresh,
        clientId: String = kinde.clientId,
    ): FakeKinde.Issued {
        val request = AuthorizationRequest.Builder(serviceConfig(), clientId, ResponseTypeValues.CODE, Uri.parse(LOGIN_REDIRECT))
            .setScopes("openid", "offline", "email", "profile").build()
        val response = AuthorizationResponse.Builder(request).setAuthorizationCode("seeded_code").setState(request.state).build()
        val state = AuthState(response, null)
        state.update(
            TokenResponse.Builder(response.createTokenExchangeRequest())
                .setTokenType("Bearer").setAccessToken(issued.access).setIdToken(issued.id).setRefreshToken(refreshToken)
                .setAccessTokenExpirationTime(System.currentTimeMillis() + kinde.accessTokenLifetimeSec * 1000)
                .build(),
            null,
        )
        store().saveState(state.jsonSerializeString())
        keysJson?.let { store().saveKeys(it) }
        return issued
    }

    // -- Facades and the browser ------------------------------------------------------------------

    class Facade(val controller: ActivityController<ComponentActivity>, val sdk: KindeSDK) {
        val activity: ComponentActivity get() = controller.get()
    }

    protected fun facade(
        sdkListener: SDKListener = listener,
        scopes: List<String>? = null,
        launchIntent: Intent? = null,
        resume: Boolean = true,
    ): Facade {
        val controller = if (launchIntent != null) Robolectric.buildActivity(ComponentActivity::class.java, launchIntent)
        else Robolectric.buildActivity(ComponentActivity::class.java)
        controller.create()
        val sdk = if (scopes != null) KindeSDK(controller.get(), LOGIN_REDIRECT, LOGOUT_REDIRECT, scopes, sdkListener)
        else KindeSDK(controller.get(), LOGIN_REDIRECT, LOGOUT_REDIRECT, sdkListener = sdkListener)
        if (resume) controller.start().resume()
        return Facade(controller, sdk)
    }

    /** AppAuth refuses to build an authorization intent without a browser. Robolectric has none. */
    protected fun installFakeBrowser() {
        val pm = shadowOf(context.packageManager)
        val pkg = "com.android.chrome"
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            versionName = "130.0"
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature("3082010a0282010100"))
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        val component = ComponentName(pkg, "$pkg.BrowserActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("http")
            addDataScheme("https")
        })
    }

    /** The intent the facade started for the browser round trip, waiting for async launches (logout). */
    protected fun awaitStartedForResult(activity: Activity, timeoutMs: Long = 5_000): Intent? {
        var found: Intent? = null
        awaitCondition(timeoutMs) {
            shadowOf(activity).nextStartedActivityForResult?.let {
                requestCodes[it.intent] = it.requestCode
                found = it.intent
            }
            found != null
        }
        return found
    }

    private val requestCodes = java.util.IdentityHashMap<Intent, Int>()

    /** Hands a browser result back to the launcher the facade registered for [started]. */
    protected fun deliverResult(facade: Facade, started: Intent, resultCode: Int, data: Intent?) {
        val code = requireNotNull(requestCodes[started]) { "intent was not captured with awaitStartedForResult" }
        facade.activity.activityResultRegistry.dispatchResult(code, resultCode, data)
    }

    @Suppress("DEPRECATION")
    protected fun browserUrl(started: Intent): Uri = started.getParcelableExtra<Intent>("authIntent")!!.data!!

    protected fun authRequest(started: Intent): AuthorizationRequest =
        AuthorizationRequest.jsonDeserialize(started.getStringExtra("authRequest")!!)

    protected fun endSessionRequest(started: Intent): EndSessionRequest =
        EndSessionRequest.jsonDeserialize(started.getStringExtra("authRequest")!!)

    /** Completes the browser step: Kinde redirected back with an authorization code. */
    protected fun deliverAuthorizationCode(facade: Facade, started: Intent, code: String = "code_from_kinde") {
        val request = authRequest(started)
        kinde.pendingNonce = request.nonce
        val data = AuthorizationResponse.Builder(request).setAuthorizationCode(code).setState(request.state).build().toIntent()
        deliverResult(facade, started, Activity.RESULT_OK, data)
    }

    // -- Waiting ----------------------------------------------------------------------------------------

    /** Idles the main looper (where Retrofit callbacks and the SDK's handler run) until [condition]. */
    protected fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    protected fun idle(ms: Long = 300) {
        awaitCondition(ms) { false }
    }

    /** Runs [block] off the main thread, as apps must for network calls. */
    protected fun <T> onBackground(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread { result = runCatching(block) }
        t.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (t.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            t.join(20)
        }
        return result!!.getOrThrow()
    }

    companion object {
        const val LOGIN_REDIRECT = "com.kinde.playground://kinde_callback"
        const val LOGOUT_REDIRECT = "com.kinde.playground://kinde_logout"
    }
}
