package com.sasch.cameragps.sharednew.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun TransmissionDot(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    /** Color while running; blue for a camera that is switched off in standby. */
    runningColor: Color = ReceivingGreen,
    /** Color while not running; amber for a camera connected with location sync off. */
    idleColor: Color = AwayRed,
) {
    Box(
        modifier = modifier
            .size(12.dp), // fixed layout size so nothing moves
        contentAlignment = Alignment.Center
    ) {
        if (!isRunning) {  // Static dot when not sending
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = idleColor,
                        shape = CircleShape
                    )
            )
            return
        }

        val infiniteTransition = rememberInfiniteTransition(label = "txDot")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "txDotScale"
        )

        Box(
            modifier = modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    color = runningColor,
                    shape = CircleShape
                )
        )
    }
}

// Camera status colors, the same as the status widget's.

/** Receiving the location. */
val ReceivingGreen = Color(0xFF2E9E4A)

/** A camera switched off in standby that still receives the location. */
val StandbyBlue = Color(0xFF1E88E5)

/** Connecting, or connected without taking the location (location sync off). */
val AttentionAmber = Color(0xFFE8A317)

/** Not connected. */
val AwayRed = Color(0xFFD93025)

/** Disabled in the app. */
val DisabledGrey = Color(0xFF9E9E9E)