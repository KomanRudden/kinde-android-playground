package com.kinde.playground.sdk

import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks a test that asserts the *correct* behaviour for a known SDK defect.
 *
 * Like pytest's strict xfail: while the defect exists the test fails, and the
 * rule reports it as skipped with "KNOWN ISSUE: ..." so the suite stays green
 * and the report lists it. When the SDK is fixed the test passes, and the rule
 * fails it with "FIXED: remove @KnownIssue" so the marker can't go stale.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KnownIssue(val value: String)

/** A test environment problem (bad credentials, missing config) that must not be mistaken for the known issue. */
class SetupFailure(message: String) : AssertionError(message)

class KnownIssueRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        val known = description.getAnnotation(KnownIssue::class.java) ?: return base
        return object : Statement() {
            override fun evaluate() {
                val failure = try {
                    base.evaluate()
                    null
                } catch (e: AssumptionViolatedException) {
                    throw e
                } catch (e: SetupFailure) {
                    // The test never reached the behaviour it is about: report it as a failure.
                    throw e
                } catch (t: Throwable) {
                    t
                }
                if (failure == null) {
                    throw AssertionError("FIXED: \"${known.value}\" no longer reproduces. Remove @KnownIssue from ${description.methodName}.")
                }
                throw AssumptionViolatedException("KNOWN ISSUE: ${known.value} [${failure.javaClass.simpleName}: ${failure.message?.take(200)}]")
            }
        }
    }
}
