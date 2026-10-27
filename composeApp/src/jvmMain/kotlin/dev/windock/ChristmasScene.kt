package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Composable
internal fun ChristmasScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    Canvas(modifier.fillMaxSize()) {
        val u = 1.dp.toPx()
        val snowColors = listOf(Color.White, Color(0xFFD9F1FF), Color(0xFFBCE4FA))
        repeat(34) { index ->
            // Bigger flakes fall faster, which reads as depth.
            val big = index % 5 == 0
            val speed = (12 + (index % 4) * 4 + if (big) 10 else 0) * u
            val x = ((index * 79 + 23) % size.width.toInt().coerceAtLeast(1)).toFloat()
            val y = (index * 37 * u + time * speed) % size.height
            val drift = sin(time * 1.1f + index) * 3 * u
            drawCircle(snowColors[index % snowColors.size].copy(alpha = .72f), if (big) 1.8f * u else 1.1f * u, Offset(x + drift, y))
        }

        fun snowLine(lift: Float) = Path().apply {
            // A low drift: the snug bar keeps its running-app dots just above the bottom edge.
            moveTo(0f, size.height - (3 + lift) * u)
            cubicTo(size.width * .16f, size.height - (6 + lift) * u, size.width * .28f, size.height - (2 + lift) * u, size.width * .46f, size.height - (4 + lift) * u)
            cubicTo(size.width * .67f, size.height - (6.5f + lift) * u, size.width * .82f, size.height - (2 + lift) * u, size.width, size.height - (4.5f + lift) * u)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(snowLine(0f), Color(0xFF9CC7DF).copy(alpha = .72f))
        val snowCap = snowLine(1f)
        drawPath(snowCap, Color(0xFFF5FBFF).copy(alpha = .96f))
        drawPath(snowCap, Color.White.copy(alpha = .9f), style = Stroke(1 * u))

        drawFairyLights(time)

        // Hops back and forth along the dock, easing into each turn.
        val leg = time / 24f % 2f
        val facing = if (leg < 1f) 1f else -1f
        val travel = smooth(if (leg < 1f) leg else 2f - leg)
        val x = mix(18 * u, size.width - 18 * u, travel)
        drawSnowman(Offset(x, size.height - 3 * u), facing, time)
    }
}

internal val BulbColors = listOf(Color(0xFFFF4D4D), Color(0xFFFFC93C), Color(0xFF3DDC84), Color(0xFF4DA8FF), Color(0xFFC77DFF))
private val Wire = Color(0xFF0E2B22)

/** The blink programs of a classic pisca-pisca controller; each runs for a few seconds before the next. */
private enum class BlinkMode(val stepSeconds: Float) { ALTERNATE(.55f), CHASE(.13f), FLASH(.32f), TWINKLE(.28f) }

private fun bulbOn(mode: BlinkMode, index: Int, step: Long): Boolean = when (mode) {
    BlinkMode.ALTERNATE -> (index + step) % 2 == 0L
    // Two lit, two dark, marching along the string.
    BlinkMode.CHASE -> ((index - step) % 4 + 4) % 4 < 2
    // Two quick flashes of the whole string, then a pause.
    BlinkMode.FLASH -> step % 6 == 0L || step % 6 == 2L
    BlinkMode.TWINKLE -> hash01(index * 7.13f + step * 3.71f) > .4f
}

/** 0..1 brightness; switching takes ~70ms like an incandescent filament instead of a hard digital cut. */
internal fun bulbLevel(time: Float, index: Int): Float {
    val modes = BlinkMode.entries
    val mode = modes[(floor(time / 7f).toInt()) % modes.size]
    val step = floor(time / mode.stepSeconds).toLong()
    val sinceSwitch = time - step * mode.stepSeconds
    val now = if (bulbOn(mode, index, step)) 1f else 0f
    val before = if (bulbOn(mode, index, step - 1)) 1f else 0f
    return mix(before, now, smooth(sinceSwitch / .07f))
}

private fun DrawScope.drawFairyLights(time: Float) {
    val u = 1.dp.toPx()
    val hooks = 8
    val top = 3 * u
    val sag = 7 * u
    // The wire droops between hooks and sways gently.
    fun wireY(x: Float): Float {
        val span = size.width / hooks
        val local = (x % span) / span
        return top + sag * 4f * local * (1f - local) + sin(time * 1.1f + x / span) * .4f * u
    }
    val wire = Path().apply {
        moveTo(0f, wireY(0f))
        var x = 0f
        while (x <= size.width) { lineTo(x, wireY(x)); x += 3 * u }
    }
    drawPath(wire, Wire, style = Stroke(1.2f * u))
    repeat(hooks + 1) { k -> drawCircle(Wire, 1.3f * u, Offset(size.width * k / hooks, top)) }

    val count = 24
    // Halos first so neighbouring glows never paint over a bulb.
    val bulbs = (0 until count).map { index ->
        val x = size.width * (index + .5f) / count
        val level = bulbLevel(time, index) * (.94f + .06f * sin(time * 37f + index))
        Triple(Offset(x, wireY(x)), BulbColors[index % BulbColors.size], level)
    }
    bulbs.forEach { (anchor, color, level) ->
        if (level > .02f) {
            val center = anchor + Offset(0f, 6 * u)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .55f * level), color.copy(alpha = 0f)), center, 11 * u), 11 * u, center)
        }
    }
    bulbs.forEachIndexed { index, (anchor, color, level) ->
        rotate(sin(time * 1.4f + index * .9f) * 7f, anchor) {
            drawRect(Wire, Offset(anchor.x - 1.1f * u, anchor.y), Size(2.2f * u, 2.6f * u))
            val top = anchor.y + 2.3f * u
            val bulb = Path().apply {
                moveTo(anchor.x - 1.3f * u, top)
                cubicTo(anchor.x - 2.4f * u, top + 1.5f * u, anchor.x - 1.9f * u, top + 4 * u, anchor.x, top + 5.4f * u)
                cubicTo(anchor.x + 1.9f * u, top + 4 * u, anchor.x + 2.4f * u, top + 1.5f * u, anchor.x + 1.3f * u, top)
                close()
            }
            // Off: dark tinted glass. On: hot white core fading into the bulb colour.
            val glass = lerp(lerp(color, Color.Black, .55f), color, level)
            drawPath(bulb, glass)
            if (level > .02f) {
                drawPath(bulb, Brush.radialGradient(listOf(Color.White.copy(alpha = .85f * level), color.copy(alpha = 0f)),
                    Offset(anchor.x, top + 2.8f * u), 2.6f * u))
            }
            drawCircle(Color.White.copy(alpha = .25f + .35f * level), .5f * u, Offset(anchor.x - .8f * u, top + 1.4f * u))
        }
    }
}

/** Snowman standing on [feet], facing right when [facing] is 1 and left when -1. */
private fun DrawScope.drawSnowman(feet: Offset, facing: Float, time: Float) {
    val u = 1.dp.toPx()
    val f = facing
    // Hop cycle: squash on landing/take-off, then a short arc in the air.
    val hop = time / .95f % 1f
    val air = if (hop > .25f) sin((hop - .25f) / .75f * PI.toFloat()) else 0f
    val squash = if (hop < .25f) sin(hop / .25f * PI.toFloat()) * .13f else 0f
    val base = Offset(feet.x, feet.y - air * 5 * u)

    val shadowW = 18 * u * (1f - air * .3f)
    drawOval(Color(0xFF7FA9C4).copy(alpha = .35f * (1f - air * .5f)), Offset(feet.x - shadowW / 2, feet.y - 1.5f * u), Size(shadowW, 3 * u))

    withTransform({ scale(1f + squash * .8f, 1f - squash + air * .04f, base) }) {
        val bottom = Offset(base.x, base.y - 9 * u)
        val middle = Offset(base.x, bottom.y - 11.5f * u)
        val head = Offset(base.x, middle.y - 9.5f * u)
        val wave = (time % 7f).let { smooth(it / .3f) * (1f - smooth((it - 1.5f) / .3f)) }

        drawStickArm(middle + Offset(-f * 5 * u, -1 * u), -25f + sin(time * 2f) * 8f, -f)
        drawSnowball(bottom, 9 * u)
        drawSnowball(middle, 6.5f * u)
        listOf(middle + Offset(f * 1.2f * u, -2 * u), middle + Offset(f * 1.4f * u, 1.8f * u), bottom + Offset(f * 1.6f * u, -4.5f * u))
            .forEach { drawCoal(it, .9f * u) }

        // Scarf tail hangs behind and flutters with each hop.
        val flutter = sin(time * 9f) * 1.2f * u + air * 1.5f * u
        val knot = middle + Offset(-f * 2.5f * u, -5.6f * u)
        val tailMid = knot + Offset(-f * 3 * u, 4 * u + flutter)
        val tailEnd = tailMid + Offset(-f * 2.5f * u + flutter * .5f, 3 * u)
        val tail = Path().apply { moveTo(knot.x, knot.y); quadraticTo(tailMid.x, tailMid.y - flutter, tailEnd.x, tailEnd.y) }
        drawPath(tail, Color(0xFFC83F4A), style = Stroke(2.4f * u))
        repeat(3) { k -> drawLine(Color(0xFFE8E2D0), tailEnd + Offset((k - 1) * .8f * u, 0f), tailEnd + Offset((k - 1) * .8f * u, 1.3f * u), .5f * u) }

        drawSnowball(head, 4.8f * u)
        drawRoundRect(Color(0xFFC83F4A), Offset(middle.x - 5 * u, middle.y - 7 * u), Size(10 * u, 2.6f * u), CornerRadius(1.3f * u))
        repeat(3) { k -> drawLine(Color(0xFFF5E6C8), Offset(middle.x + (k * 3 - 3.5f) * u, middle.y - 7 * u),
            Offset(middle.x + (k * 3 - 3.5f) * u, middle.y - 4.4f * u), .7f * u) }

        val face = head + Offset(f * 1.3f * u, 0f)
        val blinkPhase = (time + 1.3f) % 4.1f
        val blink = if (blinkPhase < .15f) 1f - sin(blinkPhase / .15f * PI.toFloat()) * .9f else 1f
        for (side in intArrayOf(-1, 1)) {
            drawOval(Color(0xFF243141), face + Offset(side * 1.6f * u - .75f * u, -1.8f * u * blink), Size(1.5f * u, 1.6f * u * blink))
        }
        val carrot = Path().apply {
            moveTo(face.x, face.y - .2f * u)
            lineTo(face.x + f * 5.5f * u, face.y + 1 * u)
            lineTo(face.x, face.y + 1.4f * u)
            close()
        }
        drawPath(carrot, Brush.horizontalGradient(listOf(Color(0xFFFF8A2A), Color(0xFFFFB45E)), face.x, face.x + f * 5.5f * u))
        drawLine(Color(0xFFD9661B), face + Offset(f * 2 * u, .2f * u), face + Offset(f * 2 * u, 1.1f * u), .4f * u)
        for (k in 0..4) {
            val a = (25f + k * 32.5f) * PI.toFloat() / 180f
            drawCircle(Color(0xFF243141), .38f * u, face + Offset(-f * .3f * u + cos(a) * 2.2f * u, 1 * u + sin(a) * 1.8f * u))
        }

        // The hat rocks back on take-off and settles on landing.
        val hatBase = head + Offset(0f, -4.3f * u)
        rotate(-f * (6f + squash * 30f) + air * f * 4f, hatBase) {
            val hat = Color(0xFF2B3446)
            drawRoundRect(hat, Offset(hatBase.x - 5.5f * u, hatBase.y - 1.4f * u), Size(11 * u, 1.8f * u), CornerRadius(.9f * u))
            drawRoundRect(hat, Offset(hatBase.x - 3.6f * u, hatBase.y - 8.5f * u), Size(7.2f * u, 7.4f * u), CornerRadius(1 * u))
            drawRect(Color(0xFFC83F4A), Offset(hatBase.x - 3.6f * u, hatBase.y - 3.2f * u), Size(7.2f * u, 1.6f * u))
            drawLine(Color.White.copy(alpha = .25f), Offset(hatBase.x - 2.6f * u, hatBase.y - 7.6f * u),
                Offset(hatBase.x - 2.6f * u, hatBase.y - 3.8f * u), .6f * u, StrokeCap.Round)
            val holly = Offset(hatBase.x + f * 2.2f * u, hatBase.y - 2.6f * u)
            drawOval(Color(0xFF2E8B57), holly + Offset(-2.4f * u, -.7f * u), Size(2.4f * u, 1.3f * u))
            drawOval(Color(0xFF2E8B57), holly + Offset(0f, -.7f * u), Size(2.4f * u, 1.3f * u))
            drawCircle(Color(0xFFE23B3B), .8f * u, holly)
        }

        // Front arm swings with the hops and periodically waves hello.
        val frontAngle = mix(-15f + sin(time * 2f + 1f) * 8f, -70f + sin(time * 14f) * 22f, wave)
        drawStickArm(middle + Offset(f * 5 * u, -1 * u), frontAngle, f)
    }
}

private fun DrawScope.drawSnowball(center: Offset, radius: Float) {
    // Lit from the upper left, with a cool blue shadow on the far side.
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFF2F8FD), Color(0xFFC9DDEB)),
        center + Offset(-radius * .35f, -radius * .4f), radius * 1.45f), radius, center)
    drawCircle(Color(0xFFB8D3E4), radius, center, style = Stroke(.7f.dp.toPx()))
}

private fun DrawScope.drawCoal(center: Offset, radius: Float) {
    drawCircle(Color(0xFF2A2F38), radius, center)
    drawCircle(Color.White.copy(alpha = .35f), radius * .35f, center + Offset(-radius * .3f, -radius * .3f))
}

private fun DrawScope.drawStickArm(shoulder: Offset, degrees: Float, dir: Float) {
    val u = 1.dp.toPx()
    val wood = Color(0xFF795B43)
    val hand = along(shoulder, degrees, 10 * u, dir)
    drawLine(wood, shoulder, hand, 1.6f * u, StrokeCap.Round)
    val knot = along(shoulder, degrees, 6.5f * u, dir)
    drawLine(wood, knot, along(knot, degrees - 40f, 3 * u, dir), 1 * u, StrokeCap.Round)
    for (k in intArrayOf(-1, 1)) drawLine(wood, hand, along(hand, degrees + k * 32f, 2.2f * u, dir), .9f * u, StrokeCap.Round)
}
