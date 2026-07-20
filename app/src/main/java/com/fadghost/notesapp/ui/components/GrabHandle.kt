package com.fadghost.notesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura

/**
 * The one grab handle. Only draggable sheets get one — a handle is a promise
 * the surface can be pulled, so non-draggable sheets (the AI trio) show none.
 * 40x5 at outline weight, everywhere, so the promise always looks the same.
 */
@Composable
fun GrabHandle(modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    Box(
        modifier
            .width(40.dp)
            .height(5.dp)
            .background(tokens.colors.outline, RoundedCornerShape(2.5.dp))
    )
}
