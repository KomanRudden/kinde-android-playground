package com.kinde.playground.checks

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Results of the host-side suites (SDK unit tests, the playground's JVM suite,
 * instrumented tests), written by scripts/regression.sh and pushed into this
 * app's files dir with `adb shell run-as`. The app can't run Gradle itself.
 */
data class HostReport(val generatedAt: String, val git: String, val suites: List<Suite>) {
    data class Suite(val name: String, val about: String, val passed: Int, val failed: Int, val skipped: Int, val known: Int, val files: List<FileResult>)
    data class FileResult(val name: String, val area: String, val explain: String, val tests: List<Test>) {
        val outcome: Outcome get() = when {
            tests.any { it.outcome == "failed" } -> Outcome.FAILED
            tests.any { it.outcome == "known" } -> Outcome.WARN
            tests.any { it.outcome == "passed" } -> Outcome.PASSED
            else -> Outcome.SKIPPED
        }
    }
    data class Test(val name: String, val outcome: String, val message: String)

    companion object {
        const val FILE = "regression.json"

        fun file(context: Context) = File(context.filesDir, FILE)

        fun load(context: Context): HostReport? = runCatching { parse(file(context).readText()) }.getOrNull()

        fun parse(text: String): HostReport {
            val root = JSONObject(text)
            val suites = root.getJSONArray("suites")
            return HostReport(
                root.optString("generatedAt"),
                root.optString("git"),
                (0 until suites.length()).map { i ->
                    val s = suites.getJSONObject(i)
                    val files = s.getJSONArray("files")
                    Suite(
                        s.getString("name"), s.optString("about"),
                        s.optInt("passed"), s.optInt("failed"), s.optInt("skipped"), s.optInt("known"),
                        (0 until files.length()).map { j ->
                            val f = files.getJSONObject(j)
                            val tests = f.getJSONArray("tests")
                            FileResult(
                                f.getString("name"), f.optString("area"), f.optString("explain"),
                                (0 until tests.length()).map { k ->
                                    val t = tests.getJSONObject(k)
                                    Test(t.getString("name"), t.getString("outcome"), t.optString("message"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }
}
