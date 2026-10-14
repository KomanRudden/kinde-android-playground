package com.kinde.playground.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import au.kinde.sdk.KindeSDK
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import com.kinde.playground.core.TokenWatcher
import com.kinde.playground.ui.theme.PlaygroundTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Null when .env isn't filled in yet: the app still runs and says what's missing. */
    var sdk: KindeSDK? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = KindeHub.config

        // Must be constructed in onCreate: the facade registers its browser result
        // launchers, which is only allowed before the activity is STARTED. A new
        // facade per activity instance (including after rotation) is the intended
        // pattern; they all share the process-wide KindeClient.
        if (config.isConfigured && KindeHub.startupError == null) {
            sdk = try {
                KindeSDK(
                    activity = this,
                    loginRedirect = config.loginRedirect,
                    logoutRedirect = config.logoutRedirect,
                    scopes = config.scopes,
                    sdkListener = KindeHub.listener,
                )
            } catch (t: Throwable) {
                KindeHub.note("KindeSDK could not be created: ${Redact.error(t)}")
                null
            }
        }
        // The SDK sends no callback for a stored session it can't verify (expired with
        // no refresh token, unknown signing key), so set the badge from isAuthenticated() too.
        KindeHub.refresh(this)
        LifecycleStats.onActivityCreated(this)

        // Notice token changes the SDK makes without telling the listener.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { TokenWatcher.watch(applicationContext) }
        }

        val start = Page.from(intent.getStringExtra(EXTRA_PAGE))
        setContent {
            PlaygroundTheme {
                App(sdk = sdk, activity = this, startPage = start)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: an invitation link that arrives while the app is open lands
        // here, not in a new KindeSDK constructor, so hand it over explicitly.
        val code = intent.data?.getQueryParameter("invitation_code")
        if (!code.isNullOrBlank()) {
            KindeHub.note("Invitation link received while running; calling handleInvitation")
            sdk?.handleInvitation(code)
        }
    }

    override fun onDestroy() {
        LifecycleStats.onActivityDestroyed(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}
