package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

private enum class Costume { GHOST, WITCH, PUMPKIN, VAMPIRE }

private val CandyColors = listOf(Color(0xFFFF4D8D), Color(0xFF4DD2FF), Color(0xFFFFD23F), Color(0xFF8BE04E))

/** Kids in costumes doing the rounds: they walk past a lit house, and each one gets a sweet tossed into their pail. */
@Composable
internal fun TrickOrTreatScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val u = 1.dp.toPx()
        val base = 37 * u
        repeat(9) { i ->
            val star = Offset(size.width * hash01(i * 4.1f), (3 + hash01(i * 2.3f) * 14) * u)
            drawSparkle(star, (1.6f + hash01(i * 7.7f)) * u * (.7f + .3f * sin(time * 1.3f + i)), .7f)
        }
        drawCrescentMoon(Offset(size.width * .05f, 10 * u), 5.5f * u, Color(0xFFF6E3A8), Color(0xFFFF9F43), time)
        drawBatSwarm(listOf(BatFlight(11.dp, 30.dp, 9.dp, .2f, 4.6f, false), BatFlight(9.dp, 38.dp, 13.dp, .65f, 5.2f, true)), time, Color(0xFF1C1326))

        // Sidewalk the kids walk along, resting on the bar.
        drawRect(Color(0xFF6E6478), Offset(0f, base), Size(size.width, 3 * u))
        var slab = 0f
        while (slab < size.width) {
            drawLine(Color(0xFF574E60), Offset(slab, base), Offset(slab, base + 3 * u), .6f * u)
            slab += 14 * u
        }

        val houseX = size.width * .84f
        val door = drawHauntedHouse(Offset(houseX, base), time)
        listOf(.08f to 4.2f, .27f to 3.4f, .49f to 4.6f, .64f to 3.2f, .95f to 4f).forEachIndexed { i, (at, r) ->
            drawPumpkin(Offset(size.width * at, base), r * u, time, carved = true, seed = i * 1.9f)
        }

        // The group loops across the screen; spacing and speed are in dp so it reads the same on any width.
        val speed = 13 * u
        val spacing = 26 * u
        val track = size.width + 160 * u
        val lead = (time * speed) % track - 60 * u
        Costume.entries.forEachIndexed { i, costume ->
            val x = lead - i * spacing
            val past = x - door.x
            val tossLength = 22 * u
            // Pails fill up once a kid has been past the door on this lap.
            val treats = if (past > tossLength) 3 else 1
            val bucket = drawKid(Offset(x, base), costume, time, i * 1.3f, treats)
            if (past in 0f..tossLength) {
                val p = past / tossLength
                val candy = Offset(mix(door.x, bucket.x, p), mix(door.y, bucket.y, p) - 12 * u * 4 * p * (1 - p))
                rotate(p * 540f, candy) {
                    drawWrappedCandy(candy, CandyColors[i % CandyColors.size])
                }
            }
        }
    }
}

/** A carved jack-o'-lantern or a plain pumpkin sitting on [base]. */
internal fun DrawScope.drawPumpkin(base: Offset, radius: Float, time: Float, carved: Boolean, seed: Float) {
    val u = 1.dp.toPx()
    val center = Offset(base.x, base.y - radius * .85f)
    val light = flicker(time, seed)
    if (carved) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFA931).copy(alpha = .4f * (.5f + .5f * light)), Color(0xFFFFA931).copy(alpha = 0f)),
            center, radius * 3.2f), radius * 3.2f, center)
    }
    val w = radius * 2.2f
    val h = radius * 1.75f
    val body = Color(0xFFF07A1A)
    val shade = Color(0xFFC85A12)
    // Ribs: overlapping ovals, darker towards the sides.
    drawOval(shade, Offset(center.x - w / 2, center.y - h / 2), Size(w * .55f, h))
    drawOval(shade, Offset(center.x + w * -.05f, center.y - h / 2), Size(w * .55f, h))
    drawOval(body, Offset(center.x - w * .32f, center.y - h / 2), Size(w * .64f, h))
    drawOval(Color(0xFFFF9A3C).copy(alpha = .6f), Offset(center.x - w * .12f, center.y - h * .42f), Size(w * .18f, h * .5f))
    drawLine(Color(0xFF4E6B2B), Offset(center.x, center.y - h / 2 + .5f * u), Offset(center.x + 1 * u, center.y - h / 2 - 2.4f * u), 1.3f * u, StrokeCap.Round)
    if (!carved) return
    val glow = lerp(Color(0xFFFFB238), Color(0xFFFFF1A0), light)
    fun tri(a: Offset, b: Offset, c: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
    val r = radius
    drawPath(tri(center + Offset(-r * .55f, -r * .05f), center + Offset(-r * .2f, -r * .05f), center + Offset(-r * .38f, -r * .42f)), glow)
    drawPath(tri(center + Offset(r * .2f, -r * .05f), center + Offset(r * .55f, -r * .05f), center + Offset(r * .38f, -r * .42f)), glow)
    val mouth = Path().apply {
        moveTo(center.x - r * .6f, center.y + r * .2f)
        lineTo(center.x - r * .35f, center.y + r * .38f); lineTo(center.x - r * .15f, center.y + r * .24f)
        lineTo(center.x + r * .05f, center.y + r * .42f); lineTo(center.x + r * .28f, center.y + r * .24f)
        lineTo(center.x + r * .6f, center.y + r * .2f)
        quadraticTo(center.x, center.y + r * .75f, center.x - r * .6f, center.y + r * .2f)
        close()
    }
    drawPath(mouth, glow)
}

private fun DrawScope.drawWrappedCandy(center: Offset, color: Color) {
    val u = 1.dp.toPx()
    drawOval(color, Offset(center.x - 1.8f * u, center.y - 1.2f * u), Size(3.6f * u, 2.4f * u))
    val left = Path().apply { moveTo(center.x - 1.6f * u, center.y); lineTo(center.x - 3.2f * u, center.y - 1.3f * u); lineTo(center.x - 3.2f * u, center.y + 1.3f * u); close() }
    val right = Path().apply { moveTo(center.x + 1.6f * u, center.y); lineTo(center.x + 3.2f * u, center.y - 1.3f * u); lineTo(center.x + 3.2f * u, center.y + 1.3f * u); close() }
    drawPath(left, color)
    drawPath(right, color)
    drawLine(Color.White.copy(alpha = .7f), center + Offset(-.8f * u, -.6f * u), center + Offset(.4f * u, -.6f * u), .5f * u, StrokeCap.Round)
}

/** The house the kids visit: lit windows, a glowing porch lamp, a candy bowl and a cobweb. Returns the door's centre. */
private fun DrawScope.drawHauntedHouse(base: Offset, time: Float): Offset {
    val u = 1.dp.toPx()
    val wallTop = base.y - 22 * u
    val left = base.x - 24 * u
    val right = base.x + 24 * u
    // Roof and walls.
    val roof = Path().apply { moveTo(left - 4 * u, wallTop + 1 * u); lineTo(base.x - 4 * u, base.y - 36 * u); lineTo(right + 4 * u, wallTop + 1 * u); close() }
    drawRect(Color(0xFF3B2F4A), Offset(left, wallTop), Size(right - left, base.y - wallTop))
    var plank = wallTop + 3 * u
    while (plank < base.y) {
        drawLine(Color(0xFF32283F), Offset(left, plank), Offset(right, plank), .6f * u)
        plank += 3 * u
    }
    drawPath(roof, Color(0xFF1E1729))
    drawRect(Color(0xFF2A2136), Offset(base.x + 8 * u, base.y - 39 * u), Size(5 * u, 8 * u))
    // Warm windows; one has a cat silhouette watching the street.
    val light = flicker(time, 4.4f)
    val windowGlow = lerp(Color(0xFFFFB347), Color(0xFFFFD27F), light)
    for ((wx, cat) in listOf(base.x + 6 * u to true, base.x + 15 * u to false)) {
        drawRect(windowGlow, Offset(wx, wallTop + 5 * u), Size(6 * u, 6 * u))
        drawLine(Color(0xFF3B2F4A), Offset(wx + 3 * u, wallTop + 5 * u), Offset(wx + 3 * u, wallTop + 11 * u), .6f * u)
        drawLine(Color(0xFF3B2F4A), Offset(wx, wallTop + 8 * u), Offset(wx + 6 * u, wallTop + 8 * u), .6f * u)
        if (cat) {
            drawCircle(Color(0xFF1B1422), 1.4f * u, Offset(wx + 1.8f * u, wallTop + 9 * u))
            drawOval(Color(0xFF1B1422), Offset(wx + .6f * u, wallTop + 9.4f * u), Size(3 * u, 1.8f * u))
        }
    }
    // Door, porch lamp and step with the candy bowl.
    val doorCenter = Offset(base.x - 10 * u, base.y - 7 * u)
    drawRoundRect(Color(0xFF5A2E1E), Offset(doorCenter.x - 4 * u, base.y - 14 * u), Size(8 * u, 14 * u), CornerRadius(1.5f * u))
    drawCircle(Color(0xFFE8C547), .6f * u, Offset(doorCenter.x + 2.4f * u, doorCenter.y))
    val lamp = Offset(doorCenter.x - 7 * u, base.y - 16 * u)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC266).copy(alpha = .55f * (.6f + .4f * light)), Color(0xFFFFC266).copy(alpha = 0f)), lamp, 14 * u), 14 * u, lamp)
    drawCircle(Color(0xFFFFE08A), 1.5f * u, lamp)
    drawRect(Color(0xFF4B3C58), Offset(doorCenter.x - 7 * u, base.y - 2 * u), Size(14 * u, 2 * u))
    drawArc(Color(0xFF8E5BC2), 0f, 180f, true, Offset(doorCenter.x + 4.5f * u, base.y - 5 * u), Size(6 * u, 5 * u))
    for (k in 0..2) drawCircle(CandyColors[k], .8f * u, Offset(doorCenter.x + (5.8f + k * 1.6f) * u, base.y - 3 * u))
    // Cobweb in the roof corner.
    val corner = Offset(right, wallTop)
    val web = Color.White.copy(alpha = .45f)
    for (a in listOf(180f, 200f, 225f, 250f, 270f)) drawLine(web, corner, along(corner, a, 7 * u), .4f * u)
    for (ring in 1..3) drawArc(web, 180f, 90f, false, corner - Offset(ring * 2.3f * u, ring * 2.3f * u), Size(ring * 4.6f * u, ring * 4.6f * u), style = Stroke(.4f * u))
    return doorCenter
}

/** A kid in costume walking right with a jack-o'-lantern pail. Returns where the pail's opening is. */
private fun DrawScope.drawKid(feet: Offset, costume: Costume, time: Float, seed: Float, treats: Int): Offset {
    val u = 1.dp.toPx()
    val phase = time * 2.2f + seed
    val bob = abs(sin(phase * TAU / 2)) * 1 * u
    val hip = Offset(feet.x, feet.y - 7 * u - bob)
    val shoe = Color(0xFF1E1A22)
    // Legs swing in opposite directions.
    for (side in intArrayOf(1, -1)) {
        val foot = Offset(feet.x + sin(phase * TAU / 2) * 2.4f * u * side, feet.y)
        drawLine(if (costume == Costume.WITCH) Color(0xFF3B8F4A) else Color(0xFF2B2B33), hip, foot, 1.6f * u, StrokeCap.Round)
        drawOval(shoe, Offset(foot.x - 1 * u, foot.y - 1.2f * u), Size(2.6f * u, 1.4f * u))
    }
    val neck = Offset(hip.x, hip.y - 7 * u)
    val head = Offset(hip.x, neck.y - 3 * u)
    val skin = Color(0xFFF2C9A0)
    when (costume) {
        Costume.GHOST -> {
            val sheet = Path().apply {
                moveTo(head.x - 4 * u, head.y)
                arcTo(Rect(head, 4 * u), 180f, 180f, false)
                lineTo(hip.x + 5 * u, hip.y + 2 * u)
                for (k in 0..3) {
                    val x = hip.x + 5 * u - (k + 1) * 2.5f * u
                    quadraticTo(x + 1.25f * u, hip.y + (if (k % 2 == 0) 4f else 1f) * u, x, hip.y + 2 * u)
                }
                close()
            }
            drawPath(sheet, Color(0xFFF4F6FA))
            drawPath(sheet, Color(0xFFB8BCCB), style = Stroke(.6f * u))
            drawOval(Color(0xFF1E1A22), Offset(head.x - 2 * u, head.y - 1 * u), Size(1.4f * u, 2 * u))
            drawOval(Color(0xFF1E1A22), Offset(head.x + .8f * u, head.y - 1 * u), Size(1.4f * u, 2 * u))
        }
        Costume.WITCH -> {
            val dress = Path().apply { moveTo(neck.x - 2 * u, neck.y); lineTo(neck.x + 2 * u, neck.y); lineTo(hip.x + 5 * u, hip.y + 1 * u); lineTo(hip.x - 5 * u, hip.y + 1 * u); close() }
            drawPath(dress, Color(0xFF6B3FA0))
            drawCircle(Color(0xFFB7E08A), 3 * u, head)
            drawCircle(Color(0xFF1E1A22), .45f * u, head + Offset(1 * u, -.3f * u))
            drawOval(Color(0xFF1E1A22), Offset(head.x - 5.5f * u, head.y - 3.2f * u), Size(11 * u, 1.6f * u))
            val hat = Path().apply { moveTo(head.x - 3 * u, head.y - 2.6f * u); lineTo(head.x - 1 * u, head.y - 10 * u); lineTo(head.x + 3 * u, head.y - 2.6f * u); close() }
            drawPath(hat, Color(0xFF1E1A22))
            drawRect(Color(0xFFFFB238), Offset(head.x - 2.4f * u, head.y - 4.2f * u), Size(4.8f * u, 1 * u))
        }
        Costume.PUMPKIN -> {
            drawCircle(Color(0xFFF07A1A), 5 * u, Offset(hip.x, hip.y - 3.5f * u))
            drawLine(Color(0xFFC85A12), Offset(hip.x, hip.y - 8 * u), Offset(hip.x, hip.y + 1 * u), .6f * u)
            drawCircle(skin, 2.8f * u, head + Offset(0f, 1 * u))
            drawCircle(Color(0xFF1E1A22), .45f * u, head + Offset(1 * u, .7f * u))
            drawRoundRect(Color(0xFF4E8B2B), Offset(head.x - 2.6f * u, head.y - 2.4f * u), Size(5.2f * u, 1.8f * u), CornerRadius(.9f * u))
            drawLine(Color(0xFF4E6B2B), Offset(head.x, head.y - 2.4f * u), Offset(head.x + .8f * u, head.y - 4.2f * u), .9f * u, StrokeCap.Round)
        }
        Costume.VAMPIRE -> {
            // The cape billows behind as they walk.
            val flap = sin(phase * TAU) * 1.2f * u
            val cape = Path().apply { moveTo(neck.x - 1 * u, neck.y - 1 * u); lineTo(hip.x - 6 * u + flap, hip.y + 1 * u); lineTo(hip.x + 2 * u, hip.y + 1 * u); close() }
            drawPath(cape, Color(0xFF15121A))
            drawPath(Path().apply { moveTo(neck.x - .5f * u, neck.y); lineTo(hip.x - 4.5f * u + flap, hip.y); lineTo(hip.x, hip.y); close() }, Color(0xFFB01E2E))
            drawRect(Color(0xFFF4F6FA), Offset(neck.x - 1.6f * u, neck.y), Size(3.6f * u, 7 * u))
            drawCircle(Color(0xFFE6E9F0), 3 * u, head)
            drawArc(Color(0xFF15121A), 180f, 180f, true, Offset(head.x - 3.2f * u, head.y - 3.4f * u), Size(6.4f * u, 5 * u))
            drawCircle(Color(0xFFB01E2E), .5f * u, head + Offset(1.1f * u, -.2f * u))
            drawLine(Color.White, head + Offset(.4f * u, 1.6f * u), head + Offset(.4f * u, 2.4f * u), .5f * u)
        }
    }
    // Front arm holding the pail, which swings with the stride.
    val hand = Offset(hip.x + 4 * u, hip.y - 1 * u + sin(phase * TAU) * .6f * u)
    drawLine(if (costume == Costume.GHOST) Color(0xFFF4F6FA) else skin, Offset(neck.x + .5f * u, neck.y + 2 * u), hand, 1.2f * u, StrokeCap.Round)
    val pail = Offset(hand.x + .5f * u, hand.y + 4 * u)
    drawArc(Color(0xFF2B2B33), 180f, 180f, false, pail - Offset(2.6f * u, 4.6f * u), Size(5.2f * u, 5 * u), style = Stroke(.5f * u))
    drawRoundRect(Color(0xFFFF8A1F), pail - Offset(2.8f * u, 2.2f * u), Size(5.6f * u, 4.6f * u), CornerRadius(1.6f * u))
    drawCircle(Color(0xFF3A1A08), .45f * u, pail + Offset(-1 * u, -.4f * u))
    drawCircle(Color(0xFF3A1A08), .45f * u, pail + Offset(1 * u, -.4f * u))
    drawLine(Color(0xFF3A1A08), pail + Offset(-1.2f * u, 1 * u), pail + Offset(1.2f * u, 1 * u), .5f * u)
    repeat(treats) { k -> drawCircle(CandyColors[(k + seed.toInt()) % CandyColors.size], .9f * u, pail + Offset((k - 1) * 1.6f * u, -2.6f * u)) }
    return pail - Offset(0f, 2.6f * u)
}
