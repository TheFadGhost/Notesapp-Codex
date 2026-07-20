package com.fadghost.notesapp.data.webhook

import android.util.Log
import com.fadghost.notesapp.data.prefs.AutomationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of the embedded [WebhookServer]. Started from [com.fadghost.notesapp.NotesApp]
 * via [bind]; thereafter it observes the automation prefs and (re)binds the server
 * whenever the enable flag or the allow-LAN host changes.
 *
 * The server only ever runs while the app process is alive — there is no background
 * service. This is intentional and documented in WEBHOOK.md: the phone must have the
 * app running for automations to be accepted.
 */
@Singleton
class WebhookServerController @Inject constructor(
    private val prefs: AutomationPreferences,
    private val tokenStore: WebhookTokenStore,
    private val executor: WebhookExecutor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var server: WebhookServer? = null
    private var bound = false

    /** Idempotent: begins observing prefs and applying server state. */
    fun bind() {
        if (bound) return
        bound = true
        scope.launch {
            combine(prefs.automationEnabled, prefs.automationAllowLan) { enabled, allowLan ->
                Config(enabled, allowLan)
            }.distinctUntilChanged().collect { applyConfig(it) }
        }
    }

    private suspend fun applyConfig(config: Config) {
        mutex.withLock {
            // Any change tears the old server down first (host may have changed).
            stopLocked()
            if (!config.enabled) return@withLock

            // Server refuses to start with no key: generate one on first enable.
            val token = runCatching { tokenStore.ensure() }.getOrNull()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Automation enabled but no key could be provisioned; server not started")
                return@withLock
            }

            val host = if (config.allowLan) WebhookServer.LAN_HOST else WebhookServer.LOOPBACK_HOST
            val fresh = WebhookServer(host = host, tokenStore = tokenStore, executor = executor)
            runCatching { fresh.start() }
                .onSuccess {
                    server = fresh
                    Log.i(TAG, "Automation server listening on $host:${WebhookServer.PORT}")
                }
                .onFailure { Log.e(TAG, "Automation server failed to start on $host:${WebhookServer.PORT}", it) }
        }
    }

    private fun stopLocked() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private data class Config(val enabled: Boolean, val allowLan: Boolean)

    private companion object {
        const val TAG = "WebhookServer"
    }
}
