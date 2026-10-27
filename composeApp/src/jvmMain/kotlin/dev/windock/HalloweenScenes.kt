package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** -1 = wings raised, 1 = wings lowered. A quick downstroke and a slower recovery, like a real bat. */
private fun wingBeat(phase: Float): Float {
    val p = phase - floor(phase)
    return if (p < .4f) mix(-1f, 1f, smooth(p / .4f)) else mix(1f, -1f, smooth((p - .4f) / .6f))
}

// ---------------------------------------------------------------- Moon

internal fun DrawScope.drawCrescentMoon(center: Offset, radius: Float, light: Color, glow: Color, time: Float) {
    val pulse = .8f + .2f * sin(time * .9f)
    val halo = radius * 2.6f
    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .45f * pulse), glow.copy(alpha = 0f)), center, halo), halo, center)
    // A real cut-out instead of painting a dark disc over the moon, so it works over any background.
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(Offset(center.x + radius * .55f, center.y - radius * .35f), radius * .9f)) }
    val crescent = Path.combine(PathOperation.Difference, disc, bite)
    // Brighter outer limb fading toward the inner edge gives the thin crescent some volume.
    drawPath(crescent, Brush.linearGradient(listOf(lerp(light, Color.White, .35f), light, lerp(light, Color.Black, .12f)),
        Offset(center.x - radius, center.y + radius * .3f), Offset(center.x + radius * .4f, center.y - radius * .2f)))
}

// ---------------------------------------------------------------- Bats

internal class BatFlight(val span: Dp, val speed: Dp, val altitude: Dp, val seed: Float, val flapHz: Float, val leftward: Boolean)

private fun DrawScope.drawBat(center: Offset, span: Float, beat: Float, color: Color, tilt: Float, facingLeft: Boolean) {
    val s = span / 2f
    val tip = beat * .6f - .1f
    withTransform({
        rotate(tilt, center)
        if (facingLeft) scale(-1f, 1f, center)
    }) {
        for (side in intArrayOf(-1, 1)) {
            fun pt(x: Float, y: Float) = Offset(center.x + side * x * s, center.y + y * s)
            val tipPoint = pt(1f, tip)
            val root = pt(.08f, .14f)
            val wing = Path().apply {
                val shoulder = pt(.08f, -.12f)
                val c1 = pt(.35f, -.3f + tip * .3f)
                val c2 = pt(.7f, tip - .2f)
                moveTo(shoulder.x, shoulder.y)
                cubicTo(c1.x, c1.y, c2.x, c2.y, tipPoint.x, tipPoint.y)
                // Trailing edge: membrane scallops curving up between the finger bones.
                var previous = tipPoint
                for (k in 1..3) {
                    val f = k / 3f
                    val next = Offset(mix(tipPoint.x, root.x, f), mix(tipPoint.y, root.y, f) + if (k < 3) .12f * s else 0f)
                    quadraticTo((previous.x + next.x) / 2f, (previous.y + next.y) / 2f - .16f * s, next.x, next.y)
                    previous = next
                }
                close()
            }
            drawPath(wing, color)
        }
        drawOval(color, Offset(center.x - .13f * s, center.y - .2f * s), Size(.26f * s, .5f * s))
        drawCircle(color, .15f * s, Offset(center.x, center.y - .24f * s))
        val ears = Path().apply {
            moveTo(center.x - .13f * s, center.y - .3f * s); lineTo(center.x - .1f * s, center.y - .5f * s); lineTo(center.x - .02f * s, center.y - .34f * s)
            moveTo(center.x + .13f * s, center.y - .3f * s); lineTo(center.x + .1f * s, center.y - .5f * s); lineTo(center.x + .02f * s, center.y - .34f * s)
        }
        drawPath(ears, color)
    }
}

internal fun DrawScope.drawBatSwarm(bats: List<BatFlight>, time: Float, color: Color) {
    val margin = 24.dp.toPx()
    val track = size.width + margin * 2
    bats.forEach { bat ->
        val speed = bat.speed.toPx()
        val run = (time * speed + bat.seed * track) % track
        val x = if (bat.leftward) size.width + margin - run else run - margin
        // Two layered sine waves give an erratic, fluttery flight line instead of a straight glide.
        val a1 = time * 1.3f + bat.seed * 11f
        val a2 = time * 2.9f + bat.seed * 5f
        val amp1 = 5.dp.toPx()
        val amp2 = 1.6.dp.toPx()
        val y = bat.altitude.toPx() + sin(a1) * amp1 + sin(a2) * amp2
        val climb = cos(a1) * 1.3f * amp1 + cos(a2) * 2.9f * amp2
        val heading = (atan2(climb, speed) * 57.3f * .6f).coerceIn(-22f, 22f)
        val beat = wingBeat(time * bat.flapHz + bat.seed)
        // The body lifts slightly on every downstroke.
        drawBat(Offset(x, y - beat * .8.dp.toPx()), bat.span.toPx(), beat, color,
            if (bat.leftward) -heading else heading, bat.leftward)
    }
}

private val ghostNightBats = listOf(
    BatFlight(15.dp, 38.dp, 12.dp, .1f, 4.2f, false),
    BatFlight(11.dp, 46.dp, 8.dp, .55f, 5.1f, false),
    BatFlight(13.dp, 31.dp, 15.dp, .8f, 4.6f, true),
)

private val graveyardBats = listOf(
    BatFlight(12.dp, 34.dp, 9.dp, .3f, 4.8f, true),
    BatFlight(10.dp, 42.dp, 13.dp, .72f, 5.4f, true),
)

// ---------------------------------------------------------------- Ghost scene

@Composable
internal fun HalloweenGhostScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val moon = Offset(size.width * .88f, 13.dp.toPx())
        drawCrescentMoon(moon, 6.5.dp.toPx(), Color(0xFFF1DFA8), Color(0xFFB79BD0), time)
        repeat(8) { index ->
            val star = Offset(size.width * (index + 1) / 9f, (4 + (index % 3) * 5).dp.toPx())
            if (kotlin.math.abs(star.x - moon.x) < 16.dp.toPx()) return@repeat
            val twinkle = (sin(time * .37f * TAU + index * 2.1f) + 1f) / 2f
            drawSparkle(star, (if (index % 3 == 0) 3.2f else 2.2f).dp.toPx() * (.6f + .4f * twinkle), .35f + .65f * twinkle)
        }
        drawBatSwarm(ghostNightBats, time, Color(0xFF35283F))

        val u = 1.dp.toPx()
        fun ghostAt(t: Float) = Offset(
            (t / 17f % 1f) * (size.width + 40 * u) - 20 * u,
            // A quick bob layered on a slow swoop so the flight never looks like it's on a rail.
            13 * u + sin(t * TAU / 1.7f) * 2 * u + sin(t * .55f) * 3 * u,
        )
        val head = ghostAt(time)
        // Ectoplasm wisps trail along the path the ghost just travelled.
        for (k in 1..6) {
            val past = ghostAt(time - k * .1f)
            if (past.x > head.x) continue
            drawCircle(Color(0xFFB9A3D9).copy(alpha = .3f * (1f - k / 7f)), (3.6f - k * .45f) * u,
                past + Offset(-4 * u, 13 * u + sin(time * 5f + k) * u))
        }
        drawGhost(head, time)
    }
}

/** Ghost whose head (the dome's center) sits on [center], flying to the right. */
private fun DrawScope.drawGhost(center: Offset, time: Float) {
    val u = 1.dp.toPx()
    val r = 9.5f * u
    val hemY = center.y + 17 * u
    // The hem lags behind the head, so the sheet sweeps back while flying forward.
    val sweep = -3 * u
    val outline = Color(0xFF53405F).copy(alpha = .85f)
    val sheetTop = Color(0xFFF6FBFF)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB79BD0).copy(alpha = .38f), Color(0xFFB79BD0).copy(alpha = 0f)),
        center + Offset(0f, 7 * u), 22 * u), 22 * u, center + Offset(0f, 7 * u))

    rotate(6f + sin(time * 1.7f) * 4f, center) {
        // Little arms: the back one trails, the front one waves "boo!" every few seconds.
        val booCycle = time % 5.5f
        val boo = smooth(booCycle / .3f) * (1f - smooth((booCycle - 1.4f) / .3f))
        fun arm(shoulder: Offset, degrees: Float) {
            val hand = along(shoulder, degrees, 5 * u)
            drawLine(outline, shoulder, hand, 4.4f * u, StrokeCap.Round)
            drawLine(Color(0xFFE6EEF4), shoulder, hand, 2.8f * u, StrokeCap.Round)
        }
        arm(center + Offset(-r + 2 * u, 7 * u), 155f + sin(time * 3f) * 10f)
        arm(center + Offset(r - 2 * u, 6 * u), mix(15f + sin(time * 3f + 1f) * 10f, -55f + sin(time * 16f) * 18f, boo))

        val sheet = Path().apply {
            moveTo(center.x - r, center.y)
            arcTo(Rect(center, r), 180f, 180f, false)
            cubicTo(center.x + r, center.y + 7 * u, center.x + r + 2 * u + sweep, hemY - 4 * u, center.x + r + 1 * u + sweep, hemY)
            // Rippling hem: four scallops whose crests travel along the edge.
            val right = center.x + r + 1 * u + sweep
            val left = center.x - r - 2 * u + sweep
            var previousX = right
            for (i in 1..4) {
                val x = mix(right, left, i / 4f)
                val y = hemY + sin(time * 6f + i * 1.7f) * 1.5f * u
                quadraticTo((previousX + x) / 2f, hemY + 4 * u + sin(time * 6f + i) * 1.2f * u, x, y)
                previousX = x
            }
            cubicTo(left, hemY - 4 * u, center.x - r, center.y + 7 * u, center.x - r, center.y)
            close()
        }
        drawPath(sheet, Brush.verticalGradient(
            listOf(sheetTop, Color(0xFFDDE9F0), Color(0xFFBBA9D8).copy(alpha = .8f)),
            startY = center.y - r, endY = hemY + 4 * u))
        drawPath(sheet, outline, style = Stroke(1.1f * u))
        // Sheen on the dome.
        drawArc(Color.White.copy(alpha = .9f), 200f, 50f, false, center - Offset(r * .7f, r * .7f), Size(r * 1.4f, r * 1.4f),
            style = Stroke(1.2f * u, cap = StrokeCap.Round))

        val face = center + Offset(1.2f * u, 1 * u)
        val blinkPhase = time % 3.7f
        val blink = if (blinkPhase < .16f) 1f - sin(blinkPhase / .16f * Math.PI.toFloat()) * .85f else 1f
        for (side in intArrayOf(-1, 1)) {
            val eye = face + Offset(side * 3 * u, 0f)
            drawCircle(Color(0xFF80FFE1).copy(alpha = .3f), 2.8f * u, eye)
            drawOval(Color(0xFF182329), eye - Offset(1.3f * u, 1.9f * u * blink), Size(2.6f * u, 3.8f * u * blink))
            if (blink > .6f) drawCircle(Color.White, .55f * u, eye + Offset(.4f * u, -.8f * u))
            drawOval(Color(0xFFF2A7C3).copy(alpha = .45f), eye + Offset(-1.6f * u, 2.6f * u), Size(3.2f * u, 1.5f * u))
        }
        // The mouth stretches into a big "O" while it says boo.
        val mouthW = mix(2.2f, 3.4f, boo) * u
        val mouthH = (mix(2f + sin(time * 2.1f) * .6f, 4.4f, boo)) * u
        drawOval(Color(0xFF31243C), face + Offset(-mouthW / 2f, 4.6f * u), Size(mouthW, mouthH))
    }
}

// ---------------------------------------------------------------- Zombie graveyard

private class Grave(
    val position: Float, val shape: Int, val tilt: Float, val period: Float, val offset: Float,
    val skin: Color, val cloth: Color, val facingLeft: Boolean,
)

private val graves = listOf(
    Grave(.22f, 0, -4f, 8.5f, 0f, Color(0xFF7E9070), Color(0xFF4A4038), false),
    Grave(.51f, 1, 2.5f, 9.7f, .37f, Color(0xFF8C8862), Color(0xFF3B4752), true),
    Grave(.79f, 2, -2f, 7.9f, .68f, Color(0xFF6F8770), Color(0xFF55403F), true),
)

private val Soil = Color(0xFF3B2F27)
private val StoneEdge = Color(0xFF2E332E)

@Composable
internal fun ZombieGraveyardScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    val textMeasurer = rememberTextMeasurer()
    val rip = remember {
        textMeasurer.measure("RIP", TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Black, letterSpacing = .4.sp))
    }
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val u = 1.dp.toPx()
        val groundY = 40 * u
        // The soil's surface sits a little above the bar; everything that "comes out of the ground" is clipped here.
        val surface = groundY - 6 * u
        drawCrescentMoon(Offset(size.width * .87f, 11 * u), 6.5f * u, Color(0xFFDDE4BA), Color(0xFFA8C49B), time)
        drawBatSwarm(graveyardBats, time, Color(0xFF262C27))

        // Gnarled dead trees frame the cemetery, leaning in over the graves.
        drawDeadTree(Offset(14 * u, groundY), lean = 6f, time = time, seed = 3f)
        drawDeadTree(Offset(size.width - 16 * u, groundY), lean = -8f, time = time, seed = 11f)
        drawIronFence(28 * u, 96 * u, surface, time, crowAt = .62f)
        drawIronFence(size.width - 104 * u, size.width - 30 * u, surface, time + 3f, crowAt = .3f)

        drawFog(time, 33f, .18f)

        // Background clutter so wide screens never read as an empty strip.
        listOf(.13f to -7f, .42f to 5f, .7f to -4f, .93f to 8f).forEach { (at, tilt) -> drawWoodenCross(Offset(size.width * at, surface + u), tilt) }
        drawLantern(Offset(size.width * .34f, surface), time)
        drawOpenGrave(Offset(size.width * .62f, surface), time)

        graves.forEach { grave ->
            val cx = size.width * grave.position
            val dir = if (grave.facingLeft) -1f else 1f
            val zombieX = cx + dir * 7 * u
            drawHeadstone(Offset(cx, surface + 1 * u), grave.shape, grave.tilt, rip)

            // Cycle: hidden → bursts out (with overshoot) → shambles in place → sinks back → hidden.
            val p = (time / grave.period + grave.offset) % 1f
            val rise = when {
                p < .18f -> 0f
                p < .42f -> easeOutBack((p - .18f) / .24f)
                p < .78f -> 1f
                p < .92f -> 1f - easeInCubic((p - .78f) / .14f)
                else -> 0f
            }
            val emerging = if (p >= .18f && p < .42f) (p - .18f) / .24f else -1f
            // Arms point up while clawing out of the ground, then swing forward into the classic reach.
            val settle = smooth((p - .34f) / .1f) * (1f - smooth((p - .74f) / .06f))
            val shake = if (emerging >= 0f) sin(time * 48f) * .7f * u * (1f - emerging) else 0f

            // Sickly green mist seeps from the grave while the dead are up.
            val mist = smooth((p - .14f) / .08f) * (1f - smooth((p - .88f) / .08f))
            if (mist > 0f) {
                val c = Offset(zombieX, surface)
                drawOval(Brush.radialGradient(listOf(Color(0xFF9CFF7A).copy(alpha = .4f * mist), Color(0xFF9CFF7A).copy(alpha = 0f)), c, 16 * u),
                    c - Offset(16 * u, 6 * u), Size(32 * u, 10 * u))
            }
            if (rise > 0f) {
                clipRect(bottom = surface) {
                    drawZombie(Offset(zombieX + shake, surface), rise, settle, time, grave.position * 7f, grave.skin, grave.cloth, dir)
                }
            }
            drawGraveMound(Offset(zombieX, surface + 1.5f * u))
            if (emerging >= 0f) drawDirtBurst(Offset(zombieX, surface), emerging)
        }

        // Hands that claw out of the soil now and then, between the graves.
        listOf(Triple(.29f, 6.3f, 0f), Triple(.56f, 7.1f, 2.4f), Triple(.86f, 5.7f, 4.1f)).forEach { (at, period, offset) ->
            drawGraspingHand(Offset(size.width * at, surface), time, period, offset)
        }
        drawWalkingZombie(surface, time)
        drawSoil(groundY, surface, time)
        listOf(.18f, .47f, .76f).forEachIndexed { i, at -> drawSkull(Offset(size.width * at + (i - 1) * 9 * u, surface + 1.2f * u), i % 2 == 0) }
        drawFog(time + 7f, 37f, .22f)
    }
}

/** The graveyard's dirt: a lumpy band of soil with pebbles and dead grass, resting on the bar. */
private fun DrawScope.drawSoil(groundY: Float, surface: Float, time: Float) {
    val u = 1.dp.toPx()
    val soil = Path().apply {
        moveTo(0f, groundY)
        var x = 0f
        while (x <= size.width + 6 * u) {
            // Stable lumps from a hash, so the terrain doesn't crawl as the window redraws.
            val bump = (hash01(x / (6 * u)) - .5f) * 2.4f * u
            lineTo(x, surface + bump)
            x += 6 * u
        }
        lineTo(size.width, groundY)
        close()
    }
    drawPath(soil, Brush.verticalGradient(listOf(Color(0xFF6A5039), Color(0xFF4A3727), Color(0xFF2E2219)), startY = surface - 1 * u, endY = groundY))
    drawPath(soil, Color(0xFF7D6249), style = Stroke(.9f * u))
    // Clods and roots buried in the band, so it reads as earth rather than a flat stripe.
    var clod = 0
    var cx = 5 * u
    while (cx < size.width) {
        val r = hash01(clod * 3.17f + .5f)
        val cy = surface + (2.2f + r * 2.6f) * u
        if (r < .55f) drawOval(Color(0xFF2A1F17).copy(alpha = .7f), Offset(cx, cy), Size((2f + r * 3) * u, (1f + r) * u))
        else drawLine(Color(0xFF3B2A1C), Offset(cx, cy), Offset(cx + 4 * u, cy + (r - .7f) * 4 * u), .6f * u, StrokeCap.Round)
        cx += (7 + hash01(clod * 1.91f) * 9) * u
        clod++
    }
    var x = 3 * u
    var i = 0
    while (x < size.width) {
        val r = hash01(i * 1.37f)
        when {
            r < .35f -> drawOval(Color(0xFF7D766B), Offset(x, surface + 1.5f * u + r * 2 * u), Size((1.4f + r * 2) * u, (1f + r) * u))
            r < .8f -> {
                // A tuft of dead grass bending in the breeze.
                val sway = sin(time * 1.6f + i) * .8f * u
                for (blade in -1..1) {
                    drawLine(Color(0xFF8C7A4E), Offset(x + blade * u, surface + .5f * u),
                        Offset(x + blade * 1.6f * u + sway, surface - (2.5f + (blade + 1) % 2 * 1.5f) * u), .6f * u, StrokeCap.Round)
                }
            }
        }
        x += (11 + hash01(i * 2.91f) * 14) * u
        i++
    }
}

private fun DrawScope.drawIronFence(from: Float, to: Float, base: Float, time: Float, crowAt: Float) {
    val u = 1.dp.toPx()
    val iron = Color(0xFF1E231F)
    var x = from
    while (x <= to) {
        drawLine(iron, Offset(x, base), Offset(x, base - 14 * u), 1.1f * u)
        val spear = Path().apply { moveTo(x - 1.5f * u, base - 13.5f * u); lineTo(x, base - 17 * u); lineTo(x + 1.5f * u, base - 13.5f * u); close() }
        drawPath(spear, iron)
        x += 5 * u
    }
    drawLine(iron, Offset(from - 1 * u, base - 11 * u), Offset(to + 1 * u, base - 11 * u), 1.2f * u)
    drawLine(iron, Offset(from - 1 * u, base - 3 * u), Offset(to + 1 * u, base - 3 * u), 1.2f * u)
    drawCrow(Offset(mix(from, to, crowAt), base - 11.6f * u), time)
}

/** A crow perched on a rail, turning its head and ruffling now and then. */
private fun DrawScope.drawCrow(feet: Offset, time: Float) {
    val u = 1.dp.toPx()
    val black = Color(0xFF15171A)
    val facing = if ((time / 3.7f).toInt() % 2 == 0) 1f else -1f
    val ruffle = if (time % 5.3f < .3f) sin(time * 60f) * .4f * u else 0f
    withTransform({ scale(facing, 1f, feet) }) {
        drawOval(black, Offset(feet.x - 3.5f * u, feet.y - 5 * u + ruffle), Size(7 * u, 4.5f * u))
        val tail = Path().apply { moveTo(feet.x - 3 * u, feet.y - 3.5f * u); lineTo(feet.x - 7 * u, feet.y - 1.5f * u); lineTo(feet.x - 2 * u, feet.y - 2 * u); close() }
        drawPath(tail, black)
        drawCircle(black, 1.9f * u, Offset(feet.x + 3 * u, feet.y - 6 * u))
        val beak = Path().apply { moveTo(feet.x + 4.6f * u, feet.y - 6.6f * u); lineTo(feet.x + 7.2f * u, feet.y - 5.8f * u); lineTo(feet.x + 4.6f * u, feet.y - 5.2f * u); close() }
        drawPath(beak, Color(0xFF3B3A33))
        drawCircle(Color(0xFFE8C547), .45f * u, Offset(feet.x + 3.6f * u, feet.y - 6.4f * u))
    }
}

private fun DrawScope.drawLantern(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    val iron = Color(0xFF22262A)
    val light = flicker(time, 1.7f)
    val lamp = Offset(base.x, base.y - 22 * u)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC266).copy(alpha = .45f * (.6f + .4f * light)), Color(0xFFFFC266).copy(alpha = 0f)), lamp, 18 * u), 18 * u, lamp)
    drawLine(iron, base, Offset(base.x, lamp.y + 4 * u), 1.6f * u)
    drawLine(iron, Offset(base.x, lamp.y - 5 * u), Offset(base.x + 4 * u, lamp.y - 5 * u), 1.2f * u)
    drawRect(Color(0xFFFFD27A).copy(alpha = .35f + .25f * light), Offset(lamp.x - 2.6f * u, lamp.y - 3 * u), Size(5.2f * u, 6.5f * u))
    // The candle flame inside wobbles with the flicker.
    val flame = Path().apply {
        val h = (2.6f + 1.2f * light) * u
        moveTo(lamp.x - 1 * u, lamp.y + 2.5f * u)
        quadraticTo(lamp.x - 1.2f * u, lamp.y + 2.5f * u - h * .6f, lamp.x + sin(time * 9f) * .4f * u, lamp.y + 2.5f * u - h)
        quadraticTo(lamp.x + 1.2f * u, lamp.y + 2.5f * u - h * .6f, lamp.x + 1 * u, lamp.y + 2.5f * u)
        close()
    }
    drawPath(flame, Color(0xFFFFB531))
    drawRect(iron, Offset(lamp.x - 3 * u, lamp.y - 3.5f * u), Size(6 * u, 7.5f * u), style = Stroke(1 * u))
    val roof = Path().apply { moveTo(lamp.x - 4 * u, lamp.y - 3.5f * u); lineTo(lamp.x, lamp.y - 6.5f * u); lineTo(lamp.x + 4 * u, lamp.y - 3.5f * u); close() }
    drawPath(roof, iron)
}

/** A freshly dug grave: a black pit, the spoil heap beside it and a shovel left standing in the dirt. */
private fun DrawScope.drawOpenGrave(at: Offset, time: Float) {
    val u = 1.dp.toPx()
    drawOval(Color(0xFF6A5039), Offset(at.x - 12 * u, at.y - 2.2f * u), Size(24 * u, 6 * u))
    drawOval(Color(0xFF0E0A08), Offset(at.x - 10 * u, at.y - 1.2f * u), Size(20 * u, 4.4f * u))
    val heap = Path().apply {
        moveTo(at.x + 8 * u, at.y + 3 * u)
        cubicTo(at.x + 9 * u, at.y - 10 * u, at.x + 21 * u, at.y - 11 * u, at.x + 27 * u, at.y + 3 * u)
        close()
    }
    drawPath(heap, Brush.verticalGradient(listOf(Color(0xFF7A5C41), Color(0xFF3E2E21)), startY = at.y - 10 * u, endY = at.y + 3 * u))
    for (k in 0..4) drawCircle(Color(0xFF8A6A4C), (.6f + k % 2 * .3f) * u, Offset(at.x + (12 + k * 3) * u, at.y - (5 - k % 3 * 2) * u))
    // Shovel: handle, grip and blade buried in the heap.
    val handleTop = Offset(at.x + 12 * u, at.y - 19 * u)
    val bladeTop = Offset(at.x + 17 * u, at.y - 5 * u)
    drawLine(Color(0xFF7A5A3A), handleTop, bladeTop, 1.4f * u, StrokeCap.Round)
    drawLine(Color(0xFF7A5A3A), handleTop + Offset(-2 * u, -.5f * u), handleTop + Offset(2 * u, .5f * u), 1.4f * u, StrokeCap.Round)
    val blade = Path().apply {
        moveTo(bladeTop.x - 2 * u, bladeTop.y); lineTo(bladeTop.x + 2.2f * u, bladeTop.y + 1 * u)
        lineTo(bladeTop.x + 2.6f * u, bladeTop.y + 5 * u); lineTo(bladeTop.x - .8f * u, bladeTop.y + 4 * u); close()
    }
    drawPath(blade, Color(0xFF8D949A))
    // A trickle of dirt keeps sliding back into the pit.
    val slide = (time * .9f) % 1f
    drawCircle(Color(0xFF5A4433), .7f * u, Offset(at.x + mix(8f, 3f, slide) * u, at.y + mix(-3f, 1f, slide) * u))
}

private fun DrawScope.drawWoodenCross(base: Offset, tilt: Float) {
    val u = 1.dp.toPx()
    val wood = Color(0xFF5B4632)
    rotate(tilt, base) {
        drawRect(wood, Offset(base.x - 1.2f * u, base.y - 13 * u), Size(2.4f * u, 13 * u))
        drawRect(wood, Offset(base.x - 4.5f * u, base.y - 10.5f * u), Size(9 * u, 2.2f * u))
        drawLine(Color(0xFF3A2C1F), Offset(base.x - 1.2f * u, base.y - 6 * u), Offset(base.x + 1.2f * u, base.y - 5 * u), .5f * u)
    }
}

private fun DrawScope.drawSkull(at: Offset, facingRight: Boolean) {
    val u = 1.dp.toPx()
    val bone = Color(0xFFE6DFC8)
    withTransform({ if (!facingRight) scale(-1f, 1f, at) }) {
        drawCircle(bone, 2.3f * u, at + Offset(0f, -2 * u))
        drawRoundRect(bone, at + Offset(-1.3f * u, -1 * u), Size(2.8f * u, 1.8f * u), CornerRadius(.6f * u))
        drawCircle(Color(0xFF2B231C), .65f * u, at + Offset(-.6f * u, -2.2f * u))
        drawCircle(Color(0xFF2B231C), .65f * u, at + Offset(1f * u, -2.2f * u))
        drawLine(bone, at + Offset(3 * u, .5f * u), at + Offset(7 * u, 0f), .9f * u, StrokeCap.Round)
    }
}

/** A rotting hand that pushes up through the soil, flexes its fingers and sinks back. */
private fun DrawScope.drawGraspingHand(at: Offset, time: Float, period: Float, offset: Float) {
    val u = 1.dp.toPx()
    val p = ((time + offset) / period) % 1f
    val up = smooth(p / .2f) * (1f - smooth((p - .55f) / .2f))
    if (up <= 0f) return
    val skin = Color(0xFF7E9070)
    val wrist = Offset(at.x, at.y - up * 7 * u)
    clipRect(bottom = at.y) {
        drawLine(Color(0xFF3B4752), Offset(at.x, at.y + 3 * u), wrist + Offset(0f, 2 * u), 2.6f * u)
        drawLine(skin, wrist + Offset(0f, 1.5f * u), wrist, 2.2f * u, StrokeCap.Round)
        val grab = sin(time * 6f + offset) * .5f + .5f
        for (finger in -2..1) {
            val angle = -90f + finger * (14f + 10f * grab)
            drawLine(skin, wrist, along(wrist, angle, (2.4f + (finger + 2) % 2 * .6f) * u), .8f * u, StrokeCap.Round)
        }
        drawLine(skin, wrist, along(wrist, -90f + 65f, 1.8f * u), .8f * u, StrokeCap.Round)
    }
    drawCircle(Color(0xFF5A4433), .8f * u, Offset(at.x - 2.5f * u, at.y - .5f * u))
    drawCircle(Color(0xFF5A4433), .6f * u, Offset(at.x + 2.2f * u, at.y - .3f * u))
}

/** A zombie that limps slowly across the whole cemetery, arms out, dragging one foot. */
private fun DrawScope.drawWalkingZombie(surface: Float, time: Float) {
    val u = 1.dp.toPx()
    val track = size.width + 60 * u
    val x = (time * 11 * u) % track - 30 * u
    val step = time * 1.9f
    // The limp: a quick step on the good leg, a slow drag of the bad one.
    val limp = (sin(step * TAU) * .5f + .5f)
    val hips = Offset(x, surface - 8.5f * u + abs(sin(step * TAU / 2)) * 1 * u)
    val pants = Color(0xFF2D3640)
    val good = along(hips, 90f - 22f * sin(step * TAU), 8.5f * u)
    val dragged = along(hips, 90f + 10f * limp, 8.5f * u)
    drawLine(lerp(pants, Color.Black, .3f), hips, Offset(dragged.x, minOf(dragged.y, surface)), 2.6f * u, StrokeCap.Round)
    drawLine(pants, hips, Offset(good.x, minOf(good.y, surface)), 2.6f * u, StrokeCap.Round)
    clipRect(bottom = hips.y + 1 * u) {
        drawZombie(Offset(x, surface), 1.3f, 1f, time, 4.2f, Color(0xFF889A73), Color(0xFF6A3B32), 1f)
    }
}

private fun DrawScope.drawFog(time: Float, baseline: Float, alpha: Float) {
    val u = 1.dp.toPx()
    val drift = sin(time * TAU / 8.4f) * 3f
    val slide = (time * 4 * u) % size.width
    val fog = Path().apply {
        moveTo(-size.width + slide, baseline * u)
        for (copy in 0..1) {
            val x0 = size.width * (copy - 1) + slide
            cubicTo(x0 + size.width * .2f, (baseline - 4 + drift) * u, x0 + size.width * .35f, (baseline + 3 - drift) * u, x0 + size.width * .55f, (baseline - 1) * u)
            cubicTo(x0 + size.width * .72f, (baseline - 5 - drift) * u, x0 + size.width * .84f, (baseline + 2 + drift) * u, x0 + size.width, baseline * u)
        }
    }
    // Mid-tone green-grey so the mist still reads over a light desktop as well as a dark one.
    drawPath(fog, Color(0xFF8FA28A).copy(alpha = alpha * 1.6f), style = Stroke(3 * u, cap = StrokeCap.Round))
}

/** Four-point star: amber body with a darker rim so it stays visible over both light and dark backdrops. */
internal fun DrawScope.drawSparkle(center: Offset, radius: Float, alpha: Float) {
    val waist = radius * .28f
    val star = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + waist * .3f, center.y - waist * .3f, center.x + radius, center.y)
        quadraticTo(center.x + waist * .3f, center.y + waist * .3f, center.x, center.y + radius)
        quadraticTo(center.x - waist * .3f, center.y + waist * .3f, center.x - radius, center.y)
        quadraticTo(center.x - waist * .3f, center.y - waist * .3f, center.x, center.y - radius)
        close()
    }
    drawPath(star, Color(0xFFF0C766).copy(alpha = alpha))
    drawPath(star, Color(0xFF9C7420).copy(alpha = alpha * .8f), style = Stroke(.6f.dp.toPx()))
}

/** A recursive, wind-swayed dead tree rooted at [root]; [seed] picks a stable but unique silhouette. */
private fun DrawScope.drawDeadTree(root: Offset, lean: Float, time: Float, seed: Float) {
    val u = 1.dp.toPx()
    val bark = Color(0xFF2F362F)
    // Flared roots grip the ground.
    drawLine(bark, root + Offset(-1 * u, -1 * u), root + Offset(-5 * u, .5f * u), 1.6f * u, StrokeCap.Round)
    drawLine(bark, root + Offset(1 * u, -1 * u), root + Offset(4.5f * u, .5f * u), 1.4f * u, StrokeCap.Round)
    drawBranch(root, -90f + lean, 12 * u, 3.4f * u, 0, time, seed, bark)
}

private fun DrawScope.drawBranch(start: Offset, angle: Float, length: Float, width: Float, depth: Int, time: Float, seed: Float, bark: Color) {
    // Thinner branches sway more and slightly out of phase with the trunk, like a gusty night.
    val a = angle + sin(time * 1.3f + seed + depth * .8f) * depth * 1.6f
    val kink = (hash01(seed * 7f) - .5f) * 24f
    val mid = along(start, a + kink, length * .5f)
    val end = along(mid, a - kink * .6f, length * .5f)
    drawLine(bark, start, mid, width, StrokeCap.Round)
    drawLine(bark, mid, end, width * .82f, StrokeCap.Round)
    if (depth == 3) {
        // Hooked twig tips, the classic spooky claw.
        drawLine(bark, end, along(end, a + 50f * if (hash01(seed) > .5f) 1f else -1f, length * .35f), width * .6f, StrokeCap.Round)
        return
    }
    val children = if (depth == 0) 3 else 2
    for (i in 0 until children) {
        val childSeed = seed * 3.1f + i * 1.7f + depth
        val side = if (children == 3) i - 1f else if (i == 0) -1f else 1f
        val spread = side * (22f + hash01(childSeed) * 22f)
        val from = if (i == 0 && depth > 0) mid else end
        drawBranch(from, a + spread, length * (.6f + hash01(childSeed + .5f) * .2f), width * .62f, depth + 1, time, childSeed, bark)
    }
}

private fun DrawScope.drawHeadstone(base: Offset, shape: Int, tilt: Float, rip: TextLayoutResult) {
    val u = 1.dp.toPx()
    val w = 22 * u
    val top = base.y - 22 * u
    val left = base.x - w / 2
    val right = base.x + w / 2
    val stone = when (shape) {
        0 -> Path().apply {
            moveTo(left, base.y)
            lineTo(left, top + w / 2)
            arcTo(Rect(left, top, right, top + w), 180f, 180f, false)
            lineTo(right, base.y)
            close()
        }
        1 -> Path.combine(
            PathOperation.Union,
            Path().apply { addRect(Rect(base.x - 4 * u, top - 3 * u, base.x + 4 * u, base.y)) },
            Path().apply { addRect(Rect(base.x - 10 * u, top + 3 * u, base.x + 10 * u, top + 9 * u)) },
        )
        else -> Path().apply {
            moveTo(left, base.y)
            lineTo(left, top + 5 * u)
            lineTo(left + 5 * u, top)
            lineTo(right - 5 * u, top)
            lineTo(right, top + 5 * u)
            lineTo(right, base.y)
            close()
        }
    }
    rotate(tilt, base) {
        drawPath(stone, Brush.verticalGradient(listOf(Color(0xFFA3A69A), Color(0xFF61665C)), startY = top, endY = base.y))
        clipPath(stone) {
            // Shaded right flank and moss creeping up from the soil.
            drawRect(Color.Black.copy(alpha = .16f), Offset(base.x + w * .22f, top - 4 * u), Size(w, 30 * u))
            drawOval(Color(0xFF5E7A45).copy(alpha = .85f), Offset(left - 3 * u, base.y - 5 * u), Size(w * .55f, 8 * u))
            drawOval(Color(0xFF6E8B4F).copy(alpha = .6f), Offset(base.x + 2 * u, base.y - 3 * u), Size(w * .4f, 5 * u))
        }
        drawPath(stone, StoneEdge, style = Stroke(1 * u))
        // Engravings: dark groove with a lighter lip underneath reads as carved at this size.
        fun carve(from: Offset, to: Offset) {
            drawLine(Color(0xFFC9CBBE).copy(alpha = .6f), from + Offset(0f, .6f * u), to + Offset(0f, .6f * u), 1f * u, StrokeCap.Round)
            drawLine(Color(0xFF3C413A), from, to, 1f * u, StrokeCap.Round)
        }
        if (shape == 1) {
            drawCircle(Color(0xFF3C413A), 3.2f * u, Offset(base.x, top + 6 * u), style = Stroke(.9f * u))
        } else {
            carve(Offset(base.x, top + 4 * u), Offset(base.x, top + 10 * u))
            carve(Offset(base.x - 2.5f * u, top + 6 * u), Offset(base.x + 2.5f * u, top + 6 * u))
            // Engraved "RIP": a light lip under a dark groove.
            val textAt = Offset(base.x - rip.size.width / 2f, top + 11.5f * u)
            drawText(rip, Color(0xFFC9CBBE).copy(alpha = .6f), textAt + Offset(0f, .6f * u))
            drawText(rip, Color(0xFF3C413A), textAt)
        }
        val crack = Path().apply {
            moveTo(base.x + 5 * u, top + (if (shape == 1) 3 else 1) * u)
            lineTo(base.x + 3 * u, top + 6 * u)
            lineTo(base.x + 6 * u, top + 9 * u)
            lineTo(base.x + 4 * u, top + 12 * u)
        }
        drawPath(crack, StoneEdge.copy(alpha = .8f), style = Stroke(.8f * u, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawGraveMound(center: Offset) {
    val u = 1.dp.toPx()
    drawOval(Soil, Offset(center.x - 10 * u, center.y - 3.5f * u), Size(20 * u, 6 * u))
    drawArc(Color(0xFF5A4A3C), 200f, 140f, false, Offset(center.x - 8 * u, center.y - 3 * u), Size(16 * u, 4 * u),
        style = Stroke(.9f * u, cap = StrokeCap.Round))
}

private fun DrawScope.drawDirtBurst(ground: Offset, progress: Float) {
    val u = 1.dp.toPx()
    repeat(8) { k ->
        val spread = ((k * 53) % 7 - 3f) / 3f
        val height = (5 + (k * 31) % 5 * 1.6f) * u
        // Staggered clods so the dirt keeps spitting out while the zombie claws upward.
        val tau = progress * 1.7f - (k % 3) * .18f
        if (tau in 0f..1f) {
            val position = Offset(ground.x + spread * 10 * u * tau, ground.y - 3 * u - height * 4f * tau * (1f - tau))
            drawCircle(lerp(Soil, Color(0xFF6B5846), (k % 3) / 2f).copy(alpha = 1f - tau * .5f), (.7f + (k % 3) * .35f) * u, position)
        }
    }
}

/** Draws a zombie whose feet sit on [feet]; [rise] 0..1 lifts it out of the ground, [settle] 0..1 swings arms from up to forward. */
private fun DrawScope.drawZombie(feet: Offset, rise: Float, settle: Float, time: Float, seed: Float, skin: Color, cloth: Color, dir: Float) {
    val u = 1.dp.toPx()
    val top = feet.y - rise * 24 * u
    val x = feet.x
    val lean = (6f + sin(time * 1.6f + seed) * 5f) * settle * dir
    rotate(lean, Offset(x, top + 22 * u)) {
        val shadowSkin = lerp(skin, Color.Black, .3f)
        drawZombieArm(Offset(x + dir * 1.5f * u, top + 11 * u), settle, time, seed + 1.3f, shadowSkin, lerp(cloth, Color.Black, .3f), dir)

        val torso = Path().apply {
            moveTo(x - 5 * u, top + 10.5f * u)
            lineTo(x + 5 * u, top + 10.5f * u)
            lineTo(x + 6 * u, top + 34 * u)
            lineTo(x - 6 * u, top + 34 * u)
            close()
        }
        drawPath(torso, cloth)
        // Torn collar showing the neck, plus a couple of rips in the shirt.
        val collar = Path().apply {
            moveTo(x - 2.5f * u, top + 10.5f * u); lineTo(x + dir * .5f * u, top + 14.5f * u); lineTo(x + 2.5f * u, top + 10.5f * u); close()
        }
        drawPath(collar, skin)
        drawLine(lerp(cloth, Color.Black, .45f), Offset(x - 3 * u, top + 16 * u), Offset(x - 1 * u, top + 19 * u), .8f * u, StrokeCap.Round)
        drawLine(lerp(cloth, Color.Black, .45f), Offset(x + 2 * u, top + 18 * u), Offset(x + 4 * u, top + 17 * u), .8f * u, StrokeCap.Round)

        val headCenter = Offset(x + dir * .5f * u, top + 4.6f * u)
        val headTilt = (14f + sin(time * 1.1f + seed) * 7f) * dir
        rotate(headTilt, Offset(x, top + 9 * u)) {
            fun h(dx: Float, dy: Float) = Offset(headCenter.x + dir * dx * u, headCenter.y + dy * u)
            val head = Path().apply {
                val a = h(-4f, -2f); moveTo(a.x, a.y)
                val b1 = h(-4f, -4.6f); val b = h(0f, -4.6f); quadraticTo(b1.x, b1.y, b.x, b.y)
                val c1 = h(4.2f, -4.6f); val c = h(4.2f, -1f); quadraticTo(c1.x, c1.y, c.x, c.y)
                val d = h(3.6f, 3f); lineTo(d.x, d.y)
                val e1 = h(2.5f, 4.8f); val e = h(0f, 4.6f); quadraticTo(e1.x, e1.y, e.x, e.y)
                val f = h(-3f, 4f); lineTo(f.x, f.y)
                val g1 = h(-4.2f, 2f); quadraticTo(g1.x, g1.y, a.x, a.y)
                close()
            }
            drawPath(head, skin)
            drawPath(head, lerp(skin, Color.Black, .5f), style = Stroke(.7f * u))
            // Patchy hair tufts.
            drawLine(Color(0xFF2A2622), h(-2f, -4.3f), h(-3f, -6f), .9f * u, StrokeCap.Round)
            drawLine(Color(0xFF2A2622), h(.5f, -4.5f), h(1f, -6.3f), .9f * u, StrokeCap.Round)
            // One glowing eye, one hollow socket.
            val glow = .7f + .3f * sin(time * 3.1f + seed)
            drawCircle(Color(0xFFFF553E).copy(alpha = .28f * glow), 2.8f * u, h(2.2f, -.8f))
            drawCircle(Color(0xFFFF6B4D).copy(alpha = glow), 1.2f * u, h(2.2f, -.8f))
            drawCircle(Color(0xFF111716), 1.3f * u, h(-1f, -.6f))
            // Slack jaw that opens and closes as it groans.
            val jaw = .6f + .9f * (sin(time * 2.3f + seed * 2f) * .5f + .5f) * settle
            drawOval(Color(0xFF1A1414), h(-.5f, 2f), Size(3.4f * u, jaw * u))
            drawLine(Color(0xFFD8D2B8), h(.6f, 2f), h(.6f, 2.8f), .6f * u)
            // Stitched scar.
            drawLine(Color(0xFF2B302A), h(-3.2f, -3f), h(-.8f, -3.4f), .6f * u)
            for (k in 0..2) drawLine(Color(0xFF2B302A), h(-2.8f + k * .9f, -3.9f), h(-2.8f + k * .9f, -2.6f), .5f * u)
        }

        drawZombieArm(Offset(x - dir * 1.5f * u, top + 11.5f * u), settle, time, seed, skin, cloth, dir)
    }
}

private fun DrawScope.drawZombieArm(shoulder: Offset, settle: Float, time: Float, seed: Float, skin: Color, sleeve: Color, dir: Float) {
    val u = 1.dp.toPx()
    val sway = sin(time * 2f + seed) * 6f
    val upper = mix(-96f, -8f + sway, settle)
    val lower = upper + mix(-4f, 16f + sin(time * 2.6f + seed) * 5f, settle)
    val elbow = along(shoulder, upper, 6 * u, dir)
    val hand = along(elbow, lower, 6 * u, dir)
    drawLine(skin, elbow, hand, 2.3f * u, StrokeCap.Round)
    drawLine(sleeve, shoulder, elbow, 3.1f * u, StrokeCap.Round)
    // Clawing fingers flex while the zombie grabs at the air.
    val flex = 22f + sin(time * 5f + seed) * 10f
    for (k in -1..1) drawLine(skin, hand, along(hand, lower + k * flex, 2.2f * u, dir), .8f * u, StrokeCap.Round)
}
