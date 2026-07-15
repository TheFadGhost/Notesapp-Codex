package com.fadghost.notesapp.ui.attach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.editor.MarkdownVisualTransformation
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlin.math.abs

/** One rendered attachment chip in transformed (visual) coordinates. */
data class ChipToken(
    val id: Long,
    val transStart: Int,
    val transEnd: Int,
    val present: Boolean,
    val isImage: Boolean
)

/**
 * The editor body's display form (M-A): markdown styling PLUS each `[[att:<id>]]` token
 * replaced by a subtle-surface chip showing the file name in link-blue. Carries the
 * forward offset map so the checkbox / audio overlays (which work in source offsets)
 * can be translated into the transformed layout.
 */
class DisplayBody(
    val transformed: TransformedText,
    val chips: List<ChipToken>,
    private val forward: IntArray
) {
    /** Source offset -> transformed offset (for positioning source-anchored overlays). */
    fun mapOffset(sourceOffset: Int): Int = forward[sourceOffset.coerceIn(0, forward.size - 1)]
    fun unmapOffset(transformedOffset: Int): Int = transformed.offsetMapping
        .transformedToOriginal(transformedOffset.coerceIn(0, transformed.text.length))
}

/**
 * Builds the [DisplayBody] for a note body. The two leading spaces in each chip label
 * reserve room for the overlaid image/paperclip glyph ([AttachmentChipOverlay]).
 */
object AttachmentBodyBuilder {

    val TOKEN = Regex("""\[\[att:(\d+)]]""")

    fun build(
        source: String,
        attachments: Map<Long, Attachment>,
        textColor: Color,
        markerColor: Color,
        accent: Color,
        linkBlue: Color,
        chipSurface: Color,
        missing: Color,
        baseSize: TextUnit = 15.sp
    ): DisplayBody {
        val md = MarkdownVisualTransformation(textColor, markerColor, accent, baseSize)
            .annotate(source, textColor, markerColor, accent, baseSize)

        val fwd = IntArray(source.length + 1)
        val sb = StringBuilder()
        val chips = ArrayList<ChipToken>()
        var i = 0
        while (i < source.length) {
            val m = TOKEN.matchAt(source, i)
            if (m != null) {
                val id = m.groupValues[1].toLongOrNull() ?: -1L
                val att = attachments[id]
                val name = att?.displayName ?: "missing attachment"
                // Leading ideographic space (~1em) + thin space reserve room for the
                // overlaid glyph so it never covers the first character of the name.
                val label = "　 $name"
                val ts = sb.length
                for (k in i..m.range.last) fwd[k] = ts
                sb.append(label)
                chips += ChipToken(id, ts, sb.length, present = att != null, isImage = att?.isImage == true)
                i = m.range.last + 1
            } else {
                fwd[i] = sb.length
                sb.append(source[i])
                i++
            }
        }
        fwd[source.length] = sb.length

        val display = sb.toString()
        val builder = AnnotatedString.Builder(display)
        for (sp in md.spanStyles) {
            val s = fwd[sp.start.coerceIn(0, source.length)]
            val e = fwd[sp.end.coerceIn(0, source.length)]
            if (e > s) builder.addStyle(sp.item, s, e)
        }
        for (chip in chips) {
            builder.addStyle(
                SpanStyle(color = if (chip.present) linkBlue else missing, background = chipSurface),
                chip.transStart,
                chip.transEnd
            )
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = fwd[offset.coerceIn(0, source.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, display.length)
                var lo = 0
                var hi = source.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (fwd[mid] <= t) lo = mid else hi = mid - 1
                }
                return lo
            }
        }
        return DisplayBody(TransformedText(builder.toAnnotatedString(), mapping), chips, fwd)
    }
}

/**
 * Draws the chip glyph + a tap target over each `[[att:<id>]]` chip, mirroring the
 * checkbox/audio overlay technique. The chip's coloured background + file name come
 * from the visual transformation; this layer adds the leading glyph and opens the
 * popover on tap (press feedback via [auraPress]).
 */
@Composable
fun AttachmentChipOverlay(
    chips: List<ChipToken>,
    layout: TextLayoutResult?,
    onOpen: (Long) -> Unit,
    onMove: (Long, Int) -> Unit = { _, _ -> }
) {
    val lay = layout ?: return
    val density = LocalDensity.current
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    chips.forEach { chip ->
        val start = runCatching { lay.getBoundingBox(chip.transStart) }.getOrNull() ?: return@forEach
        val endIdx = (chip.transEnd - 1).coerceAtLeast(chip.transStart)
        val end = runCatching { lay.getBoundingBox(endIdx) }.getOrNull() ?: start
        val sameLine = abs(start.top - end.top) < 1f
        val glyphColor = if (chip.present) tokens.colors.linkBlue else tokens.colors.danger
        val interaction = remember(chip.id) { MutableInteractionSource() }
        var dragDelta by remember(chip.id) { mutableStateOf(Offset.Zero) }
        var dragging by remember(chip.id) { mutableStateOf(false) }
        var suppressClick by remember(chip.id) { mutableStateOf(false) }
        val visualX by animateFloatAsState(dragDelta.x, MotionTokens.medium(reduceMotion), label = "attachment drag x")
        val visualY by animateFloatAsState(dragDelta.y, MotionTokens.medium(reduceMotion), label = "attachment drag y")
        LaunchedEffect(dragging, suppressClick) {
            if (!dragging && suppressClick) { delay(180); suppressClick = false }
        }
        with(density) {
            val widthPx = if (sameLine) (end.right - start.left) else (lay.size.width - start.left)
            val heightDp = (start.bottom - start.top).toDp()
            Box(
                Modifier
                    .offset(x = start.left.toDp(), y = start.top.toDp())
                    .size(width = widthPx.toDp().coerceAtLeast(24.dp), height = heightDp)
                    .graphicsLayer {
                        translationX = visualX
                        translationY = visualY
                        scaleX = if (dragging) 1.08f else 1f
                        scaleY = if (dragging) 1.08f else 1f
                        alpha = if (dragging) 0.82f else 1f
                    }
                    .auraPress(interaction, tint = true)
                    .pointerInput(chip.id, lay) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = true; suppressClick = true; dragDelta = Offset.Zero },
                            onDragCancel = { dragging = false; dragDelta = Offset.Zero },
                            onDragEnd = {
                                val point = Offset(
                                    start.left + widthPx / 2f + dragDelta.x,
                                    start.top + (start.bottom - start.top) / 2f + dragDelta.y
                                )
                                onMove(chip.id, lay.getOffsetForPosition(point))
                                dragging = false
                                dragDelta = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDelta += amount
                            }
                        )
                    }
                    .clickable(interactionSource = interaction, indication = null) {
                        if (!suppressClick) onOpen(chip.id)
                    }
                    .semantics { contentDescription = if (chip.present) "Attachment" else "Missing attachment" },
                contentAlignment = Alignment.CenterStart
            ) {
                AuraGlyph(
                    if (chip.isImage) Glyph.IMAGE else Glyph.PAPERCLIP,
                    glyphColor,
                    Modifier
                        .padding(start = 1.dp)
                        .size(heightDp.coerceAtMost(16.dp))
                )
            }
        }
    }
}
