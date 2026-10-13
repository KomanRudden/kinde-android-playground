package com.kinde.playground.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kinde.playground.checks.CheckRun
import com.kinde.playground.checks.HostReport
import com.kinde.playground.checks.LiveChecks
import com.kinde.playground.checks.Outcome
import com.kinde.playground.checks.SecurityChecks
import com.kinde.playground.checks.SelfChecks
import com.kinde.playground.checks.SetupChecks
import com.kinde.playground.ui.components.*
import com.kinde.playground.ui.rememberCheckRunner
import com.kinde.playground.ui.theme.CodeStyle
import com.kinde.playground.ui.theme.LocalStatus

@Composable
fun SecurityPage() {
    val context = LocalContext.current
    val runner = rememberCheckRunner()
    SectionCard("Self-audit", "Reads what the SDK stored on this device, how the last sign-in was protected, and what this process logged. The probe opens a forged redirect the way another app could.") {
        ButtonRow {
            ActionButton("Run audit", { runner.start { SecurityChecks.run(context) } }, busy = runner.busy)
            ActionButton("Run without probes", { runner.start { SecurityChecks.run(context, probes = false) } }, busy = runner.busy, secondary = true)
        }
        runner.run?.let { CheckRunView(it) }
    }
    HowItWorks(
        """
        // What the SDK does:
        // - stores AuthState (tokens) and the JWKS in shared_prefs/app_prefs_secure_<sha256(domain)>.xml,
        //   AES-CBC encrypted with a key made from the domain name and one fixed IV
        // - verifies the access token signature with the first cached JWKS key
        // - lets AppAuth check the redirect's state parameter
        //
        // What your app should do:
        <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules" ...>
        sdk.login(type = GrantType.PKCE)
        // and never log tokens
        """
    )
}

@Composable
fun RegressionPage() {
    val context = LocalContext.current
    val runner = rememberCheckRunner()
    var report by remember { mutableStateOf(HostReport.load(context)) }

    SectionCard("On this device", "Runs here, against the SDK compiled into this APK. Live checks use your signed-in session and your Kinde business.") {
        ButtonRow {
            ActionButton("Run everything", {
                runner.start {
                    val runs = listOf(SetupChecks.run(context), SelfChecks.run(context), SecurityChecks.run(context, probes = false), LiveChecks.run(context))
                    CheckRun("Everything", runs.flatMap { it.checks }, runs.sumOf { it.elapsedMs })
                }
            }, busy = runner.busy)
            ActionButton("On-device self-checks", { runner.start { SelfChecks.run(context) } }, busy = runner.busy, secondary = true)
            ActionButton("Live checks", { runner.start { LiveChecks.run(context) } }, busy = runner.busy, secondary = true)
        }
        runner.run?.let { CheckRunView(it) }
    }

    SectionCard("Host suites", "Gradle runs these on your Mac: the SDK's own unit tests, the playground's SDK contract suite (a fake Kinde over HTTPS), and the instrumented tests on the emulator. scripts/regression.sh copies the results here.") {
        ButtonRow { ActionButton("Reload results", { report = HostReport.load(context) }, secondary = true) }
        val r = report
        if (r == null) {
            Text("No results on this device yet. Run ./scripts/regression.sh with the emulator running.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Generated ${r.generatedAt}${if (r.git.isNotBlank()) " · ${r.git}" else ""}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.suites.forEach { suite -> HostSuite(suite) }
        }
    }

    HowItWorks(
        """
        ./scripts/regression.sh            # everything: SDK + playground JVM suites, build, emulator tests
        ./scripts/regression.sh --quick    # JVM suites only, no emulator
        ./scripts/live-test.sh             # live checks on the emulator with your signed-in session
        ./gradlew :app:testDebugUnitTest -Plive=true --tests '*LiveKindeTest*'   # host live tests (M2M)
        """
    )
}

@Composable
private fun HostSuite(suite: HostReport.Suite) {
    val s = LocalStatus.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("suite:${suite.name}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutcomeBadge(if (suite.failed > 0) Outcome.FAILED else if (suite.passed > 0) Outcome.PASSED else Outcome.SKIPPED)
            Text(suite.name, style = MaterialTheme.typography.titleSmall)
        }
        Text(buildString {
            append("${suite.passed} passed")
            if (suite.failed > 0) append(", ${suite.failed} failed")
            if (suite.known > 0) append(", ${suite.known} known SDK issues")
            if (suite.skipped > 0) append(", ${suite.skipped} skipped")
        }, style = MaterialTheme.typography.bodySmall)
        if (suite.about.isNotBlank()) Text(suite.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        suite.files.forEach { f ->
            var open by remember { mutableStateOf(f.outcome == Outcome.FAILED) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutcomeBadge(f.outcome)
                    Text(f.name, style = CodeStyle, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${f.tests.count { it.outcome == "passed" }}/${f.tests.size}", style = MaterialTheme.typography.labelSmall)
                }
                if (f.explain.isNotBlank()) Text("${f.area} · ${f.explain}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedVisibility(open) {
                    Column(Modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        f.tests.forEach { t ->
                            val color = when (t.outcome) {
                                "failed" -> s.fail
                                "known" -> s.warn
                                "skipped" -> s.muted
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text("${symbol(t.outcome)} ${t.name}", style = MaterialTheme.typography.bodySmall, color = color)
                            if (t.message.isNotBlank() && t.outcome != "passed") Text(t.message, style = CodeStyle, color = color)
                        }
                    }
                }
            }
        }
    }
}

private fun symbol(outcome: String) = when (outcome) {
    "passed" -> "✓"
    "failed" -> "✗"
    "known" -> "!"
    else -> "–"
}
