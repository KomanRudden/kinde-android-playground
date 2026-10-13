package com.kinde.playground.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import au.kinde.sdk.KindeSDK
import com.kinde.playground.core.KindeHub
import com.kinde.playground.ui.components.Badge
import com.kinde.playground.ui.components.Note
import com.kinde.playground.checks.Outcome
import com.kinde.playground.ui.pages.*
import com.kinde.playground.ui.theme.LocalStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(sdk: KindeSDK?, activity: MainActivity?, startPage: Page = Page.HOME) {
    var stack by rememberSaveable { mutableStateOf(if (startPage == Page.HOME) listOf(Page.HOME.name) else listOf(Page.HOME.name, startPage.name)) }
    val page = Page.from(stack.last())
    val navigate: (Page) -> Unit = { stack = stack + it.name }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    val env = PlaygroundEnv(sdk, KindeHub.config, activity, navigate)
    CompositionLocalProvider(LocalEnv provides env) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(page.title, modifier = Modifier.testTag("title")) },
                    navigationIcon = {
                        if (stack.size > 1) {
                            IconButton(onClick = { stack = stack.dropLast(1) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = { SessionChip() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (page == Page.HOME) {
                HomePage(padding)
            } else {
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("page:${page.name}"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (!env.config.isConfigured && page != Page.DASHBOARD && page != Page.REGRESSION && page != Page.SECURITY) {
                        Note("Not configured yet: fill in .env and rebuild. Calls on this page need a Kinde client.", Outcome.WARN)
                    }
                    when (page) {
                        Page.DASHBOARD -> DashboardPage()
                        Page.SIGN_IN -> SignInPage()
                        Page.PROFILE -> ProfilePage()
                        Page.TOKENS -> TokensPage()
                        Page.CLAIMS -> ClaimsPage()
                        Page.ACCESS -> AccessPage()
                        Page.FLAGS -> FlagsPage()
                        Page.BILLING -> BillingPage()
                        Page.ORGS -> OrganizationsPage()
                        Page.BACKGROUND -> BackgroundPage()
                        Page.LIFECYCLE -> LifecyclePage()
                        Page.MANAGEMENT -> ManagementPage()
                        Page.SECURITY -> SecurityPage()
                        Page.REGRESSION -> RegressionPage()
                        Page.EVENTS -> EventsPage()
                        Page.HOME -> Unit
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionChip() {
    val session by KindeHub.session.collectAsState()
    val s = LocalStatus.current
    val (label, fg, bg) = when (session) {
        KindeHub.Session.SIGNED_IN -> Triple("signed in", s.pass, s.passBg)
        KindeHub.Session.SIGNED_OUT -> Triple("signed out", s.muted, s.mutedBg)
        KindeHub.Session.UNKNOWN -> Triple(if (KindeHub.config.isConfigured) "starting" else "not configured", s.warn, s.warnBg)
    }
    Badge(label, fg, bg, Modifier.padding(end = 12.dp).testTag("session"))
}

@Composable
private fun HomePage(padding: PaddingValues) {
    val env = LocalEnv.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("page:HOME"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Every part of the Kinde Android SDK, used the way a real app uses it. Each page pairs a live result with the SDK code behind it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (!env.config.isConfigured) {
                Note("Not configured: copy .env.example to .env, fill in KINDE_DOMAIN and KINDE_CLIENT_ID, and rebuild. The Dashboard lists what's missing.", Outcome.WARN)
                Spacer(Modifier.height(8.dp))
            }
        }
        items(Page.menu) { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { env.navigate(p) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("nav:${p.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(p.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
