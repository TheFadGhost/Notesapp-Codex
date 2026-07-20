package com.fadghost.notesapp.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.PlainChip
import com.fadghost.notesapp.ui.components.TagChip
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import kotlin.math.roundToInt

/** Trim and collapse whitespace before a tag name reaches persistence. */
internal fun normalizeOrganizeName(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ")

/**
 * One tag-only assignment panel for the editor. It is positioned from the
 * measured trigger bounds, flips when needed, clamps to the window, and scrolls
 * within a bounded Aura card. Every selection applies immediately.
 */
@Composable
fun OrganizePanel(
    anchorBounds: Rect,
    allTags: List<Tag>,
    assignedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler { onDismiss() }
    val density = LocalDensity.current
    var tagInput by remember { mutableStateOf(TextFieldValue("")) }
    var colorIndex by remember { mutableIntStateOf(0) }

    val searchable = allTags.size > 8
    val normalizedTag = normalizeOrganizeName(tagInput.text)
    val visibleTags = remember(allTags, assignedTagIds, searchable, normalizedTag) {
        allTags
            .asSequence()
            .filter { !searchable || normalizedTag.isBlank() || it.name.contains(normalizedTag, ignoreCase = true) }
            .sortedWith(compareByDescending<Tag> { it.id in assignedTagIds }.thenBy { it.name.lowercase() })
            .toList()
    }
    val exactTagExists = allTags.any { it.name.equals(normalizedTag, ignoreCase = true) }
    val summary = if (assignedTagIds.isEmpty()) "No tags" else {
        "${assignedTagIds.size} ${if (assignedTagIds.size == 1) "tag" else "tags"}"
    }

    Popup(
        alignment = Alignment.TopStart,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false
        ),
        onDismissRequest = onDismiss
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val margin = 12.dp
            val gap = 8.dp
            val panelWidth = (maxWidth - margin * 2).coerceAtMost(340.dp).coerceAtLeast(220.dp)
            val panelMaxHeight = (maxHeight - margin * 2).coerceAtMost(460.dp).coerceAtLeast(220.dp)
            val rootWidthPx = with(density) { maxWidth.roundToPx() }
            val rootHeightPx = with(density) { maxHeight.roundToPx() }
            val panelWidthPx = with(density) { panelWidth.roundToPx() }
            val panelHeightPx = with(density) { panelMaxHeight.roundToPx() }
            val marginPx = with(density) { margin.roundToPx() }
            val gapPx = with(density) { gap.roundToPx() }
            val maxX = (rootWidthPx - panelWidthPx - marginPx).coerceAtLeast(marginPx)
            val x = (anchorBounds.right.roundToInt() - panelWidthPx).coerceIn(marginPx, maxX)
            val belowY = anchorBounds.bottom.roundToInt() + gapPx
            val availableBelow = rootHeightPx - marginPx - belowY
            val y = if (availableBelow >= with(density) { 220.dp.roundToPx() }) {
                belowY.coerceAtMost((rootHeightPx - marginPx - panelHeightPx).coerceAtLeast(marginPx))
            } else {
                (anchorBounds.top.roundToInt() - gapPx - panelHeightPx).coerceAtLeast(marginPx)
            }

            Column(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight)
                    .imePadding()
                    .auraFloatShadow(RoundedCornerShape(tokens.radii.lg))
                    .clip(RoundedCornerShape(tokens.radii.lg))
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                BasicText("Organize", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
                Spacer(Modifier.height(2.dp))
                BasicText(summary, style = AuraType.label.copy(color = tokens.colors.textSecondary))

                Spacer(Modifier.height(18.dp))
                SectionLabel("Tags")
                if (searchable) {
                    OrganizerField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = "Find or create a tag"
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (visibleTags.isEmpty()) {
                    BasicText(
                        if (allTags.isEmpty()) "No tags yet." else "No matching tags.",
                        style = AuraType.body.copy(color = tokens.colors.textSecondary)
                    )
                } else {
                    FlowChips {
                        visibleTags.forEach { tag ->
                            TagChip(
                                tag = tag,
                                selected = tag.id in assignedTagIds,
                                onClick = { onToggleTag(tag.id) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (searchable) {
                    if (normalizedTag.isNotBlank() && !exactTagExists) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ColorCycler(colorIndex) { colorIndex = (colorIndex + 1) % AuraAccents.palette.size }
                            PlainChip("Create “$normalizedTag”", selected = false) {
                                onCreateTag(normalizedTag, AuraAccents.palette[colorIndex].toArgb())
                                tagInput = TextFieldValue("")
                            }
                        }
                    }
                } else {
                    CreateRow(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = "New tag",
                        colorIndex = colorIndex,
                        onCycleColor = { colorIndex = (colorIndex + 1) % AuraAccents.palette.size },
                        onAdd = {
                            val name = normalizeOrganizeName(tagInput.text)
                            if (name.isNotBlank() && allTags.none { it.name.equals(name, ignoreCase = true) }) {
                                onCreateTag(name, AuraAccents.palette[colorIndex].toArgb())
                                tagInput = TextFieldValue("")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text.uppercase(),
        style = AuraType.labelSm.copy(color = Aura.tokens.colors.textSecondary),
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun CreateRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    colorIndex: Int? = null,
    onCycleColor: (() -> Unit)? = null,
    onAdd: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (colorIndex != null && onCycleColor != null) ColorCycler(colorIndex, onCycleColor)
        OrganizerField(value, onValueChange, placeholder, Modifier.weight(1f))
        AddButton(enabled = normalizeOrganizeName(value.text).isNotBlank(), onClick = onAdd)
    }
}

@Composable
private fun ColorCycler(colorIndex: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Change color" }
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(AuraAccents.palette[colorIndex]))
    }
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Create tag" }
            .auraPress(interaction)
            .background(tokens.colors.accent.copy(alpha = if (enabled) 0.16f else 0.06f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(
            Glyph.PLUS,
            if (enabled) tokens.colors.accent else tokens.colors.textSecondary,
            Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OrganizerField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.text.isEmpty()) {
            BasicText(placeholder, style = AuraType.body.copy(color = tokens.colors.textSecondary))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
