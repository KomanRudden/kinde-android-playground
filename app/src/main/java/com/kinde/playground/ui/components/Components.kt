package com.kinde.playground.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kinde.playground.checks.Check
import com.kinde.playground.checks.CheckRun
import com.kinde.playground.checks.Outcome
import com.kinde.playground.core.CallResult
import com.kinde.playground.ui.theme.CodeStyle
import com.kinde.playground.ui.theme.LocalStatus

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** The "How this works" panel: the SDK code behind a page. */
@Composable
fun HowItWorks(code: String, title: String = "How this works") {
    var open by rememberSaveable { mutableStateOf(false) }
    val status = LocalStatus.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(status.codeBg, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(open) {
            CodeBlock(code.trimIndent(), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), background = false)
        }
    }
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, background: Boolean = true, wrap: Boolean = false) {
    val status = LocalStatus.current
    SelectionContainer {
        Text(
            text,
            style = CodeStyle,
            color = status.code,
            modifier = modifier
                .fillMaxWidth()
                .then(if (background) Modifier.background(status.codeBg, RoundedCornerShape(10.dp)).padding(12.dp) else Modifier)
                // Code keeps its lines and scrolls sideways; call results wrap so the whole value is visible.
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
        )
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = if (mono) CodeStyle else MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Badge(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun OutcomeBadge(outcome: Outcome) {
    val s = LocalStatus.current
    when (outcome) {
        Outcome.PASSED -> Badge("pass", s.pass, s.passBg)
        Outcome.FAILED -> Badge("fail", s.fail, s.failBg)
        Outcome.WARN -> Badge("warn", s.warn, s.warnBg)
        Outcome.SKIPPED -> Badge("skip", s.muted, s.mutedBg)
        Outcome.INFO -> Badge("info", s.muted, s.mutedBg)
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    secondary: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    if (secondary) {
        OutlinedButton(onClick, modifier.testTag("btn:$label"), enabled = enabled && !busy, shape = RoundedCornerShape(10.dp)) { content() }
    } else {
        Button(onClick, modifier.testTag("btn:$label"), enabled = enabled && !busy, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors()) { content() }
    }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("field:$label"),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(10.dp),
    )
}

@Composable
fun Note(text: String, tone: Outcome = Outcome.INFO) {
    val s = LocalStatus.current
    val (fg, bg) = when (tone) {
        Outcome.FAILED -> s.fail to s.failBg
        Outcome.WARN -> s.warn to s.warnBg
        Outcome.PASSED -> s.pass to s.passBg
        else -> MaterialTheme.colorScheme.onSurfaceVariant to s.mutedBg
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = fg,
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(12.dp))
}

@Composable
fun CallResultView(result: CallResult) {
    val s = LocalStatus.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("result:${result.label}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (result.ok) Badge("ok", s.pass, s.passBg) else Badge("no value", s.fail, s.failBg)
            Text(result.label, style = CodeStyle, modifier = Modifier.weight(1f))
            Text("${result.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CodeBlock(result.value, wrap = true)
        if (result.http.isNotEmpty()) {
            Text("HTTP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.http.forEach { call ->
                Text("${call.status ?: "—"}  ${call.url}", style = CodeStyle,
                    color = if ((call.status ?: 0) >= 400) s.fail else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (result.sdkExceptions.isNotEmpty()) {
            Text("SDKListener.onException", style = MaterialTheme.typography.labelSmall, color = s.fail)
            result.sdkExceptions.forEach { Text(it, style = CodeStyle, color = s.fail) }
        }
    }
}

@Composable
fun CheckRow(check: Check) {
    var open by remember { mutableStateOf(check.outcome == Outcome.FAILED) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 8.dp)
            .testTag("check:${check.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutcomeBadge(check.outcome)
            Text(check.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        Text(check.result, style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Did: ${check.did}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Network: ${check.http}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CheckRunView(run: CheckRun) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutcomeBadge(run.status)
            Text(run.summary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("summary:${run.title}"))
        }
        run.checks.groupBy { it.group }.forEach { (group, checks) ->
            Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            checks.forEach { CheckRow(it) }
        }
    }
}
