package com.androidharness.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ring-style progress indicator.
 *
 * Uses a 6 dp stroke with rounded caps, the track in [MaterialTheme.colorScheme.surfaceContainerHigh]
 * and the filled arc in [MaterialTheme.colorScheme.primary].
 *
 * @param progress 0f..1f
 * @param diameter overall size of the ring
 * @param strokeWidth width of the arc stroke
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    strokeWidth: Dp = 6.dp,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "ring"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val ringColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.size(diameter)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val padding = strokeWidth.toPx() / 2
        val arcSize = Size(size.width - strokeWidth.toPx(), size.height - strokeWidth.toPx())
        val topLeft = Offset(padding, padding)

        // Track
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        // Filled arc
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = animatedProgress * 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}
