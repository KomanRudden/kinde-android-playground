package com.kinde.playground.core

import au.kinde.sdk.api.model.entitlements.Entitlement
import au.kinde.sdk.api.model.entitlements.EntitlementResponse
import au.kinde.sdk.api.model.entitlements.EntitlementsResponse
import au.kinde.sdk.model.ClaimData
import au.kinde.sdk.model.Flag
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of one SDK call as shown on a page. */
data class CallResult(
    val label: String,
    val ok: Boolean,
    val value: String,
    val http: List<SdkLog.HttpCall>,
    val durationMs: Long,
    val sdkExceptions: List<String> = emptyList(),
)

object Calls {
    /**
     * Runs an SDK call on a background thread (the SDK refuses network calls on
     * the main thread) and records the HTTP calls and listener exceptions it caused.
     */
    suspend fun run(label: String, onMain: Boolean = false, block: () -> Any?): CallResult {
        val before = KindeHub.events.value.firstOrNull()?.id ?: -1
        val started = System.currentTimeMillis()
        val dispatcher = if (onMain) Dispatchers.Main else Dispatchers.IO
        val (outcome, http) = withContext(dispatcher) {
            SdkLog.capture { runCatching(block) }
        }
        val elapsed = System.currentTimeMillis() - started
        val exceptions = KindeHub.events.value
            .filter { it.id > before && it.type == KindeHub.EventType.EXCEPTION }
            .map { it.detail }
            .reversed()
        return outcome.fold(
            onSuccess = { CallResult(label, it != null, describe(it), http, elapsed, exceptions) },
            onFailure = { CallResult(label, false, "Threw " + Redact.error(it), http, elapsed, exceptions) },
        )
    }

    fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    /** Human-readable rendering of SDK return types (several are not data classes). */
    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is ClaimData.Permission -> "Permission(orgCode=${value.orgCode.q()}, isGranted=${value.isGranted})"
        is ClaimData.Permissions -> "Permissions(orgCode=${value.orgCode.q()}, permissions=${value.permissions})"
        is ClaimData.Role -> "Role(orgCode=${value.orgCode.q()}, isGranted=${value.isGranted})"
        is ClaimData.Roles -> "Roles(orgCode=${value.orgCode.q()}, roles=${value.roles})"
        is ClaimData.Organizations -> "Organizations(orgCodes=${value.orgCodes})"
        is ClaimData.Organization -> "Organization(orgCode=${value.orgCode.q()})"
        is ClaimData.Claim -> "Claim(name=${value.name.q()}, value=${valueWithType(value.value)})"
        is Flag -> "Flag(code=${value.code.q()}, type=${value.type}, value=${valueWithType(value.value)}, isDefault=${value.isDefault})"
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString("\n") { "${it.key} → ${describe(it.value)}" }
        is EntitlementsResponse -> buildString {
            append("orgCode=${value.data.orgCode.q()}\n")
            append("plans=").append(value.data.plans.joinToString { "${it.key} (${it.name})" }.ifEmpty { "none" }).append('\n')
            append("entitlements:\n")
            value.data.entitlements.forEach { append("  ").append(entitlement(it)).append('\n') }
            // Declared non-null, but Gson leaves it null on the last page.
            append("metadata: hasMore=${value.metadata.hasMore}, nextPageStartingAfter=${value.metadata.nextPageStartingAfter?.q()}")
        }
        is EntitlementResponse -> "orgCode=${value.data.orgCode.q()}\n${entitlement(value.data.entitlement)}\nmetadata=${value.metadata}"
        is List<*> -> if (value.isEmpty()) "[]" else value.joinToString("\n") { describe(it) }
        is String -> value.q()
        else -> value.toString()
    }

    private fun entitlement(e: Entitlement) =
        "${e.featureKey} (${e.featureName}): min=${e.entitlementLimitMin}, max=${e.entitlementLimitMax}, price=${e.priceName}"

    fun valueWithType(v: Any?): String = when (v) {
        null -> "null"
        is String -> "${v.q()} : String"
        else -> "$v : ${v.javaClass.simpleName}"
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun String?.q() = if (this == null) "null" else "\"$this\""
}
