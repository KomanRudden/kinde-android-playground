package com.kinde.playground.ui

import android.app.Activity
import androidx.compose.runtime.mutableStateListOf
import au.kinde.sdk.KindeClient
import com.kinde.playground.core.KindeHub

/** Records activity instances and which KindeClient each saw, for the lifecycle page. */
object LifecycleStats {
    data class Entry(val atMs: Long, val activity: String, val instance: Int, val event: String, val clientId: Int?)

    val entries = mutableStateListOf<Entry>()

    private fun clientHash(activity: Activity): Int? =
        if (KindeHub.clientOrNull(activity) != null) System.identityHashCode(KindeClient.getInstance(activity)) else null

    fun onActivityCreated(activity: Activity) = add(activity, "created")
    fun onActivityDestroyed(activity: Activity) = add(activity, if (activity.isChangingConfigurations) "destroyed (config change)" else "destroyed")

    private fun add(activity: Activity, event: String) {
        entries.add(0, Entry(System.currentTimeMillis(), activity.javaClass.simpleName, System.identityHashCode(activity), event, clientHash(activity)))
        if (entries.size > 40) entries.removeAt(entries.lastIndex)
    }
}
