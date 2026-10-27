package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val LeafColors = listOf(Color(0xFFF2B33D), Color(0xFFE8872E), Color(0xFFD0532A), Color(0xFFF5D25E), Color(0xFFA8642C))

/** A pumpkin patch under golden trees, with leaves drifting down across the whole dock. */
@Composable
internal fun AutumnScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    Canvas(modifier.fillMaxWidth().height(StageHeight)) {
        val u = 1.dp.toPx()
        val ground = 40 * u
        // Gusts come and go; leaves on the trees and in the air both feel them.
        val gust = max(0f, sin(time * .45f)).let { it * it * it * it }

        // Trees root on the grass line, not the bar, so their trunks never poke below it.
        val grassTop = ground - 3 * u
        drawAutumnTree(Offset(20 * u, grassTop), 1f, time, gust, seed = 1f)
        drawAutumnTree(Offset(size.width * .29f, grassTop), .9f, time, gust, seed = 3f)
        drawAutumnTree(Offset(size.width * .5f, grassTop), .95f, time, gust, seed = 5f)
        drawAutumnTree(Offset(size.width * .76f, grassTop), .85f, time, gust, seed = 7f)
        drawAutumnTree(Offset(size.width - 22 * u, grassTop), 1.05f, time, gust, seed = 9f)

        // Grass verge resting on the bar, with leaf litter.
        drawRect(Color(0xFF8C8A3E), Offset(0f, ground - 3 * u), Size(size.width, 3 * u))
        var x = 2 * u
        var i = 0
        while (x < size.width) {
            val r = hash01(i * 1.7f)
            if (r < .55f) {
                drawLine(Color(0xFF6E7A2E), Offset(x, ground - 2.5f * u), Offset(x + (r - .3f) * 3 * u, ground - (4 + r * 3) * u), .7f * u, StrokeCap.Round)
            } else {
                drawOval(LeafColors[i % LeafColors.size], Offset(x, ground - 2.6f * u), Size(2.6f * u, 1.3f * u))
            }
            x += (4 + hash01(i * 3.3f) * 6) * u
            i++
        }

        drawPumpkinPatch(size.width * .2f, ground - 2 * u, time, seed = 2f)
        drawHayBale(Offset(size.width * .38f, ground - 2 * u), time)
        drawPumpkinPatch(size.width * .66f, ground - 2 * u, time, seed = 7f)
        drawLeafPile(Offset(size.width * .82f, ground - 2 * u))
        drawSquirrel(ground - 2 * u, time, from = size.width * .4f, to = size.width * .6f)

        drawFallingLeaves(ground, time, gust)
    }
}

private fun DrawScope.drawAutumnTree(root: Offset, scale: Float, time: Float, gust: Float, seed: Float) {
    val u = 1.dp.toPx() * scale
    val bark = Color(0xFF5B3A24)
    val sway = (sin(time * 1.1f + seed) * 1.2f + gust * 4f) * u
    drawLine(bark, root, Offset(root.x + sway * .3f, root.y - 18 * u), 3.6f * u, StrokeCap.Round)
    drawLine(bark, Offset(root.x + sway * .2f, root.y - 11 * u), Offset(root.x - 9 * u + sway * .5f, root.y - 20 * u), 1.8f * u, StrokeCap.Round)
    drawLine(bark, Offset(root.x + sway * .25f, root.y - 13 * u), Offset(root.x + 10 * u + sway * .5f, root.y - 22 * u), 1.6f * u, StrokeCap.Round)
    // Canopy: a wide crown of overlapping blobs. Darker blobs first give it depth; each one sways on its own.
    fun crown(count: Int, spread: Float, lift: Float, radius: Float, colors: List<Color>, salt: Float) {
        repeat(count) { k ->
            val a = hash01(seed * 10 + k + salt)
            val b = hash01(seed * 20 + k * 1.3f + salt)
            val wobble = sin(time * 1.6f + k + seed) * .8f * u + sway * (.6f + b * .4f)
            // Points spread wide near the middle of the crown and narrower towards the top, like a real canopy.
            val c = Offset(root.x + (a - .5f) * spread * (1.1f - b * .5f) * u + wobble, root.y - (lift + b * 13) * u)
            drawCircle(colors[k % colors.size], (radius + hash01(seed + k * 3.1f + salt) * 2.5f) * u, c)
        }
    }
    crown(10, 34f, 17f, 5f, listOf(Color(0xFFA8642C), Color(0xFFB9472A)), 0f)
    crown(16, 30f, 19f, 4.4f, LeafColors.take(4), 50f)
    crown(7, 20f, 24f, 2.6f, listOf(Color(0xFFFFE08A), Color(0xFFF5D25E)), 90f)
}

private fun DrawScope.drawPumpkinPatch(center: Float, ground: Float, time: Float, seed: Float) {
    val u = 1.dp.toPx()
    // Curling vines behind the fruit.
    val vine = Color(0xFF4E7A2B)
    val path = Path().apply {
        moveTo(center - 34 * u, ground)
        var x = center - 34 * u
        var k = 0
        while (x < center + 34 * u) {
            quadraticTo(x + 4 * u, ground - (3 + (k % 2) * 2) * u, x + 8 * u, ground - 1 * u)
            x += 8 * u
            k++
        }
    }
    drawPath(path, vine, style = Stroke(.9f * u, cap = StrokeCap.Round))
    for (k in 0..6) {
        val lx = center + (-30 + k * 10) * u
        drawOval(Color(0xFF5E8F33), Offset(lx, ground - 5 * u), Size(4 * u, 2.6f * u))
    }
    listOf(-22f to 4.2f, -9f to 6.2f, 6f to 3.6f, 19f to 5.2f).forEachIndexed { i, (dx, r) ->
        drawPumpkin(Offset(center + dx * u, ground + 1 * u), r * u, time, carved = false, seed = seed + i)
    }
}

private fun DrawScope.drawHayBale(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    val top = base.y - 11 * u
    drawRoundRect(Color(0xFFD9B04E), Offset(base.x - 13 * u, top), Size(26 * u, 11 * u), CornerRadius(2.5f * u))
    for (k in 0..8) {
        val sx = base.x + (-11 + k * 2.6f) * u
        drawLine(Color(0xFFB48A2E), Offset(sx, top + 1.5f * u), Offset(sx + 1 * u, base.y - 1.5f * u), .5f * u)
    }
    drawLine(Color(0xFF8A5A2B), Offset(base.x - 13 * u, top + 3.5f * u), Offset(base.x + 13 * u, top + 3.5f * u), .9f * u)
    drawLine(Color(0xFF8A5A2B), Offset(base.x - 13 * u, top + 7.5f * u), Offset(base.x + 13 * u, top + 7.5f * u), .9f * u)
    drawPumpkin(Offset(base.x + 4 * u, top + .5f * u), 4.6f * u, time, carved = false, seed = 3f)
    drawPumpkin(Offset(base.x - 6 * u, top + .5f * u), 3.2f * u, time, carved = false, seed = 4f)
}

private fun DrawScope.drawLeafPile(base: Offset) {
    val u = 1.dp.toPx()
    repeat(26) { k ->
        val a = hash01(k * 2.7f)
        val b = hash01(k * 5.1f)
        val dx = (a - .5f) * 26 * u
        val height = (1 - abs(dx) / (13 * u)).coerceAtLeast(0f) * 6 * u
        rotate(a * 360f, Offset(base.x + dx, base.y - b * height)) {
            drawOval(LeafColors[k % LeafColors.size], Offset(base.x + dx - 1.6f * u, base.y - b * height - .8f * u), Size(3.2f * u, 1.6f * u))
        }
    }
}

/** A squirrel hopping back and forth across the grass, pausing to look around. */
private fun DrawScope.drawSquirrel(ground: Float, time: Float, from: Float, to: Float) {
    val u = 1.dp.toPx()
    val trip = 9f
    val t = time % (trip * 2)
    val leg = if (t < trip) t / trip else 1f - (t - trip) / trip
    val facing = if (t < trip) 1f else -1f
    // Hop in bursts: travel during the first 70% of each hop window, sit still for the rest.
    val hops = 7
    val hopPhase = leg * hops % 1f
    val moving = hopPhase < .7f
    val travel = (floorDiv(leg * hops) + min(hopPhase / .7f, 1f)) / hops
    val x = mix(from, to, travel.coerceIn(0f, 1f))
    val lift = if (moving) sin(hopPhase / .7f * Math.PI.toFloat()) * 4 * u else 0f
    val feet = Offset(x, ground - lift)
    val fur = Color(0xFFA0522D)
    withTransform({ scale(facing, 1f, feet) }) {
        // Bushy tail curling up behind.
        val tail = Path().apply {
            moveTo(feet.x - 2 * u, feet.y - 2 * u)
            cubicTo(feet.x - 9 * u, feet.y - 3 * u, feet.x - 9 * u, feet.y - 12 * u, feet.x - 4 * u, feet.y - 11 * u)
        }
        drawPath(tail, fur, style = Stroke(3.6f * u, cap = StrokeCap.Round))
        drawPath(tail, Color(0xFFC07040), style = Stroke(1.6f * u, cap = StrokeCap.Round))
        drawOval(fur, Offset(feet.x - 3 * u, feet.y - 6 * u), Size(6 * u, 5.5f * u))
        drawOval(Color(0xFFE8C9A0), Offset(feet.x - .2f * u, feet.y - 5 * u), Size(2.4f * u, 3.6f * u))
        val head = Offset(feet.x + 2.6f * u, feet.y - 7.4f * u + if (moving) 0f else sin(time * 6f) * .3f * u)
        drawCircle(fur, 2.3f * u, head)
        drawCircle(fur, .8f * u, head + Offset(-.8f * u, -2.2f * u))
        drawCircle(Color(0xFF1E1A16), .45f * u, head + Offset(.9f * u, -.3f * u))
        drawCircle(Color(0xFF1E1A16), .3f * u, head + Offset(2.2f * u, .4f * u))
    }
}

private fun floorDiv(v: Float): Float = kotlin.math.floor(v)

/** Leaves spiralling down from above the dock to the bottom of the screen, fluttering and riding the gusts. */
private fun DrawScope.drawFallingLeaves(ground: Float, time: Float, gust: Float) {
    val u = 1.dp.toPx()
    val count = (size.width / (20 * u)).toInt().coerceIn(20, 110)
    val travel = size.height + 24 * u
    repeat(count) { i ->
        val fall = (9 + hash01(i * 1.3f) * 9) * u
        val y = (time * fall + hash01(i * 2.9f) * travel) % travel - 12 * u
        val drift = (3 + hash01(i * 4.4f) * 6) * u
        val sway = sin(time * (1.1f + hash01(i * 6.1f)) + i) * 7 * u
        val x = ((hash01(i * 7.3f) * size.width + time * drift + gust * 28 * u + sway) % (size.width + 20 * u)) - 10 * u
        val spin = time * (60f + hash01(i * 8.8f) * 120f) + i * 40f
        // Fading over the bar keeps the icons readable while leaves keep falling past them.
        val alpha = if (y > ground) .5f else 1f
        val center = Offset(x, y)
        withTransform({
            rotate(spin, center)
            // Flipping on one axis fakes the 3-D tumble of a real leaf.
            scale(cos(time * (2f + hash01(i * 3.7f) * 2f) + i).coerceIn(-1f, 1f).let { if (abs(it) < .2f) .2f * kotlin.math.sign(it + .001f) else it }, 1f, center)
        }) {
            drawLeaf(center, (3.4f + hash01(i * 9.9f) * 2.4f) * u, LeafColors[i % LeafColors.size].copy(alpha = alpha))
        }
    }
}

private fun DrawScope.drawLeaf(center: Offset, size: Float, color: Color) {
    val leaf = Path().apply {
        moveTo(center.x - size, center.y)
        quadraticTo(center.x - size * .2f, center.y - size * .9f, center.x + size, center.y)
        quadraticTo(center.x - size * .2f, center.y + size * .9f, center.x - size, center.y)
        close()
    }
    drawPath(leaf, color)
    drawLine(Color(0xFF7A3E1C).copy(alpha = color.alpha * .7f), Offset(center.x - size * 1.3f, center.y), Offset(center.x + size * .8f, center.y), size * .18f)
}
