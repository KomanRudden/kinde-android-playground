package com.kinde.playground.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import au.kinde.sdk.KindeClient
import au.kinde.sdk.KindeSDK
import au.kinde.sdk.SDKListener
import au.kinde.sdk.model.TokenType
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import com.kinde.playground.ui.components.ActionButton
import com.kinde.playground.ui.components.ButtonRow
import com.kinde.playground.ui.components.KeyValue
import com.kinde.playground.ui.components.SectionCard
import com.kinde.playground.ui.theme.PlaygroundTheme

/**
 * A second screen with its own KindeSDK facade and its own listener. Shows that
 * both facades share one KindeClient: same tokens, both hear session events,
 * and logging out here signs out everywhere.
 */
class SecondActivity : ComponentActivity() {
    private var sdk: KindeSDK? = null
    private val heard = mutableStateListOf<String>()

    private val ownListener = object : SDKListener {
        override fun onNewToken(token: String) { heard.add(0, "onNewToken ${Redact.mask(token)}") }
        override fun onLogout() { heard.add(0, "onLogout") }
        override fun onException(exception: Exception) { heard.add(0, "onException ${Redact.error(exception)}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = KindeHub.config
        if (KindeHub.clientOrNull(this) != null) {
            sdk = KindeSDK(this, config.loginRedirect, config.logoutRedirect, config.scopes, ownListener)
        }
        LifecycleStats.onActivityCreated(this)
        setContent {
            PlaygroundTheme {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).testTag("second"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Second activity", style = MaterialTheme.typography.headlineSmall)
                    SectionCard("This facade", "A separate KindeSDK and SDKListener, created in this activity's onCreate.") {
                        val client = KindeHub.clientOrNull(this@SecondActivity)
                        KeyValue("KindeClient", client?.let { "#" + System.identityHashCode(KindeClient.getInstance(this@SecondActivity)).toString(16) } ?: "—", mono = true)
                        KeyValue("Same token as main", (sdk?.getToken(TokenType.ACCESS_TOKEN) == KindeHub.accessToken(this@SecondActivity)).toString(), mono = true)
                        KeyValue("isAuthenticated()", (sdk?.isAuthenticated() ?: false).toString(), mono = true)
                        ButtonRow {
                            ActionButton("logout() from here", { sdk?.logout() }, enabled = sdk != null, secondary = true)
                            ActionButton("Close", { finish() })
                        }
                    }
                    SectionCard("This listener heard", "On construction a facade replays the current state: a token if signed in, onLogout if not.") {
                        if (heard.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                        heard.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        LifecycleStats.onActivityDestroyed(this)
        super.onDestroy()
    }
}
