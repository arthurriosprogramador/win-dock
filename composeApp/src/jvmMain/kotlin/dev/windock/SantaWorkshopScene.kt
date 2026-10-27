package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

private val ElfGreen = Color(0xFF2E8B57)
private val SantaRed = Color(0xFFD42A2A)
private val Fur = Color(0xFFF7F4EE)
private val Skin = Color(0xFFF6CBA6)
private val Wood = Color(0xFF8A5A33)

/** Santa's workshop along the top of the dock: a bench, a toy conveyor, busy elves and Santa checking his list. */
@Composable
internal fun SantaWorkshopScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    val textMeasurer = rememberTextMeasurer()
    val hoHoHo = remember { textMeasurer.measure("Ho ho ho!", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3141B))) }
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val u = 1.dp.toPx()
        val floor = 37 * u
        drawBunting(time)
        // Wooden floor planks resting on the bar.
        drawRect(Color(0xFF7A4E2C), Offset(0f, floor), Size(size.width, 3 * u))
        var plank = 0f
        var k = 0
        while (plank < size.width) {
            drawLine(Color(0xFF5E3A1F), Offset(plank, floor + (k % 2) * 1.5f * u), Offset(plank, floor + 3 * u), .6f * u)
            plank += (16 + (k % 3) * 5) * u
            k++
        }
        drawCandyCane(Offset(10 * u, floor))
        drawWorkbench(Offset(size.width * .18f, floor), time)
        val beltStart = size.width * .3f
        val beltEnd = size.width * .56f
        drawConveyor(beltStart, beltEnd, floor, time)
        drawGiftPile(Offset(size.width * .72f, floor))
        drawCarrierElf(beltEnd + 4 * u, size.width * .72f - 12 * u, floor, time)
        drawSanta(Offset(size.width * .82f, floor), time, hoHoHo)
        drawWorkshopTree(Offset(size.width - 20 * u, floor), time)
    }
}

/** Triangle flags on a sagging string across the top of the strip. */
private fun DrawScope.drawBunting(time: Float) {
    val u = 1.dp.toPx()
    val colors = listOf(SantaRed, ElfGreen, Fur, Color(0xFFF2C14E))
    val span = 140 * u
    fun stringY(x: Float): Float {
        val local = (x % span) / span
        return 2 * u + 4 * u * 4 * local * (1 - local)
    }
    var x = 0f
    while (x < size.width) {
        drawLine(Color(0xFF6B4A2E), Offset(x, stringY(x)), Offset(x + 3 * u, stringY(x + 3 * u)), .6f * u)
        x += 3 * u
    }
    var i = 0
    var fx = 4 * u
    while (fx < size.width) {
        val top = stringY(fx)
        val sway = sin(time * 1.4f + i * .7f) * .8f * u
        val flag = Path().apply { moveTo(fx - 2.5f * u, top); lineTo(fx + 2.5f * u, top); lineTo(fx + sway, top + 5.5f * u); close() }
        drawPath(flag, colors[i % colors.size])
        fx += 9 * u
        i++
    }
}

private fun DrawScope.drawCandyCane(base: Offset) {
    val u = 1.dp.toPx()
    val cane = Path().apply {
        moveTo(base.x, base.y)
        lineTo(base.x, base.y - 22 * u)
        quadraticTo(base.x, base.y - 28 * u, base.x + 4 * u, base.y - 28 * u)
        quadraticTo(base.x + 8 * u, base.y - 28 * u, base.x + 8 * u, base.y - 24 * u)
    }
    drawPath(cane, Fur, style = Stroke(3 * u, cap = StrokeCap.Round))
    var y = base.y - 2 * u
    while (y > base.y - 22 * u) {
        drawLine(SantaRed, Offset(base.x - 1.5f * u, y), Offset(base.x + 1.5f * u, y - 2 * u), 1.1f * u)
        y -= 4 * u
    }
}

/** Bench with a toy rocking horse; an elf hammers it and sparks fly on every strike. */
private fun DrawScope.drawWorkbench(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    val top = base.y - 12 * u
    drawRect(Wood, Offset(base.x - 16 * u, top), Size(32 * u, 2.4f * u))
    drawRect(Color(0xFF6B4527), Offset(base.x - 14 * u, top + 2.4f * u), Size(2 * u, 9.6f * u))
    drawRect(Color(0xFF6B4527), Offset(base.x + 12 * u, top + 2.4f * u), Size(2 * u, 9.6f * u))
    drawRect(Color(0xFF6B4527), Offset(base.x - 14 * u, top + 7 * u), Size(28 * u, 1.2f * u))
    // Rocking horse in progress.
    val horse = Offset(base.x + 4 * u, top)
    val rock = sin(time * 2f) * 4f
    rotate(rock, horse) {
        drawArc(Color(0xFFB3141B), 20f, 140f, false, Offset(horse.x - 6 * u, horse.y - 6 * u), Size(12 * u, 6 * u), style = Stroke(1 * u))
        drawRoundRect(Color(0xFFE9D3A8), Offset(horse.x - 4 * u, horse.y - 7 * u), Size(7 * u, 3 * u), CornerRadius(1.5f * u))
        drawRoundRect(Color(0xFFE9D3A8), Offset(horse.x + 2 * u, horse.y - 10 * u), Size(3 * u, 4.5f * u), CornerRadius(1 * u))
        drawLine(Color(0xFF6B4527), Offset(horse.x + 2.5f * u, horse.y - 10 * u), Offset(horse.x + 1 * u, horse.y - 8 * u), .8f * u)
        drawLine(Color(0xFFE9D3A8), Offset(horse.x - 3 * u, horse.y - 4 * u), Offset(horse.x - 3 * u, horse.y - 1.5f * u), .9f * u)
        drawLine(Color(0xFFE9D3A8), Offset(horse.x + 2 * u, horse.y - 4 * u), Offset(horse.x + 2 * u, horse.y - 1.5f * u), .9f * u)
    }
    // Hammer cycle: slow wind-up, fast strike.
    val cycle = (time / .7f) % 1f
    val angle = if (cycle < .7f) mix(20f, -70f, smooth(cycle / .7f)) else mix(-70f, 20f, (cycle - .7f) / .3f)
    val elfFeet = Offset(base.x - 10 * u, base.y)
    drawElf(elfFeet, facing = 1f, time = time, walking = false, armAngle = angle, holding = Holding.HAMMER, seed = 1f)
    val sinceStrike = (cycle - .98f).let { if (it < 0) it + 1f else it } * .7f
    if (sinceStrike < .25f) {
        val hit = Offset(horse.x - 3 * u, top - 6 * u)
        repeat(5) { s ->
            val dir = -150f + s * 30f
            val p = along(hit, dir, (2 + sinceStrike * 40) * u)
            drawSparkle(p, (1.6f - sinceStrike * 5) * u, 1f - sinceStrike * 4)
        }
    }
}

private enum class Holding { NOTHING, HAMMER, GIFT }

/** An elf: striped stockings, curled shoes, green tunic and a floppy hat with a bell. */
private fun DrawScope.drawElf(feet: Offset, facing: Float, time: Float, walking: Boolean, armAngle: Float, holding: Holding, seed: Float) {
    val u = 1.dp.toPx()
    val phase = time * 2.6f + seed
    val bob = if (walking) abs(sin(phase * TAU / 2)) * .8f * u else 0f
    withTransform({ scale(facing, 1f, feet) }) {
        val hip = Offset(feet.x, feet.y - 6 * u - bob)
        for (side in intArrayOf(1, -1)) {
            val swing = if (walking) sin(phase * TAU / 2) * 2.2f * u * side else side * 1.2f * u
            val foot = Offset(feet.x + swing, feet.y)
            // Red-and-white stockings.
            drawLine(Fur, hip, foot, 1.5f * u)
            drawLine(SantaRed, mix(hip, foot, .25f), mix(hip, foot, .5f), 1.5f * u)
            drawLine(SantaRed, mix(hip, foot, .75f), foot, 1.5f * u)
            val shoe = Path().apply {
                moveTo(foot.x - 1 * u, foot.y); lineTo(foot.x + 2 * u, foot.y)
                quadraticTo(foot.x + 3.2f * u, foot.y - .2f * u, foot.x + 3 * u, foot.y - 1.6f * u)
                lineTo(foot.x - 1 * u, foot.y - 1.2f * u); close()
            }
            drawPath(shoe, Color(0xFF1F5E3A))
        }
        val tunic = Path().apply {
            moveTo(hip.x - 2.5f * u, hip.y - 6 * u); lineTo(hip.x + 2.5f * u, hip.y - 6 * u)
            lineTo(hip.x + 4 * u, hip.y + 1 * u)
            for (z in 0..3) lineTo(hip.x + (3 - z * 2.2f) * u, hip.y + (if (z % 2 == 0) 2.2f else 1f) * u)
            lineTo(hip.x - 4 * u, hip.y + 1 * u); close()
        }
        drawPath(tunic, ElfGreen)
        drawRect(Color(0xFF3A2A1C), Offset(hip.x - 3.4f * u, hip.y - 2 * u), Size(6.8f * u, 1 * u))
        drawRect(Color(0xFFF2C14E), Offset(hip.x - .6f * u, hip.y - 2.1f * u), Size(1.2f * u, 1.2f * u))
        val head = Offset(hip.x + .4f * u, hip.y - 8.8f * u)
        // Pointy ears either side of the head.
        for (side in intArrayOf(-1, 1)) {
            val ear = Path().apply { moveTo(head.x + side * 2 * u, head.y - .6f * u); lineTo(head.x + side * 4.4f * u, head.y - 2.2f * u); lineTo(head.x + side * 2.2f * u, head.y + .8f * u); close() }
            drawPath(ear, Skin)
        }
        drawCircle(Skin, 2.6f * u, head)
        drawCircle(Color(0xFFF08A8A).copy(alpha = .6f), .8f * u, head + Offset(1.4f * u, .9f * u))
        drawCircle(Color(0xFF2A1E16), .4f * u, head + Offset(1.2f * u, -.4f * u))
        // Floppy hat; the bell swings with movement.
        val tipSway = sin(time * 3f + seed) * 1.4f * u
        val hat = Path().apply {
            moveTo(head.x - 3 * u, head.y - 1.2f * u); lineTo(head.x + 3 * u, head.y - 1.2f * u)
            quadraticTo(head.x + 1 * u, head.y - 7 * u, head.x - 4 * u + tipSway, head.y - 7.5f * u)
            close()
        }
        drawPath(hat, ElfGreen)
        drawRect(SantaRed, Offset(head.x - 3 * u, head.y - 2.2f * u), Size(6 * u, 1.1f * u))
        drawCircle(Color(0xFFF2C14E), .9f * u, Offset(head.x - 4 * u + tipSway, head.y - 7.5f * u))
        // Arm and whatever it holds.
        val shoulder = Offset(hip.x + 1 * u, hip.y - 5 * u)
        val hand = along(shoulder, armAngle, 4.5f * u)
        drawLine(ElfGreen, shoulder, hand, 1.4f * u, StrokeCap.Round)
        drawCircle(Skin, .8f * u, hand)
        when (holding) {
            Holding.HAMMER -> {
                val head2 = along(hand, armAngle - 90f, 4 * u)
                drawLine(Color(0xFF8A5A33), hand, head2, .9f * u)
                rotate(armAngle, head2) { drawRect(Color(0xFF7D858C), Offset(head2.x - 1 * u, head2.y - 1.5f * u), Size(2 * u, 3 * u)) }
            }
            Holding.GIFT -> drawGift(Offset(hand.x + 1.5f * u, hand.y - 1 * u), 6 * u, Color(0xFF3E7BD6), SantaRed)
            Holding.NOTHING -> Unit
        }
    }
}

private fun mix(a: Offset, b: Offset, t: Float) = Offset(mix(a.x, b.x, t), mix(a.y, b.y, t))

private fun DrawScope.drawGift(center: Offset, size: Float, box: Color, ribbon: Color) {
    val half = size / 2
    drawRect(box, Offset(center.x - half, center.y - half), Size(size, size))
    drawRect(ribbon, Offset(center.x - size * .1f, center.y - half), Size(size * .2f, size))
    drawRect(ribbon, Offset(center.x - half, center.y - size * .1f), Size(size, size * .2f))
    drawCircle(ribbon, size * .14f, Offset(center.x - size * .14f, center.y - half - size * .08f))
    drawCircle(ribbon, size * .14f, Offset(center.x + size * .14f, center.y - half - size * .08f))
}

/** Conveyor belt with turning rollers carrying toys from the bench towards the gift pile. */
private fun DrawScope.drawConveyor(start: Float, end: Float, floor: Float, time: Float) {
    val u = 1.dp.toPx()
    val beltY = floor - 9 * u
    drawRect(Color(0xFF4A4F57), Offset(start, beltY + 3 * u), Size(1.6f * u, 6 * u))
    drawRect(Color(0xFF4A4F57), Offset(end - 1.6f * u, beltY + 3 * u), Size(1.6f * u, 6 * u))
    drawRoundRect(Color(0xFF2E3238), Offset(start, beltY), Size(end - start, 3.4f * u), CornerRadius(1.7f * u))
    val speed = 8 * u
    // Rollers turn at the belt's speed; spokes make the turning visible.
    var rx = start + 2 * u
    val spin = time * speed / (1.4f * u) * 57.3f
    while (rx < end - 1 * u) {
        val c = Offset(rx, beltY + 1.7f * u)
        drawCircle(Color(0xFF8E959E), 1.4f * u, c)
        rotate(spin, c) { drawLine(Color(0xFF4A4F57), c - Offset(1.2f * u, 0f), c + Offset(1.2f * u, 0f), .5f * u) }
        rx += 6 * u
    }
    val spacing = 24 * u
    val length = end - start
    var item = 0
    while (item * spacing < length + spacing) {
        val x = start + ((time * speed + item * spacing) % (length + spacing)) - spacing / 2
        if (x > start + 2 * u && x < end - 2 * u) {
            val base = Offset(x, beltY)
            when (item % 4) {
                0 -> drawGift(base - Offset(0f, 3 * u), 6 * u, Color(0xFFE8443A), Color(0xFFF2C14E))
                1 -> { // teddy bear
                    drawCircle(Color(0xFFA0683C), 2.6f * u, base - Offset(0f, 2.6f * u))
                    drawCircle(Color(0xFFA0683C), 1.9f * u, base - Offset(0f, 6.4f * u))
                    drawCircle(Color(0xFFA0683C), .8f * u, base - Offset(1.6f * u, 8 * u))
                    drawCircle(Color(0xFFA0683C), .8f * u, base - Offset(-1.6f * u, 8 * u))
                    drawCircle(Color(0xFFE8C9A0), .7f * u, base - Offset(0f, 6 * u))
                }
                2 -> { // ball
                    drawCircle(Color(0xFF3E7BD6), 3 * u, base - Offset(0f, 3 * u))
                    drawArc(Color(0xFFF2C14E), -30f, 60f, false, base - Offset(3 * u, 6 * u), Size(6 * u, 6 * u), style = Stroke(1.2f * u))
                }
                else -> { // toy drum
                    drawRect(SantaRed, Offset(x - 3 * u, beltY - 5 * u), Size(6 * u, 5 * u))
                    drawLine(Color(0xFFF2C14E), Offset(x - 3 * u, beltY - 5 * u), Offset(x + 3 * u, beltY), .6f * u)
                    drawLine(Color(0xFFF2C14E), Offset(x + 3 * u, beltY - 5 * u), Offset(x - 3 * u, beltY), .6f * u)
                    drawOval(Fur, Offset(x - 3 * u, beltY - 6 * u), Size(6 * u, 2 * u))
                }
            }
        }
        item++
    }
}

private fun DrawScope.drawGiftPile(base: Offset) {
    val u = 1.dp.toPx()
    drawGift(Offset(base.x - 6 * u, base.y - 5 * u), 10 * u, Color(0xFF2E8B57), SantaRed)
    drawGift(Offset(base.x + 5 * u, base.y - 4 * u), 8 * u, Color(0xFFE8443A), Color(0xFFF2C14E))
    drawGift(Offset(base.x - 1 * u, base.y - 14 * u), 8 * u, Color(0xFF3E7BD6), Fur)
    drawGift(Offset(base.x + 7 * u, base.y - 11 * u), 6 * u, Color(0xFF9B59B6), Color(0xFFF2C14E))
}

/** An elf fetching presents from the belt's end and carrying them to the pile, then strolling back. */
private fun DrawScope.drawCarrierElf(pickupX: Float, dropX: Float, floor: Float, time: Float) {
    val p = (time / 8f) % 1f
    val (x, facing, carrying) = when {
        p < .1f -> Triple(pickupX, 1f, p > .05f)
        p < .45f -> Triple(mix(pickupX, dropX, smooth((p - .1f) / .35f)), 1f, true)
        p < .55f -> Triple(dropX, 1f, p < .5f)
        p < .9f -> Triple(mix(dropX, pickupX, smooth((p - .55f) / .35f)), -1f, false)
        else -> Triple(pickupX, 1f, false)
    }
    val walking = (p in .1f..<.45f) || (p in .55f..<.9f)
    drawElf(Offset(x, floor), facing, time, walking, armAngle = if (carrying) -45f else 60f + sin(time * 5f) * 10f,
        holding = if (carrying) Holding.GIFT else Holding.NOTHING, seed = 3f)
}

/** Santa checking his list; every few seconds his belly shakes with a "Ho ho ho!". */
private fun DrawScope.drawSanta(feet: Offset, time: Float, hoHoHo: TextLayoutResult) {
    val u = 1.dp.toPx()
    val laughCycle = time % 9f
    val laughing = laughCycle < 2.2f
    val shake = if (laughing) sin(time * 22f) * .8f * u else 0f
    // Boots and trousers.
    for (side in intArrayOf(-1, 1)) {
        drawRect(SantaRed, Offset(feet.x + side * 3 * u - 1.5f * u, feet.y - 8 * u), Size(3 * u, 6 * u))
        drawRoundRect(Color(0xFF1C1A1A), Offset(feet.x + side * 3 * u - 2 * u, feet.y - 3 * u), Size(4.5f * u, 3 * u), CornerRadius(1 * u))
    }
    // Coat and big belly.
    val belly = Offset(feet.x, feet.y - 14 * u + shake)
    drawOval(SantaRed, Offset(belly.x - 8 * u, belly.y - 8 * u), Size(16 * u, 15 * u))
    drawRect(Fur, Offset(belly.x - .9f * u, belly.y - 8 * u), Size(1.8f * u, 14 * u))
    drawRect(Color(0xFF1C1A1A), Offset(belly.x - 8 * u, belly.y + 1 * u), Size(16 * u, 2.4f * u))
    drawRect(Color(0xFFF2C14E), Offset(belly.x - 1.8f * u, belly.y + .6f * u), Size(3.6f * u, 3.2f * u), style = Stroke(.7f * u))
    // Head, beard and hat.
    val head = Offset(feet.x + .5f * u, belly.y - 11 * u)
    drawCircle(Skin, 3.6f * u, head)
    drawOval(Fur, Offset(head.x - 4.4f * u, head.y - .5f * u), Size(8.8f * u, 8 * u))
    drawOval(Fur, Offset(head.x - 2.6f * u, head.y + .2f * u), Size(5.2f * u, 1.6f * u))
    drawCircle(Color(0xFFF08A8A), .9f * u, head + Offset(-1.6f * u, -.4f * u))
    drawCircle(Color(0xFFF08A8A), .9f * u, head + Offset(2.2f * u, -.4f * u))
    val eyesClosed = laughing || (time % 4.3f < .15f)
    for (side in intArrayOf(-1, 1)) {
        val eye = head + Offset(side * 1.3f * u + .3f * u, -1.4f * u)
        if (eyesClosed) drawArc(Color(0xFF2A1E16), 200f, 140f, false, eye - Offset(.7f * u, .4f * u), Size(1.4f * u, 1 * u), style = Stroke(.5f * u))
        else drawCircle(Color(0xFF2A1E16), .45f * u, eye)
    }
    val hat = Path().apply {
        moveTo(head.x - 4 * u, head.y - 2.4f * u); lineTo(head.x + 4 * u, head.y - 2.4f * u)
        quadraticTo(head.x + 2 * u, head.y - 9 * u, head.x + 7 * u, head.y - 6 * u); close()
    }
    drawPath(hat, SantaRed)
    drawRoundRect(Fur, Offset(head.x - 4.6f * u, head.y - 3.6f * u), Size(9.2f * u, 2.4f * u), CornerRadius(1.2f * u))
    drawCircle(Fur, 1.5f * u, Offset(head.x + 7 * u, head.y - 6 * u))
    // The naughty-or-nice list, unrolling down past his knees.
    val hand = Offset(belly.x - 9 * u, belly.y - 2 * u)
    drawLine(SantaRed, Offset(belly.x - 6 * u, belly.y - 5 * u), hand, 2.2f * u, StrokeCap.Round)
    drawCircle(Fur, 1.2f * u, hand)
    val scroll = Path().apply {
        moveTo(hand.x - 3 * u, hand.y - 2 * u); lineTo(hand.x + 1 * u, hand.y - 2 * u)
        lineTo(hand.x + 1.5f * u, feet.y - 2 * u); lineTo(hand.x - 3.5f * u, feet.y - 1 * u); close()
    }
    drawPath(scroll, Color(0xFFF5E6C4))
    for (line in 0..5) drawLine(Color(0xFF9C8A6A), Offset(hand.x - 2.5f * u, hand.y + line * 2.4f * u), Offset(hand.x + .5f * u, hand.y + line * 2.4f * u), .4f * u)
    if (laughing) {
        // Beside the head rather than above it: the strip ends at the top of the hat.
        val bubble = Offset(head.x + 10 * u, head.y - 11 * u)
        val w = hoHoHo.size.width + 8 * u
        val h = hoHoHo.size.height + 4 * u
        drawRoundRect(Color.White, bubble, Size(w, h), CornerRadius(h / 2))
        drawPath(Path().apply { moveTo(bubble.x + 3 * u, bubble.y + h - 2 * u); lineTo(bubble.x - 3 * u, bubble.y + h + 2 * u); lineTo(bubble.x + 7 * u, bubble.y + h - 1 * u); close() }, Color.White)
        drawText(hoHoHo, topLeft = Offset(bubble.x + 4 * u, bubble.y + 2 * u))
    }
}

/** A small decorated tree whose bulbs run the same blink programs as the string lights. */
private fun DrawScope.drawWorkshopTree(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    drawRect(Color(0xFF5B3A24), Offset(base.x - 1.5f * u, base.y - 4 * u), Size(3 * u, 4 * u))
    for (tier in 0..2) {
        val w = (16 - tier * 4) * u
        val bottom = base.y - (3 + tier * 8) * u
        val layer = Path().apply { moveTo(base.x - w / 2, bottom); lineTo(base.x + w / 2, bottom); lineTo(base.x, bottom - 12 * u); close() }
        drawPath(layer, Brush.verticalGradient(listOf(Color(0xFF2E8B57), Color(0xFF1E5E3B)), startY = bottom - 12 * u, endY = bottom))
    }
    listOf(-4f to -6f, 3f to -9f, -2f to -14f, 4f to -17f, -1f to -22f, 2f to -25f).forEachIndexed { i, (dx, dy) ->
        val level = bulbLevel(time, i)
        val c = Offset(base.x + dx * u, base.y + dy * u)
        val color = BulbColors[i % BulbColors.size]
        if (level > .05f) drawCircle(color.copy(alpha = .4f * level), 3 * u, c)
        drawCircle(androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(color, Color.Black, .55f), color, level), 1 * u, c)
    }
    val star = Offset(base.x, base.y - 30 * u)
    drawSparkle(star, (3 + sin(time * 3f) * .6f) * u, 1f)
}
