package com.kinde.playground.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import au.kinde.sdk.KindeSDK
import com.kinde.playground.checks.CheckRun
import com.kinde.playground.config.PlaygroundConfig
import com.kinde.playground.core.CallResult
import com.kinde.playground.core.Calls
import com.kinde.playground.core.KindeHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What every page needs: the activity's KindeSDK facade (null until configured) and the config. */
data class PlaygroundEnv(
    val sdk: KindeSDK?,
    val config: PlaygroundConfig,
    val activity: MainActivity?,
    val navigate: (Page) -> Unit,
)

val LocalEnv = compositionLocalOf<PlaygroundEnv> { error("No PlaygroundEnv") }

/** Runs SDK calls off the main thread and keeps the latest result per label for display. */
class Runner(private val scope: CoroutineScope) {
    val results = mutableStateMapOf<String, CallResult>()
    var busy by mutableStateOf<String?>(null)
        private set

    fun run(label: String, onMain: Boolean = false, block: () -> Any?) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                results[label] = Calls.run(label, onMain, block)
            } finally {
                busy = null
            }
        }
    }

    fun clear() = results.clear()
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    return remember { Runner(scope) }
}

/** Runs a check suite in the background and holds its latest result. */
class CheckRunner(private val scope: CoroutineScope) {
    var run by mutableStateOf<CheckRun?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun start(block: () -> CheckRun) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                run = withContext(Dispatchers.IO) { block() }
                KindeHub.note("${run!!.title} checks: ${run!!.summary()}")
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberCheckRunner(): CheckRunner {
    val scope = rememberCoroutineScope()
    return remember { CheckRunner(scope) }
}
