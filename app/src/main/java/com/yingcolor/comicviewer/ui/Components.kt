package com.yingcolor.comicviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yingcolor.comicviewer.theme.expressiveSpring

/** Capsule button with the springy press feedback used throughout M3 Expressive. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionScale = LocalMotionScale.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (motionScale == 0f) tween(0) else expressiveSpring(),
        label = "press"
    )
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = CircleShape,
        color = if (filled) scheme.primary else scheme.secondaryContainer,
        contentColor = if (filled) scheme.onPrimary else scheme.onSecondaryContainer
    ) {
        Box(
            modifier = Modifier
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(horizontal = 22.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * A connected button group whose visual state comes from one continuous
 * position. As the position slides between slots, every segment interpolates
 * its fill, label colour and corner radius at the same time, so the highlight
 * travels instead of two blocks fading against each other. Only the press
 * (a genuinely discrete event) gets its own short ease-out tween.
 */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val position by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = motionTween<Float>(MotionSpec.MEDIUM, MotionSpec.Emphasized),
        label = "segmentPosition"
    )
    Row(
        modifier = modifier
            .background(scheme.surfaceContainerHigh, CircleShape)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selection = selectionOf(position, index)
            val interaction = remember { MutableInteractionSource() }
            val isPressed by interaction.collectIsPressedAsState()
            val press by animateFloatAsState(
                targetValue = if (isPressed) 1f else 0f,
                animationSpec = motionTween<Float>(MotionSpec.SHORT, MotionSpec.EaseOutCubic),
                label = "segmentPress$index"
            )
            val restingCorner = 8f + 12f * selection
            val corner = restingCorner + (4f - restingCorner) * press * (1f - selection)
            val fill = lerp(scheme.surfaceContainerHigh, scheme.primary, selection)
            val labelColor = lerp(scheme.onSurfaceVariant, scheme.onPrimary, selection)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .background(fill, RoundedCornerShape(corner.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(index) }
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 10.dp)
    )
}

/**
 * Wrapping chip group. The zoom list has seven entries, which is unreadable as
 * equal-width segments on a phone; chips wrap onto as many lines as needed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                shape = CircleShape,
                color = if (active) scheme.primary else scheme.surfaceContainerHigh,
                contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.clickable { onSelect(index) }
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
