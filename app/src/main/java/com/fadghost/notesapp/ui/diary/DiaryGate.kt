package com.fadghost.notesapp.ui.diary

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

private val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

/**
 * Biometric/device-credential gate for the Diary tab (PLAN.md §7). Shows a locked
 * card and drives [BiometricPrompt]; on success calls [onUnlock]. If the device has
 * no biometric or screen lock enrolled we fail open (don't trap the user) — the
 * honest posture is documented in the Settings toggle subtitle.
 */
@Composable
fun DiaryBiometricGate(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var error by remember { mutableStateOf<String?>(null) }

    fun authenticate() {
        if (activity == null) { onUnlock(); return }
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No enrolled biometric or credential — a UI gate can't help; fail open.
            onUnlock(); return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    error = null
                    onUnlock()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    error = message.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock your diary")
            .setSubtitle("Verify it's you to view your entries")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { error = it.message }
    }

    // Prompt automatically the first time the gate is shown.
    LaunchedEffect(Unit) { authenticate() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(tokens.radii.lg))
                    .background(tokens.colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                LockGlyph(Modifier.size(34.dp))
            }
            Spacer(Modifier.height(20.dp))
            BasicText(
                "Diary locked",
                style = AuraType.titleLg.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                error ?: "Your journal is gated behind your device lock.",
                style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(24.dp))
            val unlockInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(unlockInteraction, tint = true)
                    .background(tokens.colors.accent)
                    .clickable(
                        interactionSource = unlockInteraction,
                        indication = null,
                        onClick = { authenticate() }
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                BasicText(
                    "Unlock",
                    style = AuraType.body.copy(color = tokens.colors.background)
                )
            }
        }
    }
}

@Composable
private fun LockGlyph(modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    val c = tokens.colors.accent
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val st = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        // Shackle.
        drawArc(
            color = c,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(s * 0.32f, s * 0.20f),
            size = Size(s * 0.36f, s * 0.36f),
            style = st
        )
        // Body.
        drawRoundRect(
            color = c,
            topLeft = Offset(s * 0.26f, s * 0.44f),
            size = Size(s * 0.48f, s * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f, s * 0.08f),
            style = st
        )
        // Keyhole.
        drawCircle(c, radius = s * 0.05f, center = Offset(s * 0.5f, s * 0.58f))
    }
}

/** Walk the ContextWrapper chain to find the hosting [FragmentActivity]. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
