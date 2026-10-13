package com.kinde.playground.core

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Polls the SDK's access token while the app is in the foreground and notes
 * when it changes without an onNewToken callback. The SDK's scheduled refresh
 * runs with notifyListener = false, so apps that cache the token from
 * onNewToken never hear about it; this makes that visible on the Events page.
 */
object TokenWatcher {
    @Volatile
    private var last: String? = null

    suspend fun watch(context: Context) {
        last = KindeHub.accessToken(context)
        while (true) {
            delay(1000)
            val now = KindeHub.accessToken(context)
            val previous = last
            if (now != previous) {
                last = now
                if (previous != null && now != null) {
                    delay(500) // let a matching onNewToken land first
                    val masked = Redact.mask(now)
                    val announced = KindeHub.events.value.take(10).any {
                        it.type == KindeHub.EventType.NEW_TOKEN && masked in it.detail
                    }
                    KindeHub.note(
                        if (announced) "Access token rotated; the listener was told."
                        else "Access token rotated to $masked by a background refresh, without an onNewToken callback."
                    )
                }
            }
        }
    }
}
