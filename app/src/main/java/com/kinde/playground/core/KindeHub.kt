package com.kinde.playground.core

import android.content.Context
import android.os.Looper
import au.kinde.sdk.KindeClient
import au.kinde.sdk.KindeConfig
import au.kinde.sdk.SDKListener
import au.kinde.sdk.model.TokenType
import com.kinde.playground.config.ConfigMode
import com.kinde.playground.config.PlaygroundConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-scoped glue between the SDK and the UI.
 *
 * One [SDKListener] instance is shared by every activity's KindeSDK facade (the
 * SDK supports this and dedupes broadcasts). Callbacks arrive on whatever thread
 * the SDK is on — onNewToken after a sign-in comes from a background thread — so
 * everything here is a thread-safe StateFlow and the UI collects it.
 */
object KindeHub {

    enum class EventType { NEW_TOKEN, LOGOUT, EXCEPTION, APP }

    data class Event(
        val id: Long,
        val atMs: Long,
        val type: EventType,
        val detail: String,
        val onMainThread: Boolean,
        val threadName: String,
    )

    enum class Session { UNKNOWN, SIGNED_OUT, SIGNED_IN }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _session = MutableStateFlow(Session.UNKNOWN)
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Bumped on every SDK callback so pages that read SDK state can recompute. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    var startupError: Throwable? = null
        private set

    private var nextId = 0L

    val config: PlaygroundConfig get() = PlaygroundConfig.current

    val listener = object : SDKListener {
        override fun onNewToken(token: String) {
            val exp = Jwt.decode(token)?.secondsLeft()
            record(EventType.NEW_TOKEN, "Access token ${Redact.mask(token)}" + (exp?.let { ", expires in ${it}s" } ?: ""))
            _session.value = Session.SIGNED_IN
        }

        override fun onLogout() {
            record(EventType.LOGOUT, "Signed out (or no session to restore)")
            _session.value = Session.SIGNED_OUT
            LogoutProbe.onLogout()
        }

        override fun onException(exception: Exception) {
            record(EventType.EXCEPTION, Redact.error(exception))
        }
    }

    fun note(text: String) = record(EventType.APP, text)

    @Synchronized
    private fun record(type: EventType, detail: String) {
        val event = Event(
            id = nextId++,
            atMs = System.currentTimeMillis(),
            type = type,
            detail = Redact.text(detail),
            onMainThread = Looper.myLooper() == Looper.getMainLooper(),
            threadName = Thread.currentThread().name,
        )
        _events.update { (listOf(event) + it).take(200) }
        _revision.update { it + 1 }
    }

    fun clearEvents() = _events.update { emptyList() }

    /** Re-reads SDK state, e.g. after a page changed it. */
    fun refresh(context: Context) {
        val client = clientOrNull(context) ?: return
        _session.value = if (client.isAuthenticated()) Session.SIGNED_IN else Session.SIGNED_OUT
        _revision.update { it + 1 }
    }

    /**
     * Called from Application.onCreate. In programmatic mode this is where the
     * process-wide client is configured — before any activity creates a facade.
     */
    fun initialize(context: Context) {
        if (!config.isConfigured) return
        try {
            when (config.configMode) {
                ConfigMode.PROGRAMMATIC -> KindeClient.getInstance(
                    context, KindeConfig(config.domain, config.clientId, config.audience)
                )
                ConfigMode.MANIFEST -> KindeClient.getInstance(context)
            }
        } catch (t: Throwable) {
            startupError = t
            record(EventType.EXCEPTION, "KindeClient could not start: ${Redact.error(t)}")
        }
    }

    /** The shared client, or null when the playground isn't configured or failed to start. */
    fun clientOrNull(context: Context): KindeClient? {
        if (!config.isConfigured || startupError != null) return null
        return runCatching { KindeClient.getInstance(context) }.getOrNull()
    }

    fun accessToken(context: Context): String? = clientOrNull(context)?.getToken(TokenType.ACCESS_TOKEN)
    fun idToken(context: Context): String? = clientOrNull(context)?.getToken(TokenType.ID_TOKEN)

    /** Values that must never appear in UI text or logs. */
    fun knownSecrets(context: Context): List<String> {
        val client = clientOrNull(context) ?: return emptyList()
        return listOfNotNull(
            client.getToken(TokenType.ACCESS_TOKEN),
            client.getToken(TokenType.ID_TOKEN),
            client.getRefreshToken(),
        )
    }
}
