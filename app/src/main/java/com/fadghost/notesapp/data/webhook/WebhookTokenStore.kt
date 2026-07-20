package com.fadghost.notesapp.data.webhook

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fadghost.notesapp.data.ai.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure store for the automation-webhook bearer token. Reuses the exact same
 * hardware-backed AES-GCM mechanism as the OpenRouter key ([KeystoreCrypto]),
 * but under its own Keystore alias so the two secrets are independent — clearing
 * one never touches the other.
 *
 * The token is a 32-byte random value, base64url-encoded (URL-safe, unpadded) so
 * it can travel in an `Authorization: Bearer` header without escaping. Only the
 * encrypted ciphertext is persisted; plaintext is never written to disk and never
 * logged.
 */
private val Context.webhookSecretsStore by preferencesDataStore(name = "webhook_secrets")

@Singleton
class WebhookTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Distinct alias from the OpenRouter key so the two Keystore keys are separate.
    private val crypto = KeystoreCrypto(alias = "notesapp_webhook_token")
    private val blobKey = stringPreferencesKey("webhook_token_blob")

    /** Whether a token exists — drives UI without ever decrypting. */
    val hasToken: Flow<Boolean> = context.webhookSecretsStore.data.map { it[blobKey] != null }

    /** Decrypt and return the token, or null if none is stored / decryption fails. */
    suspend fun get(): String? {
        val encoded = context.webhookSecretsStore.data.first()[blobKey] ?: return null
        val blob = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return crypto.decrypt(blob)
    }

    /** Return the existing token, generating and persisting a fresh one if absent. */
    suspend fun ensure(): String = get() ?: regenerate()

    /** Generate a new random token (rotating any existing one) and return it. */
    suspend fun regenerate(): String {
        val token = generateToken()
        store(token)
        return token
    }

    /** Wipe the stored token and its wrapping Keystore key. */
    suspend fun clear() {
        context.webhookSecretsStore.edit { it.remove(blobKey) }
        crypto.deleteKey()
    }

    private suspend fun store(token: String) {
        val blob = crypto.encrypt(token)
        val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
        context.webhookSecretsStore.edit { it[blobKey] = encoded }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        /** Mask a token for display, e.g. `wk_…a1b2` — never shows the full secret. */
        fun redact(token: String?): String {
            if (token.isNullOrBlank()) return "(none)"
            return "••••••••••••" + token.takeLast(4)
        }
    }
}
