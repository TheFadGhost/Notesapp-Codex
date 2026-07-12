package com.fadghost.notesapp.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.ui.MainViewModel
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.ThemeResolver
import com.fadghost.notesapp.ui.theme.ThemeSwitchController
import com.fadghost.notesapp.ui.theme.ThemeTokens

/**
 * Appearance settings (PLAN.md §9/§10): animated theme-preview swatch picker across
 * Light/Dark/AMOLED/Grey/System, an 8-colour accent picker, and the reduce-motion
 * toggle. Uses its own Activity-scoped [MainViewModel] instance so it stays in sync
 * with the root theme state.
 */
@Composable
fun AppearanceSettingsSection(viewModel: MainViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val mode by viewModel.themeMode.collectAsState()
    val accentIndex by viewModel.accentIndex.collectAsState()
    val reduceMotion by viewModel.reduceMotion.collectAsState()
    val systemDark = isSystemInDarkTheme()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(16.dp)
    ) {
        BasicText("Appearance", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(14.dp))

        BasicText("Theme", style = AuraType.body.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ThemeMode.entries.size) { i ->
                val m = ThemeMode.entries[i]
                ThemeSwatchCard(
                    mode = m,
                    preview = ThemeResolver.baseTokens(m, systemDark),
                    selected = m == mode,
                    onSelect = viewModel::setThemeMode
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        BasicText("Accent", style = AuraType.body.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AuraAccents.themeAccents.size) { index ->
                AccentDot(
                    color = AuraAccents.themeAccents[index],
                    label = accentName(index),
                    selected = accentIndex == index,
                    onSelect = { viewModel.setAccentIndex(index) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // "Theme default" reset chip.
        val defaultSelected = accentIndex == AuraAccents.THEME_DEFAULT
        Box(
            Modifier
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(if (defaultSelected) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { viewModel.setAccentIndex(AuraAccents.THEME_DEFAULT) }
                )
                .semantics {
                    selected = defaultSelected
                    contentDescription = "Theme default accent"
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            BasicText("Theme default", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }

        Spacer(Modifier.height(18.dp))
        ReduceMotionRow(enabled = reduceMotion, onToggle = viewModel::setReduceMotion)
    }
}

@Composable
private fun ThemeSwatchCard(
    mode: ThemeMode,
    preview: ThemeTokens,
    selected: Boolean,
    onSelect: (ThemeMode) -> Unit
) {
    val tokens = Aura.tokens
    val haptics = rememberAuraHaptics()
    val view = LocalView.current
    val ringAlpha by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    val lift by animateDpAsState(if (selected) 2.dp else 0.dp, label = "lift")
    var center by remember { androidx.compose.runtime.mutableStateOf(Offset(0.5f, 0.5f)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                val w = view.width.takeIf { it > 0 } ?: 1
                val h = view.height.takeIf { it > 0 } ?: 1
                center = Offset(b.center.x / w, b.center.y / h)
            }
            .clip(RoundedCornerShape(tokens.radii.md))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    ThemeSwitchController.setOrigin(center.x, center.y)
                    haptics.tick()
                    onSelect(mode)
                }
            )
            .semantics {
                this.selected = selected
                contentDescription = "${prettyMode(mode)} theme"
            }
            .padding(4.dp)
    ) {
        // Mini preview of the theme's own tokens.
        Box(
            Modifier
                .padding(bottom = lift)
                .size(width = 68.dp, height = 84.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(preview.colors.background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = lerp(tokens.colors.outline, tokens.colors.accent, ringAlpha),
                    shape = RoundedCornerShape(tokens.radii.sm)
                )
                .padding(8.dp)
        ) {
            Column {
                // Title bar.
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(preview.colors.textPrimary)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(preview.colors.textSecondary)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(preview.colors.textSecondary)
                )
                Spacer(Modifier.weight(1f))
                // Mini nav pill + accent capture dot.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(preview.colors.surfaceTranslucent)
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(preview.colors.accent)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            prettyMode(mode),
            style = AuraType.label.copy(
                color = if (selected) tokens.colors.accent else tokens.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun AccentDot(
    color: Color,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val tokens = Aura.tokens
    val haptics = rememberAuraHaptics()
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "accentRing")
    // 48dp touch target (PLAN.md §11) with a smaller visual dot centred inside.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { haptics.tick(); onSelect() }
            )
            .semantics {
                this.selected = selected
                contentDescription = "$label accent"
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = lerp(Color.Transparent, tokens.colors.textPrimary, ring),
                    shape = CircleShape
                )
                .padding(5.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun ReduceMotionRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val tokens = Aura.tokens
    val haptics = rememberAuraHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { haptics.tick(); onToggle(!enabled) }
            )
            .semantics {
                stateDescription = if (enabled) "On" else "Off"
                contentDescription = "Reduce motion"
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("Reduce motion", style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(
                "Collapse springs to quick fades",
                style = AuraType.label.copy(color = tokens.colors.textSecondary)
            )
        }
        AuraToggle(checked = enabled, onCheckedChange = { haptics.tick(); onToggle(it) })
    }
}

/** Minimal custom toggle (no Material Switch) used by appearance settings. */
@Composable
private fun AuraToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tokens = Aura.tokens
    val t by animateFloatAsState(if (checked) 1f else 0f, label = "toggle")
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(CircleShape)
            .background(lerp(tokens.colors.outline, tokens.colors.accent, t))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val offset by animateDpAsState(if (checked) 18.dp else 0.dp, label = "knob")
        Box(
            Modifier
                .padding(start = offset)
                .size(22.dp)
                .clip(CircleShape)
                .background(tokens.colors.surface)
        )
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

private fun prettyMode(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
    ThemeMode.GREY -> "Grey"
    ThemeMode.SYSTEM -> "System"
}

private fun accentName(index: Int): String = listOf(
    "Terracotta", "Ochre", "Sage", "Olive", "Oxblood", "Slate", "Taupe", "Forest"
).getOrElse(index) { "Accent" }
