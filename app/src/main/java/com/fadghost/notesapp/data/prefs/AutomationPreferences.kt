package com.fadghost.notesapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret automation-webhook config (the bearer token itself lives in the
 * Keystore-backed [com.fadghost.notesapp.data.webhook.WebhookTokenStore], never
 * here). Mirrors the app convention: config flags in a plain DataStore, secrets
 * in their own encrypted store.
 */
private val Context.automationStore by preferencesDataStore(name = "automation_prefs")

@Singleton
class AutomationPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("automation_enabled")
    private val allowLanKey = booleanPreferencesKey("automation_allow_lan")

    /** Whether the embedded webhook server should run while the app process is alive. */
    val automationEnabled: Flow<Boolean> = context.automationStore.data.map { it[enabledKey] ?: false }

    /** When true, bind 0.0.0.0 (LAN-reachable) instead of loopback-only 127.0.0.1. */
    val automationAllowLan: Flow<Boolean> = context.automationStore.data.map { it[allowLanKey] ?: false }

    suspend fun setAutomationEnabled(enabled: Boolean) =
        context.automationStore.edit { it[enabledKey] = enabled }

    suspend fun setAutomationAllowLan(allow: Boolean) =
        context.automationStore.edit { it[allowLanKey] = allow }
}
