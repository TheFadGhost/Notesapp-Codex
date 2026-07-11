package com.fadghost.notesapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LocalThemeTokens = staticCompositionLocalOf { DarkTokens }

/** Access point for Aura tokens: `Aura.tokens.colors.accent`. */
object Aura {
    val tokens: ThemeTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeTokens.current
}

/** Minimal type scale so visible UI never touches MaterialTheme typography. */
object AuraType {
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun AuraTheme(
    tokens: ThemeTokens,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalThemeTokens provides tokens) {
        content()
    }
}
