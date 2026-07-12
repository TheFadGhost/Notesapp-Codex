package com.fadghost.notesapp.ui.attach

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private enum class Tool { PEN, HIGHLIGHTER, ERASER, TEXT }

private data class DrawnStroke(
    val points: List<Offset>,
    val color: Color,
    val widthPx: Float,
    val highlighter: Boolean,
    val eraser: Boolean
)

private data class TextItem(val id: Long, val text: String, val pos: Offset, val color: Color, val sizePx: Float)

private data class Snapshot(val strokes: List<DrawnStroke>, val texts: List<TextItem>)

/**
 * Image annotation editor (M-A part 6): pencil (3 widths), highlighter, eraser and a
 * tap-to-place / draggable text tool, over the original image. Colour palette = ink
 * (black/white) + the 8 Aura accents. Finger + stylus both drive the same drag path.
 * Undo/redo via whole-canvas snapshots. Saving rasterises a PNG and hands the bytes to
 * [onSave]; the caller stores it as a NEW attachment (annotatedOfId = original) and
 * repoints the note token, so the original is always preserved.
 */
@Composable
fun AnnotateScreen(
    attachment: Attachment,
    onCancel: () -> Unit,
    onSave: (ByteArray) -> Unit
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val base by produceState<Bitmap?>(initialValue = null, attachment.path) {
        value = withContext(Dispatchers.IO) { AttachmentImages.decodeDownsampled(attachment.path, 1600, 1600) }
    }

    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color.Black) }
    var widthDp by remember { mutableFloatStateOf(4f) } // 3 options: 2 / 4 / 9
    var strokes by remember { mutableStateOf(listOf<DrawnStroke>()) }
    var texts by remember { mutableStateOf(listOf<TextItem>()) }
    var current by remember { mutableStateOf<DrawnStroke?>(null) }
    val undo = remember { mutableStateListOf<Snapshot>() }
    val redo = remember { mutableStateListOf<Snapshot>() }
    var editing by remember { mutableStateOf<TextItem?>(null) }
    var contentPx by remember { mutableStateOf(IntOffset(0, 0)) } // width,height in px

    fun pushHistory() { undo.add(Snapshot(strokes, texts)); redo.clear() }
    fun doUndo() {
        if (undo.isEmpty()) return
        redo.add(Snapshot(strokes, texts))
        val s = undo.removeAt(undo.lastIndex); strokes = s.strokes; texts = s.texts
    }
    fun doRedo() {
        if (redo.isEmpty()) return
        undo.add(Snapshot(strokes, texts))
        val s = redo.removeAt(redo.lastIndex); strokes = s.strokes; texts = s.texts
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .statusBarsPadding()
    ) {
        // --- Top bar: cancel / undo / redo / done -------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopIcon(Glyph.CLOSE) { onCancel() }
            Spacer(Modifier.weight(1f))
            TopIcon(Glyph.UNDO, enabled = undo.isNotEmpty()) { doUndo() }
            Spacer(Modifier.width(4.dp))
            TopIcon(Glyph.REDO, enabled = redo.isNotEmpty()) { doRedo() }
            Spacer(Modifier.width(10.dp))
            DoneButton {
                val b = base
                if (b != null && contentPx.x > 0 && contentPx.y > 0) {
                    val bytes = rasterize(b, contentPx.x, contentPx.y, strokes, texts)
                    onSave(bytes)
                } else onCancel()
            }
        }

        // --- Drawing surface ----------------------------------------------------
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val b = base
            if (b == null) {
                BasicText("Opening…", style = AuraType.body.copy(color = tokens.colors.textSecondary))
            } else {
                val availW = with(density) { maxWidth.toPx() }
                val availH = with(density) { maxHeight.toPx() }
                val ar = b.width.toFloat() / b.height.toFloat()
                var cw = availW
                var ch = cw / ar
                if (ch > availH) { ch = availH; cw = ch * ar }
                val cwDp = with(density) { cw.toDp() }
                val chDp = with(density) { ch.toDp() }

                Box(
                    Modifier
                        .size(cwDp, chDp)
                        .onSizeChanged { contentPx = IntOffset(it.width, it.height) }
                        .clip(RoundedCornerShape(tokens.radii.sm))
                ) {
                    Image(
                        bitmap = remember(b) { b.asImageBitmap() },
                        contentDescription = attachment.displayName,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Stroke layer, offscreen so the eraser clears annotations only.
                    Canvas(
                        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    ) {
                        (strokes + listOfNotNull(current)).forEach { s ->
                            if (s.points.size < 2) return@forEach
                            val path = Path().apply {
                                moveTo(s.points.first().x, s.points.first().y)
                                for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y)
                            }
                            drawPath(
                                path = path,
                                color = if (s.eraser) Color.Black else s.color,
                                style = DrawStroke(width = s.widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                blendMode = if (s.eraser) androidx.compose.ui.graphics.BlendMode.Clear
                                else androidx.compose.ui.graphics.drawscope.DrawScope.DefaultBlendMode
                            )
                        }
                    }
                    // Drawing / text-placement gestures.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(tool, color, widthDp) {
                                if (tool == Tool.TEXT) {
                                    detectTapGestures(onTap = { pos ->
                                        pushHistory()
                                        val item = TextItem(
                                            id = System.nanoTime(),
                                            text = "",
                                            pos = pos,
                                            color = color,
                                            sizePx = with(density) { 22.sp.toPx() }
                                        )
                                        texts = texts + item
                                        editing = item
                                    })
                                } else {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            pushHistory()
                                            val wpx = with(density) { widthDp.dp.toPx() }
                                            current = DrawnStroke(
                                                points = listOf(pos),
                                                color = if (tool == Tool.HIGHLIGHTER) color.copy(alpha = 0.3f) else color,
                                                widthPx = if (tool == Tool.HIGHLIGHTER) wpx * 3.2f else wpx,
                                                highlighter = tool == Tool.HIGHLIGHTER,
                                                eraser = tool == Tool.ERASER
                                            )
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            current = current?.let { it.copy(points = it.points + change.position) }
                                        },
                                        onDragEnd = {
                                            current?.let { strokes = strokes + it }
                                            current = null
                                        }
                                    )
                                }
                            }
                    )
                    // Text items (draggable when the text tool is active; tap to edit).
                    texts.forEach { item ->
                        key(item.id, tool) {
                            TextOverlay(
                                item = item,
                                editable = tool == Tool.TEXT,
                                density = density,
                                onDragStart = { pushHistory() },
                                onMove = { delta ->
                                    texts = texts.map { if (it.id == item.id) it.copy(pos = it.pos + delta) else it }
                                },
                                onEdit = { editing = item }
                            )
                        }
                    }
                }
            }
        }

        // --- Tool + colour bar --------------------------------------------------
        ToolBar(
            tool = tool, onTool = { tool = it },
            widthDp = widthDp, onWidth = { widthDp = it },
            color = color, onColor = { color = it },
            modifier = Modifier.navigationBarsPadding()
        )
    }

    // Text input overlay.
    editing?.let { item ->
        TextInputCard(
            initial = item.text,
            onDone = { value ->
                texts = if (value.isBlank()) texts.filterNot { it.id == item.id }
                else texts.map { if (it.id == item.id) it.copy(text = value) else it }
                editing = null
            },
            onDismiss = {
                if (item.text.isBlank()) texts = texts.filterNot { it.id == item.id }
                editing = null
            }
        )
    }
}

@Composable
private fun TextOverlay(
    item: TextItem,
    editable: Boolean,
    density: Density,
    onDragStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onEdit: () -> Unit
) {
    val fontSizeSp = with(density) { item.sizePx.toSp() }
    Box(
        Modifier
            .offset { IntOffset(item.pos.x.toInt(), item.pos.y.toInt()) }
            .then(
                if (editable) Modifier.pointerInput(item.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, drag -> change.consume(); onMove(drag) }
                    )
                } else Modifier
            )
            .then(if (editable) Modifier.clickable(remember { MutableInteractionSource() }, null) { onEdit() } else Modifier)
            .padding(2.dp)
    ) {
        BasicText(
            item.text.ifBlank { "Text" },
            style = AuraType.body.copy(color = item.color, fontSize = fontSizeSp)
        )
    }
}

@Composable
private fun ToolBar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    widthDp: Float,
    onWidth: (Float) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Tools + widths.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Glyph.PENCIL, tool == Tool.PEN) { onTool(Tool.PEN) }
            ToolButton(Glyph.BOLD, tool == Tool.HIGHLIGHTER) { onTool(Tool.HIGHLIGHTER) }
            ToolButton(Glyph.CLOSE, tool == Tool.ERASER) { onTool(Tool.ERASER) }
            ToolButton(Glyph.HEADING, tool == Tool.TEXT) { onTool(Tool.TEXT) }
            Spacer(Modifier.weight(1f))
            if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER) {
                listOf(2f, 4f, 9f).forEach { w ->
                    WidthDot(w, selected = widthDp == w) { onWidth(w) }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Colour palette: ink (black/white) + 8 accents.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(Color.Black, Color.White) + AuraAccents.themeAccents).forEach { c ->
                Swatch(c, selected = color == c) { onColor(c) }
            }
        }
    }
}

@Composable
private fun ToolButton(glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(interaction, tint = true)
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, if (selected) tokens.colors.accent else tokens.colors.textSecondary, Modifier.size(22.dp))
    }
}

@Composable
private fun WidthDot(w: Float, selected: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .auraPress(interaction, tint = true)
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size((w + 4).dp).clip(CircleShape).background(tokens.colors.textPrimary))
    }
}

@Composable
private fun Swatch(c: Color, selected: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .auraPress(interaction)
            .background(c)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) tokens.colors.accent else tokens.colors.outline,
                shape = CircleShape
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Colour" }
    )
}

@Composable
private fun TopIcon(glyph: Glyph, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, if (enabled) tokens.colors.textPrimary else tokens.colors.outline, Modifier.size(20.dp))
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.accent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText("Done", style = AuraType.label.copy(color = tokens.colors.background))
    }
}

@Composable
private fun TextInputCard(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    var value by remember { mutableStateOf(initial) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = tokens.elevation.scrim))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(20.dp)
        ) {
            BasicText("Add text", style = AuraType.title.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radii.sm))
                    .background(tokens.colors.background)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isEmpty()) {
                    BasicText("Type here…", style = AuraType.body.copy(color = tokens.colors.textSecondary))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.fadghost.notesapp.ui.ai.SoftButton("Cancel", filled = false, onClick = onDismiss)
                Spacer(Modifier.width(10.dp))
                com.fadghost.notesapp.ui.ai.SoftButton("Add", filled = true, onClick = { onDone(value) })
            }
        }
    }
}

/**
 * Rasterise the base image + annotation strokes + text into a PNG. The stroke layer is
 * composited separately so the eraser (PorterDuff CLEAR) removes only annotations.
 */
private fun rasterize(
    base: Bitmap,
    wPx: Int,
    hPx: Int,
    strokes: List<DrawnStroke>,
    texts: List<TextItem>
): ByteArray {
    val out = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(base, null, android.graphics.Rect(0, 0, wPx, hPx), null)

    val annot = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
    val ac = android.graphics.Canvas(annot)
    strokes.forEach { s ->
        if (s.points.size < 2) return@forEach
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = s.widthPx
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = s.color.toArgb()
            if (s.eraser) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val path = android.graphics.Path().apply {
            moveTo(s.points.first().x, s.points.first().y)
            for (i in 1 until s.points.size) lineTo(s.points[i].x, s.points[i].y)
        }
        ac.drawPath(path, paint)
    }
    canvas.drawBitmap(annot, 0f, 0f, null)

    texts.filter { it.text.isNotBlank() }.forEach { t ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = t.color.toArgb()
            textSize = t.sizePx
        }
        canvas.drawText(t.text, t.pos.x + 2f, t.pos.y + t.sizePx, paint)
    }

    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bos)
    annot.recycle()
    out.recycle()
    return bos.toByteArray()
}
