package com.kinde.playground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kinde.playground.checks.Outcome
import com.kinde.playground.checks.SetupChecks
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.SdkLog
import com.kinde.playground.ui.LocalEnv
import com.kinde.playground.ui.components.*
import com.kinde.playground.ui.rememberCheckRunner
import com.kinde.playground.ui.theme.CodeStyle
import com.kinde.playground.ui.theme.LocalStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardPage() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val checks = rememberCheckRunner()
    LaunchedEffect(Unit) { checks.start { SetupChecks.run(context) } }

    SectionCard("Configuration", "Compiled in from .env by app/build.gradle. Real environment variables win over the file.") {
        env.config.describe().forEach { (k, v) -> KeyValue(k, v, mono = true) }
        KeyValue("SDK version", runCatching { au.kinde.sdk.BuildConfig.SDK_VERSION }.getOrDefault("?"), mono = true)
        KeyValue("SDK HTTP log", if (SdkLog.httpLoggingAvailable) "on (debug SDK build)" else "off (release AAR)", mono = true)
        KindeHub.startupError?.let { Note("KindeClient refused this configuration: ${com.kinde.playground.core.Redact.error(it)}", Outcome.FAILED) }
    }

    SectionCard("Setup checks", "Configuration, Kinde's endpoints, the redirect scheme and a browser for sign-in. No sign-in needed.") {
        ButtonRow { ActionButton("Run setup checks", { checks.start { SetupChecks.run(context) } }, busy = checks.busy) }
        checks.run?.let { CheckRunView(it) }
    }

    SectionCard("Register these in Kinde", "Settings → Applications → your Android (native) app → Details.") {
        KeyValue("Callback URL", env.config.loginRedirect, mono = true)
        KeyValue("Logout URL", env.config.logoutRedirect, mono = true)
    }

    HowItWorks(
        """
        // Application.onCreate — programmatic configuration (PLAYGROUND_CONFIG_MODE=programmatic)
        KindeClient.getInstance(this, KindeConfig(domain, clientId, audience))

        // ...or AndroidManifest meta-data (PLAYGROUND_CONFIG_MODE=manifest)
        <meta-data android:name="au.kinde.domain" android:value="your-business.kinde.com" />
        <meta-data android:name="au.kinde.clientId" android:value="..." />

        // app/build.gradle — must match the redirect URLs
        manifestPlaceholders = [appAuthRedirectScheme: 'com.kinde.playground']

        // Activity.onCreate — one facade per activity, all sharing the client
        sdk = KindeSDK(this, "com.kinde.playground://kinde_callback",
                       "com.kinde.playground://kinde_logout", scopes, listener)
        """
    )
}

@Composable
fun EventsPage() {
    val events by KindeHub.events.collectAsState()
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val s = LocalStatus.current
    SectionCard("SDKListener callbacks", "Newest first. One listener instance is shared by every activity's KindeSDK. Note the thread: onNewToken after sign-in arrives on a background thread, so update UI state accordingly.") {
        ButtonRow { ActionButton("Clear", { KindeHub.clearEvents() }, secondary = true) }
        if (events.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
        events.forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (e.type) {
                        KindeHub.EventType.NEW_TOKEN -> Badge("onNewToken", s.pass, s.passBg)
                        KindeHub.EventType.LOGOUT -> Badge("onLogout", s.warn, s.warnBg)
                        KindeHub.EventType.EXCEPTION -> Badge("onException", s.fail, s.failBg)
                        KindeHub.EventType.APP -> Badge("playground", s.muted, s.mutedBg)
                    }
                    Text(fmt.format(Date(e.atMs)), style = CodeStyle)
                    if (e.type != KindeHub.EventType.APP) {
                        Text(if (e.onMainThread) "main thread" else "thread ${e.threadName}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(e.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HowItWorks(
        """
        val listener = object : SDKListener {
            override fun onNewToken(token: String) { /* may be a background thread */ }
            override fun onLogout() { }
            override fun onException(exception: Exception) { }
        }
        // Pass the same instance to every activity's KindeSDK: broadcasts are de-duplicated.
        """
    )
}
