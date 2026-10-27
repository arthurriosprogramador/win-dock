package dev.windock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The handle's window, and the zone around it where the pointer brings the dock back. */
internal val HandleWindowWidth = 300.dp
internal val HandleWindowHeight = 64.dp
internal val HandleZoneWidth = 200.dp
internal val HandleZoneHeight = 14.dp

/**
 * The sign that the dock is tucked below the screen edge: a pill like a phone's home indicator, with a chevron
 * that nudges upwards now and then. With [hint], a tag above it explains how to bring the dock back.
 */
@Composable
internal fun DockHandle(hint: Boolean, modifier: Modifier = Modifier) {
    // Rises into place when the dock has just gone, so the eye follows it down to the edge.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(380, delayMillis = 120, easing = FastOutSlowInEasing)) }
    val nudge by rememberInfiniteTransition(label = "handle").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "handle-nudge",
    )
    // A short hop at the start of each cycle, then rest.
    val hop = if (nudge < .25f) kotlin.math.sin(nudge / .25f * Math.PI).toFloat() else 0f

    Column(
        modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1 - appear.value) * 10.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        AnimatedVisibility(hint, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier.padding(bottom = 6.dp).shadow(6.dp, RoundedCornerShape(10.dp))
                    .background(Color(0xF02B2E36), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Leve o mouse até aqui para mostrar o dock", color = Color(0xFFF3F4F7), fontSize = 12.sp)
            }
        }
        // The chevron: a little arrow pointing up, hopping to say "it's up here".
        Canvas(Modifier.size(width = 18.dp, height = 8.dp).graphicsLayer { translationY = -hop * 3.dp.toPx() }) {
            val stroke = 2.dp.toPx()
            val outline = Color.White.copy(alpha = .85f)
            val ink = Color(0xFF3A3D45)
            for ((color, width) in listOf(outline to stroke + 2.dp.toPx(), ink to stroke)) {
                drawLine(color, Offset(2.dp.toPx(), size.height - 1.dp.toPx()), Offset(size.width / 2, 1.dp.toPx()), width, StrokeCap.Round)
                drawLine(color, Offset(size.width / 2, 1.dp.toPx()), Offset(size.width - 2.dp.toPx(), size.height - 1.dp.toPx()), width, StrokeCap.Round)
            }
        }
        // The pill: dark with a light rim, so it reads on any window or wallpaper.
        Box(
            Modifier.padding(top = 3.dp, bottom = 4.dp).width(120.dp).height(6.dp)
                .shadow(3.dp, CircleShape)
                .background(Color(0xE6474B55), CircleShape)
                .border(1.dp, Color.White.copy(alpha = .75f), CircleShape),
        )
    }
}
