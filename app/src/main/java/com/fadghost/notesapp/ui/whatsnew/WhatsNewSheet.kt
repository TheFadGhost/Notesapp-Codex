package com.fadghost.notesapp.ui.whatsnew

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import kotlinx.coroutines.launch

/**
 * Host that shows the post-update "What's new" sheet (PLAN.md §13) when the gating
 * ViewModel has content for the current version. No-op otherwise.
 */
@Composable
fun WhatsNewHost(viewModel: WhatsNewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val whatsNew = state ?: return
    WhatsNewSheet(
        lines = whatsNew.lines,
        onDismiss = viewModel::dismiss
    )
}

@Composable
private fun WhatsNewSheet(
    lines: List<ChangelogLine>,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAuraHaptics()
    val sheetHeightPx = with(density) { 480.dp.toPx() }
    val offsetY = remember { Animatable(sheetHeightPx) }
    val scrimAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(220)) }
        offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
    }

    fun close() {
        haptics.confirm()
        scope.launch {
            launch { scrimAlpha.animateTo(0f, tween(180)) }
            offsetY.animateTo(sheetHeightPx, tween(220))
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { close() }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = offsetY.value }
                .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(
                    1.dp,
                    tokens.colors.outline,
                    RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg)
                )
                .padding(bottom = 28.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textSecondary.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AuraGlyph(Glyph.SPARKLE, tokens.colors.accent, Modifier.size(24.dp))
                BasicText("What's new", style = AuraType.title.copy(color = tokens.colors.textPrimary))
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                lines.forEach { line -> ChangelogRow(line) }
            }
            Spacer(Modifier.height(20.dp))
            val gotItInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(gotItInteraction, tint = true)
                    .background(tokens.colors.accent)
                    .clickable(
                        interactionSource = gotItInteraction,
                        indication = null
                    ) { close() }
                    .semantics { contentDescription = "Got it, dismiss what's new" }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    "Got it",
                    style = AuraType.body.copy(color = tokens.colors.background, textAlign = TextAlign.Center)
                )
            }
        }
    }
}

@Composable
private fun ChangelogRow(line: ChangelogLine) {
    val tokens = Aura.tokens
    when (line.kind) {
        ChangelogLine.Kind.TITLE -> {} // header row is rendered by the sheet chrome
        ChangelogLine.Kind.SECTION -> {
            Spacer(Modifier.height(6.dp))
            BasicText(line.text, style = AuraType.label.copy(color = tokens.colors.accent))
            Spacer(Modifier.height(6.dp))
        }
        ChangelogLine.Kind.BULLET -> Row(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textSecondary)
            )
            BasicText(line.text, style = AuraType.body.copy(color = tokens.colors.textPrimary))
        }
        ChangelogLine.Kind.BODY -> {
            BasicText(
                line.text,
                style = AuraType.body.copy(color = tokens.colors.textSecondary),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}
