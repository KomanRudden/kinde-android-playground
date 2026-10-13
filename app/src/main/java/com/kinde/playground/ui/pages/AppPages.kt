package com.kinde.playground.ui.pages

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.kinde.sdk.KindeClient
import au.kinde.sdk.api.model.CreateUserRequest
import au.kinde.sdk.api.model.CreateUserRequestIdentitiesInner
import au.kinde.sdk.api.model.CreateUserRequestIdentitiesInnerDetails
import au.kinde.sdk.api.model.CreateUserRequestProfile
import com.kinde.playground.checks.Outcome
import com.kinde.playground.core.KindeHub
import com.kinde.playground.core.Redact
import com.kinde.playground.ui.LifecycleStats
import com.kinde.playground.ui.LocalEnv
import com.kinde.playground.ui.SecondActivity
import com.kinde.playground.ui.components.*
import com.kinde.playground.ui.rememberRunner
import com.kinde.playground.work.InterceptorDemo
import com.kinde.playground.work.TokenWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackgroundPage() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var worker by rememberSaveable { mutableStateOf<String?>(null) }
    var workerBusy by rememberSaveable { mutableStateOf(false) }
    var interceptor by rememberSaveable { mutableStateOf<String?>(null) }
    val runner = rememberRunner()

    SectionCard("WorkManager", "A CoroutineWorker reads the token and calls the Account API through KindeClient.getInstance(context): no activity, no facade.") {
        ButtonRow {
            ActionButton("Enqueue TokenWorker", {
                workerBusy = true
                val request = OneTimeWorkRequestBuilder<TokenWorker>().build()
                val wm = WorkManager.getInstance(context)
                wm.enqueue(request)
                scope.launch {
                    val info = wm.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
                    worker = info?.outputData?.getString("summary") ?: "Worker finished as ${info?.state}"
                    workerBusy = false
                }
            }, enabled = env.config.isConfigured, busy = workerBusy)
        }
        worker?.let { CodeBlock(it) }
    }
    SectionCard("OkHttp interceptor", "How you'd call your own API: an interceptor adds the Kinde access token to every request. Here it calls Kinde's user profile endpoint as a stand-in.") {
        ButtonRow {
            ActionButton("Call with interceptor", {
                scope.launch {
                    interceptor = withContext(Dispatchers.IO) {
                        runCatching { InterceptorDemo.call(context, env.config.domain) }.getOrElse { Redact.error(it) }
                    }
                }
            }, enabled = env.config.isConfigured)
        }
        interceptor?.let { CodeBlock(it) }
        Note("The interceptor reads the token the SDK holds; it does not refresh it. If your API returns 401, the token may have expired while the app was in the background (the SDK pauses refresh there).")
    }
    SectionCard("Plain background thread", "KindeClient methods are safe to call from any thread; network calls must not be on the main thread.") {
        ButtonRow {
            ActionButton("getPermissions() on IO", { runner.run("KindeClient.getPermissions() on IO") {
                KindeClient.getInstance(context).getPermissions(au.kinde.sdk.api.ApiOptions(forceApi = true, useCache = false))
            } }, enabled = env.config.isConfigured)
        }
        runner.results.values.forEach { CallResultView(it) }
    }
    HowItWorks(
        """
        class KindeAuthInterceptor(private val context: Context) : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val token = KindeClient.getInstance(context).getToken(TokenType.ACCESS_TOKEN)
                return chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Bearer ${'$'}token").build())
            }
        }
        """
    )
}

@Composable
fun LifecyclePage() {
    val env = LocalEnv.current
    val context = LocalContext.current
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    SectionCard("One client, many facades", "Each activity creates its own KindeSDK in onCreate. They all wrap the same KindeClient, so tokens, the refresh schedule and caches survive rotation and navigation.") {
        KeyValue("KindeClient", KindeHub.clientOrNull(context)?.let { "#" + System.identityHashCode(it).toString(16) } ?: "—", mono = true)
        KeyValue("Process state", ProcessLifecycleOwner.get().lifecycle.currentState.name, mono = true)
        ButtonRow {
            ActionButton("Open second activity", { context.startActivity(Intent(context, SecondActivity::class.java)) })
            ActionButton("Recreate this activity", { env.activity?.recreate() }, secondary = true)
        }
        Text("Rotate the device, open the second activity, or press Home and come back: the client hash below never changes. In the background the refresh schedule pauses; it resumes (refreshing immediately if due) when the app returns.",
            style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Activity instances") {
        if (LifecycleStats.entries.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodySmall)
        LifecycleStats.entries.forEach { e ->
            KeyValue(fmt.format(Date(e.atMs)), "${e.activity}#${e.instance.toString(16)} ${e.event} → client ${e.clientId?.let { "#" + it.toString(16) } ?: "—"}", mono = true)
        }
    }
    HowItWorks(
        """
        class AnyActivity : ComponentActivity() {
            private lateinit var sdk: KindeSDK
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                sdk = KindeSDK(this, loginRedirect, logoutRedirect, sdkListener = sharedListener)
            }
        }
        """
    )
}

@Composable
fun ManagementPage() {
    val env = LocalEnv.current
    val sdk = env.sdk
    val runner = rememberRunner()
    var sort by rememberSaveable { mutableStateOf("") }
    var pageSize by rememberSaveable { mutableStateOf("5") }
    var userId by rememberSaveable { mutableStateOf("") }
    var nextToken by rememberSaveable { mutableStateOf("") }
    var given by rememberSaveable { mutableStateOf("Playground") }
    var family by rememberSaveable { mutableStateOf("User") }
    var email by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    Note("The SDK's getUsers() and createUser() call the Management API (/api/v1/…) with the signed-in user's access token. Kinde's Management API expects a machine-to-machine token, and an M2M secret must never ship in an app. Expect these to fail; the playground's host-side live tests check the same endpoints with an M2M token from your machine.", Outcome.WARN)

    SectionCard("getUsers()", "GET /api/v1/users. userId is an Int in the SDK, but Kinde user IDs are strings like kp_….") {
        Field("sort", sort, { sort = it }, placeholder = "name_asc")
        Field("pageSize", pageSize, { pageSize = it })
        Field("userId (Int)", userId, { userId = it })
        Field("nextToken", nextToken, { nextToken = it })
        ButtonRow {
            ActionButton("getUsers()", {
                runner.run("getUsers()") {
                    sdk?.getUsers(sort.ifBlank { null }, pageSize.toIntOrNull(), userId.toIntOrNull(), nextToken.ifBlank { null })
                }
            }, enabled = sdk != null && runner.busy == null, busy = runner.busy == "getUsers()")
        }
        runner.results["getUsers()"]?.let { CallResultView(it) }
        Note("A 403 here makes the SDK refresh the session token and retry, because it treats every 403 as an expired token. Watch the HTTP lines.")
    }

    SectionCard("createUser()", if (env.config.allowMutations) "POST /api/v1/user. Type \"playground\" to confirm." else "Disabled: set PLAYGROUND_ALLOW_MUTATIONS=true to try it. Use a test business.") {
        Field("given_name", given, { given = it })
        Field("family_name", family, { family = it })
        Field("email", email, { email = it }, placeholder = "playground+test@example.com")
        Field("confirm", confirm, { confirm = it }, placeholder = "playground")
        ButtonRow {
            ActionButton("createUser()", {
                runner.run("createUser()") {
                    sdk?.createUser(
                        CreateUserRequest(
                            profile = CreateUserRequestProfile(givenName = given, familyName = family),
                            identities = listOf(CreateUserRequestIdentitiesInner("email", CreateUserRequestIdentitiesInnerDetails(email.trim()))),
                        )
                    )
                }
            }, enabled = sdk != null && env.config.allowMutations && confirm == "playground" && email.contains('@') && runner.busy == null,
                busy = runner.busy == "createUser()")
        }
        runner.results["createUser()"]?.let { CallResultView(it) }
    }
    HowItWorks(
        """
        // From a backend with an M2M token — not from the app:
        // POST https://<domain>/oauth2/token  grant_type=client_credentials
        //      audience=https://<domain>/api
        val users = withContext(Dispatchers.IO) { sdk.getUsers(pageSize = 5) }
        val created = withContext(Dispatchers.IO) {
            sdk.createUser(CreateUserRequest(profile = ..., identities = ...))
        }
        """
    )
}
