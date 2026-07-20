package com.fadghost.notesapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.prefs.AutomationPreferences
import com.fadghost.notesapp.data.webhook.WebhookServer
import com.fadghost.notesapp.data.webhook.WebhookTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings → Automation section. Owns the enable/allow-LAN prefs plus the
 * decrypted token used only for the tap-to-copy display; the token is never logged
 * and the section shows it masked.
 */
@HiltViewModel
class AutomationSettingsViewModel @Inject constructor(
    private val prefs: AutomationPreferences,
    private val tokenStore: WebhookTokenStore
) : ViewModel() {

    val enabled: StateFlow<Boolean> = prefs.automationEnabled.state(false)
    val allowLan: StateFlow<Boolean> = prefs.automationAllowLan.state(false)

    private val _token = MutableStateFlow<String?>(null)
    /** The current bearer token (null until loaded / when none exists). */
    val token: StateFlow<String?> = _token

    val port: Int = WebhookServer.PORT

    init {
        viewModelScope.launch { _token.value = tokenStore.get() }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setAutomationEnabled(value)
            if (value) {
                // Provision a key on first enable so the server can start.
                tokenStore.ensure()
                _token.value = tokenStore.get()
            }
        }
    }

    fun setAllowLan(value: Boolean) {
        viewModelScope.launch { prefs.setAutomationAllowLan(value) }
    }

    /** Rotate the key. Existing integrations break until they are updated. */
    fun regenerateToken() {
        viewModelScope.launch { _token.value = tokenStore.regenerate() }
    }

    fun hostFor(allowLan: Boolean): String =
        if (allowLan) WebhookServer.LAN_HOST else WebhookServer.LOOPBACK_HOST

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
