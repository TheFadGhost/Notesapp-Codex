package com.fadghost.notesapp.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

// Bubble diameter (V2-SPEC item 3). Tab-slot width is now responsive (passed in by the
// shell) so all four tabs always fit — evenly shrinking, never dropping — from 320dp up
// (P0-2). Slots default to the max envelope for previews / standalone use.
private val BUBBLE = 40.dp

/**
 * Custom translucent floating pill nav bar (PLAN.md §4, V2-SPEC items 1-3). A single
 * traveling circle ("the bubble") sits behind the icon row and slides to the selected
 * tab with a decoupled arrival sway (trampoline, sideways). No Material components; the
 * old per-tab blurred glow is gone. The "+" now lives in a bottom-right FAB (AppShell).
 *
 * [slotWidth] is the per-tab width the shell computed from the screen width; the bubble
 * travel math is derived from it so the traveling circle always lands under each glyph.
 */
@Composable
fun AuraNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    slotWidth: Dp = NavTabSlotMax
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAuraHaptics()

    val slotPx = with(density) { slotWidth.toPx() }
    val bubbleRadiusPx = with(density) { BUBBLE.toPx() } / 2f
    // Tab centre X relative to the pill's inner (post-padding) content box.
    fun centerFor(i: Int) = i * slotPx + slotPx / 2f

    // Pin the pill to exactly its content (padding + four equal slots) so it never
    // stretches wider than the tabs and leaves dead space that the last tab's touch
    // target absorbs — at ultra-narrow widths that dead space pushed the icons left of
    // centre (P0-2). With this, all four tabs fill the pill evenly and it centres cleanly.
    val pillWidth = NavPillHPadding * 2 + slotWidth * NavTab.entries.size

    val selIndex = NavTab.entries.indexOf(selected)
    val bubbleX = remember { Animatable(centerFor(selIndex)) }
    val swayX = remember { Animatable(0f) }
    var prevIndex by remember { mutableStateOf(selIndex) }

    // Travel + sway are decoupled Animatables on the same value → interruption-safe.
    LaunchedEffect(selected) {
        val from = prevIndex
        prevIndex = selIndex
        val target = centerFor(selIndex)
        val dir = sign((selIndex - from).toFloat())
        if (!reduceMotion && dir != 0f) {
            launch {
                swayX.snapTo(0f)
                swayX.animateTo(0f, MotionTokens.NavSway, initialVelocity = dir * 800f)
            }
        }
        // Suspends until the bubble actually settles; interruption cancels before this.
        bubbleX.animateTo(target, MotionTokens.navTravel(reduceMotion))
        // Landing haptic on settle — only for real travel, not first-frame / re-mount.
        if (dir != 0f) haptics.tick() // V2-SPEC item 13
    }

    Box(
        modifier = modifier
            .height(64.dp)
            .width(pillWidth)
            // A stable 92% opacity keeps the pill translucent but readable over content.
            .background(
                tokens.colors.surfaceTranslucent.copy(alpha = 0.92f),
                RoundedCornerShape(tokens.radii.pill)
            )
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(horizontal = NavPillHPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        // Bubble layer — drawn first so it stays behind the glyphs.
        Box(
            modifier = Modifier
                .size(BUBBLE)
                .graphicsLayer {
                    val stretch = if (reduceMotion) 0f
                    else (abs(bubbleX.velocity) / 4000f).coerceIn(0f, 0.12f)
                    translationX = bubbleX.value + swayX.value - bubbleRadiusPx
                    // Track the glyphs, which ride 6dp high to make room for the labels.
                    translationY = -6.dp.toPx()
                    scaleX = 1f + stretch
                    scaleY = 1f - stretch * 0.6f
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .clip(CircleShape)
                .background(tokens.colors.accent.copy(alpha = 0.12f))
                .border(1.dp, tokens.colors.textPrimary.copy(alpha = 0.12f), CircleShape)
        )

        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    slotWidth = slotWidth,
                    onClick = { onSelect(tab) }
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: NavTab,
    selected: Boolean,
    slotWidth: Dp,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAuraHaptics()
    // Selected glyph tints to accent (V2-SPEC item 1); crossfade, no per-frame relayout.
    val iconColor by animateColorAsState(
        if (selected) tokens.colors.accent else tokens.colors.textSecondary,
        MotionTokens.fast(reduceMotion),
        label = "navIconColor"
    )
    val interaction = remembered()
    var showHelp by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(slotWidth)
            .fillMaxHeight()
            .semantics {
                this.selected = selected
                contentDescription = tab.label
                onClick(label = "Open ${tab.label}") { onClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(interaction)
                .pointerInput(tab) {
                    detectTapGestures(
                        onPress = { position ->
                            val press = PressInteraction.Press(position)
                            interaction.emit(press)
                            val released = tryAwaitRelease()
                            showHelp = false
                            interaction.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press)
                            )
                        },
                        onLongPress = {
                            showHelp = true
                            haptics.tick()
                        },
                        // A completed long press suppresses onTap, so release never
                        // navigates while the help tooltip is being shown.
                        onTap = { onClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Icon rides slightly high so the always-on label fits beneath it inside the
            // 64dp pill — primary navigation must never require a long-press to learn
            // (council B12). The traveling bubble stays centred behind the glyph.
            TabGlyph(
                icon = tab.icon,
                color = iconColor,
                modifier = Modifier
                    .offset(y = (-6).dp)
                    .size(22.dp)
            )
            BasicText(
                text = tab.label,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 9.dp),
                style = AuraType.label.copy(fontSize = 9.sp, color = iconColor),
                maxLines = 1
            )
        }

        if (showHelp) {
            BasicText(
                text = tab.label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-38).dp)
                    .background(
                        tokens.colors.surface.copy(alpha = 0.98f),
                        RoundedCornerShape(tokens.radii.sm)
                    )
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = com.fadghost.notesapp.ui.theme.AuraType.label.copy(
                    color = tokens.colors.textPrimary
                )
            )
        }
    }
}

@Composable
private fun remembered(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }
