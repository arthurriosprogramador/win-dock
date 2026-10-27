package dev.windock

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The cat or dog sitting and facing the viewer, for the acts done "to the camera": the cat licking its paw, the
 * dog panting then barking. Drawn around [feet] (the middle of the ground under it) in the same cat units as the
 * side view, so the two swap seamlessly halfway through the turn.
 */
internal enum class FrontKind { CAT, DOG, JAGUAR }

internal fun DrawScope.drawSittingFront(
    kind: FrontKind, fur: Color, shade: Color, belly: Color, outline: Color,
    feet: Offset, c: Float, time: Float,
    mouth: Float, tongue: Float, bark: Float, breath: Float, lick: Float, tilt: Float, wag: Float,
) {
    val x = feet.x
    val g = feet.y
    val dog = kind == FrontKind.DOG
    val jaguar = kind == FrontKind.JAGUAR
    fun oval(color: Color, center: Offset, w: Float, h: Float) =
        drawOval(color, Offset(center.x - w * c / 2, center.y - h * c / 2), Size(w * c, h * c))
    fun outlined(center: Offset, w: Float, h: Float, fill: Color = fur) {
        oval(outline, center, w + 1.2f, h + 1.2f)
        oval(fill, center, w, h)
    }
    fun at(dx: Float, dy: Float) = Offset(x + dx * c, g - dy * c)

    // Tail: the cat's wraps round in front of its paws; the dog's sticks out behind and wags.
    if (dog) {
        val base = at(4f, 4f)
        val end = along(base, -60f + wag * 1.2f, 8f * c)
        val mid = along(base, -30f + wag * .6f, 4f * c)
        val tail = Path().apply { moveTo(base.x, base.y); quadraticTo(mid.x, mid.y, end.x, end.y) }
        drawPath(tail, outline, style = Stroke(3.6f * c, cap = StrokeCap.Round))
        drawPath(tail, fur, style = Stroke(2.6f * c, cap = StrokeCap.Round))
        drawCircle(belly, 1.1f * c, end)
    }

    // Haunches either side, then the body rising between them, breathing.
    outlined(at(-4.6f, 3.6f), 7.4f, 7f)
    outlined(at(4.6f, 3.6f), 7.4f, 7f)
    val bodyH = 13.5f * (1f + breath)
    val bodyCenter = at(0f, 9f + (bodyH - 13.5f) / 2)
    oval(outline, bodyCenter, 11.2f, bodyH + 1.2f)
    drawOval(
        Brush.verticalGradient(listOf(lerp(fur, Color.White, .14f), fur, lerp(fur, outline, .15f)), bodyCenter.y - bodyH * c / 2, bodyCenter.y + bodyH * c / 2),
        Offset(bodyCenter.x - 5f * c, bodyCenter.y - bodyH * c / 2), Size(10f * c, bodyH * c),
    )
    // The chest: a cream bib, fluffy on the cat.
    oval(belly, at(0f, 10.5f), 6f, 9f)
    if (kind == FrontKind.CAT) for (k in -1..1) {
        val tuft = Path().apply {
            val top = at(k * 1.6f, 13.2f)
            moveTo(top.x - 1f * c, top.y); lineTo(top.x, top.y + 1.6f * c); lineTo(top.x + 1f * c, top.y); close()
        }
        drawPath(tuft, belly)
    }
    if (jaguar) {
        // Rosettes over the flanks and haunches.
        for ((dx, dy) in listOf(-3.8f to 9f, 3.8f to 9f, -3.4f to 12.6f, 3.4f to 12.6f, -6.2f to 4f, 6.2f to 4f, -3.6f to 2.4f, 3.6f to 2.4f, -4.4f to 6.4f, 4.4f to 6.4f)) {
            drawCircle(shade, 1.05f * c, at(dx, dy), style = Stroke(.6f * c))
            drawCircle(lerp(fur, shade, .3f), .35f * c, at(dx, dy))
        }
    }
    if (kind == FrontKind.CAT) {
        // Tabby stripes down the sides.
        for (side in listOf(-1f, 1f)) for (k in 0..1) {
            val y = 7f + k * 3f
            drawLine(shade, at(side * 4.6f, y), at(side * 3.4f, y - .6f), .9f * c, StrokeCap.Round)
        }
    }

    // Front legs; while licking, the right one comes up to the mouth.
    val shoulderY = 12f
    fun frontLeg(side: Float, raise: Float) {
        val shoulder = at(side * 2.2f, shoulderY)
        val rest = at(side * 2.2f, 1f)
        val paw = Offset(mix(rest.x, x + side * 1.4f * c, raise), mix(rest.y, g - 15.6f * c, raise))
        drawLine(outline, shoulder, paw, 3.6f * c, StrokeCap.Round)
        drawLine(fur, shoulder, paw, 2.6f * c, StrokeCap.Round)
        val pawSize = if (raise > .5f) Size(2.8f * c, 2.6f * c) else Size(3.4f * c, 2f * c)
        val pawAt = paw - Offset(pawSize.width / 2, pawSize.height * .6f)
        drawOval(outline, pawAt - Offset(.5f * c, .5f * c), Size(pawSize.width + 1f * c, pawSize.height + 1f * c))
        drawOval(belly, pawAt, pawSize)
        if (raise < .5f) for (t in -1..1) {
            // Toe lines.
            val toe = paw + Offset(t * .8f * c, -.1f * c)
            drawLine(outline.copy(alpha = .55f), toe, toe + Offset(0f, .6f * c), .35f * c)
        }
    }
    frontLeg(-1f, 0f)

    if (!dog) {
        // The tail wrapped round the front, its tip flicking.
        val flick = sin(time * 2.6f) * 1.2f * c
        val tail = Path().apply {
            val start = at(6.4f, 2.4f)
            moveTo(start.x, start.y)
            cubicTo(x + 9f * c, g - .2f * c, x + 2f * c, g + .8f * c, x - 5.6f * c + flick, g - 1.6f * c)
        }
        drawPath(tail, outline, style = Stroke(3.3f * c, cap = StrokeCap.Round))
        drawPath(tail, fur, style = Stroke(2.3f * c, cap = StrokeCap.Round))
        for (k in 0..2) drawCircle(shade, .55f * c, Offset(x + (3.4f - k * 3f) * c, g - (.1f + k * .2f) * c))
        // The jaguar's tail ends in a black tip.
        if (jaguar) drawCircle(shade, 1.2f * c, Offset(x - 5.6f * c + flick, g - 1.6f * c))
    }

    if (dog) {
        // Collar with a tag, centred under the chin.
        drawLine(Color(0xFFD23A3A), at(-3.4f, 14.6f), at(3.4f, 14.6f), 1.5f * c, StrokeCap.Round)
        drawCircle(Color(0xFFF6C744), .95f * c, at(0f, 13.4f))
    }

    // Head, tilted toward the paw while licking; lifted for each bark.
    val head = at(0f, 19.6f + bark * .8f)
    rotate(tilt, at(0f, 15f)) {
        when (kind) {
            FrontKind.DOG -> drawDogFace(head, fur, shade, belly, outline, c, time, mouth, tongue, bark)
            FrontKind.CAT -> drawCatFace(head, fur, shade, belly, outline, c, time, tongue, lick)
            FrontKind.JAGUAR -> drawJaguarFace(head, fur, shade, belly, outline, c, time, mouth)
        }
    }
    // The raised paw goes over the chin, so it's drawn last.
    frontLeg(1f, lick)
}

private fun DrawScope.drawCatFace(head: Offset, fur: Color, shade: Color, belly: Color, outline: Color, c: Float, time: Float, tongue: Float, lick: Float) {
    val r = 6f * c
    fun ear(side: Float) {
        val base = head + Offset(side * 3.6f * c, -3.6f * c)
        rotate(side * 14f, base) {
            val ear = Path().apply { moveTo(base.x - 2.6f * c, base.y + .6f * c); lineTo(base.x, base.y - 4.4f * c); lineTo(base.x + 2.6f * c, base.y + .6f * c); close() }
            drawPath(ear, fur)
            drawPath(ear, outline, style = Stroke(.7f * c))
            val inner = Path().apply { moveTo(base.x - 1.3f * c, base.y); lineTo(base.x, base.y - 2.8f * c); lineTo(base.x + 1.3f * c, base.y); close() }
            drawPath(inner, Color(0xFFF2A7A0))
        }
    }
    ear(-1f)
    ear(1f)
    // Cheek ruff, then the round face.
    for (side in listOf(-1f, 1f)) {
        val ruff = Path().apply {
            moveTo(head.x + side * 4.6f * c, head.y - 1.6f * c); lineTo(head.x + side * 7.2f * c, head.y + 1.6f * c); lineTo(head.x + side * 4f * c, head.y + 3.4f * c); close()
        }
        drawPath(ruff, fur)
        drawPath(ruff, outline, style = Stroke(.6f * c))
    }
    drawCircle(outline, r + .6f * c, head)
    drawCircle(Brush.radialGradient(listOf(lerp(fur, Color.White, .16f), fur), head - Offset(1.5f * c, 2f * c), r * 1.3f), r, head)
    // Forehead stripes.
    for (k in -1..1) drawLine(shade, head + Offset(k * 1.4f * c, -5.4f * c), head + Offset(k * 1.1f * c, -3.4f * c), .9f * c, StrokeCap.Round)
    drawCircle(Color(0xFFFF8A8A).copy(alpha = .3f), 1.2f * c, head + Offset(-3.6f * c, 1.4f * c))
    drawCircle(Color(0xFFFF8A8A).copy(alpha = .3f), 1.2f * c, head + Offset(3.6f * c, 1.4f * c))
    drawOval(belly, head + Offset(-2.8f * c, .4f * c), Size(5.6f * c, 3.4f * c))
    // Eyes: half closed while licking, otherwise the usual blink.
    val blinkPhase = time % 3.1f
    val blink = (if (blinkPhase < .16f) 1f - sin(blinkPhase / .16f * PI.toFloat()) * .9f else 1f) * (1f - lick * .65f)
    for (side in listOf(-1f, 1f)) {
        val eye = head + Offset(side * 2.4f * c, -1f * c)
        drawOval(Color(0xFF3E6B2C), eye - Offset(1f * c, 1.25f * c * blink), Size(2f * c, 2.5f * c * blink))
        if (blink > .45f) {
            drawOval(Color(0xFF1B1B1B), eye - Offset(.35f * c, 1.05f * c * blink), Size(.7f * c, 2.1f * c * blink))
            drawCircle(Color.White, .35f * c, eye + Offset(.35f * c, -.6f * c))
        } else {
            drawLine(outline, eye - Offset(1f * c, 0f), eye + Offset(1f * c, 0f), .5f * c, StrokeCap.Round)
        }
    }
    // Nose, the little "w" mouth, the tongue darting out between licks.
    val nose = Path().apply { moveTo(head.x - .9f * c, head.y + .9f * c); lineTo(head.x + .9f * c, head.y + .9f * c); lineTo(head.x, head.y + 1.8f * c); close() }
    drawPath(nose, Color(0xFFE46F7A))
    drawArc(outline, 0f, 180f, false, topLeft = head + Offset(-1.4f * c, 1.5f * c), size = Size(1.4f * c, 1.2f * c), style = Stroke(.4f * c))
    drawArc(outline, 0f, 180f, false, topLeft = head + Offset(0f, 1.5f * c), size = Size(1.4f * c, 1.2f * c), style = Stroke(.4f * c))
    if (tongue > .05f) drawOval(Color(0xFFE46F7A), head + Offset(-.6f * c, 2.5f * c), Size(1.2f * c, 1.4f * c * tongue))
    for (side in listOf(-1f, 1f)) for (k in -1..1) {
        drawLine(outline.copy(alpha = .55f), head + Offset(side * 2.4f * c, (1.6f + k * .5f) * c), head + Offset(side * 8.6f * c, (1f + k * 1.2f) * c), .3f * c)
    }
}

private fun DrawScope.drawDogFace(head: Offset, fur: Color, shade: Color, belly: Color, outline: Color, c: Float, time: Float, mouth: Float, tongue: Float, bark: Float) {
    val r = 6f * c
    drawCircle(outline, r + .6f * c, head)
    drawCircle(Brush.radialGradient(listOf(lerp(fur, Color.White, .14f), fur), head - Offset(1.5f * c, 2f * c), r * 1.3f), r, head)
    // A white blaze down the middle of the face.
    drawOval(belly, head + Offset(-1.2f * c, -5.4f * c), Size(2.4f * c, 6f * c))
    // Floppy ears, flapping up a little with each bark.
    for (side in listOf(-1f, 1f)) {
        // Hung from the sides of the skull and swung outward, so they frame the face.
        val root = head + Offset(side * 5.2f * c, -3.4f * c)
        rotate(side * (-22f - bark * 24f + sin(time * 3f) * 2f), root) {
            drawOval(outline, root + Offset(-2.2f * c, -.8f * c), Size(4.4f * c, 9.4f * c))
            drawOval(shade, root + Offset(-1.8f * c, -.4f * c), Size(3.6f * c, 8.6f * c))
        }
    }
    // Eyes with brows that lift when barking.
    val blink = if ((time % 3.7f) < .14f) .2f else 1f
    for (side in listOf(-1f, 1f)) {
        val eye = head + Offset(side * 2.3f * c, -1.2f * c)
        drawOval(Color(0xFF2A1B10), eye - Offset(.9f * c, 1.05f * c * blink), Size(1.8f * c, 2.1f * c * blink))
        if (blink > .5f) drawCircle(Color.White, .38f * c, eye + Offset(.35f * c, -.5f * c))
        drawCircle(lerp(shade, outline, .3f), .5f * c, eye + Offset(side * .2f * c, (-2.2f - bark * .8f) * c))
    }
    // Muzzle, open mouth with the tongue lolling out while panting.
    drawOval(belly, head + Offset(-3.2f * c, .2f * c), Size(6.4f * c, 4.6f * c))
    if (mouth > .05f) {
        val open = (1.2f + 2.6f * mouth) * c
        drawOval(Color(0xFF5A1414), head + Offset(-2.2f * c, 2.6f * c), Size(4.4f * c, open))
        if (tongue > .05f) {
            val length = (2.2f + 2.8f * tongue + sin(time * 16f) * .3f) * c
            drawOval(Color(0xFFE5707E), head + Offset(-1.2f * c, 2.8f * c), Size(2.4f * c, length))
            drawLine(Color(0xFFC24E5C), head + Offset(0f, 3.2f * c), head + Offset(0f, 2.8f * c + length * .8f), .3f * c)
        }
    } else {
        drawLine(outline, head + Offset(0f, 2f * c), head + Offset(0f, 2.8f * c), .4f * c)
        drawArc(outline, 0f, 180f, false, topLeft = head + Offset(-1.6f * c, 2.2f * c), size = Size(1.6f * c, 1.2f * c), style = Stroke(.4f * c))
        drawArc(outline, 0f, 180f, false, topLeft = head + Offset(0f, 2.2f * c), size = Size(1.6f * c, 1.2f * c), style = Stroke(.4f * c))
    }
    drawOval(Color(0xFF241812), head + Offset(-1.4f * c, .4f * c), Size(2.8f * c, 2f * c))
    drawCircle(Color.White.copy(alpha = .6f), .35f * c, head + Offset(-.5f * c, .8f * c))
    // "Woof": waves either side of the head.
    if (bark > .05f) for (side in listOf(-1f, 1f)) for (k in 0..1) {
        val radius = (3f + k * 2.2f) * c
        val center = head + Offset(side * 9.6f * c, -1f * c)
        drawArc(
            outline.copy(alpha = bark * (1f - k * .3f)), if (side > 0) -40f else 140f, 80f, false,
            topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2), style = Stroke(.7f * c, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawJaguarFace(head: Offset, fur: Color, shade: Color, belly: Color, outline: Color, c: Float, time: Float, roar: Float) {
    val r = 6.2f * c
    // Round ears with black backs.
    for (side in listOf(-1f, 1f)) {
        val ear = head + Offset(side * 4.4f * c, -4.6f * c)
        drawCircle(outline, 2.2f * c, ear)
        drawCircle(shade, 1.8f * c, ear)
        drawCircle(lerp(fur, belly, .5f), 1f * c, ear + Offset(0f, .3f * c))
    }
    drawCircle(outline, r + .6f * c, head)
    drawCircle(Brush.radialGradient(listOf(lerp(fur, Color.White, .16f), fur), head - Offset(1.5f * c, 2f * c), r * 1.3f), r, head)
    // Spots across the brow and cheeks.
    for ((dx, dy) in listOf(-2.2f to -4.2f, 0f to -4.8f, 2.2f to -4.2f, -1.1f to -3f, 1.1f to -3f, -4.6f to -.6f, 4.6f to -.6f, -4.2f to 1.6f, 4.2f to 1.6f)) {
        drawCircle(shade, .55f * c, head + Offset(dx * c, dy * c))
    }
    // A wide pale muzzle.
    drawOval(belly, head + Offset(-3.4f * c, -.2f * c), Size(6.8f * c, 4.6f * c))
    // Eyes narrow into a scowl as the roar opens up.
    val blinkPhase = time % 3.4f
    val blink = if (blinkPhase < .15f) .2f else 1f
    val open = blink * (1f - roar * .45f)
    for (side in listOf(-1f, 1f)) {
        val eye = head + Offset(side * 2.4f * c, -1.4f * c)
        drawOval(Color(0xFFD99A1F), eye - Offset(1f * c, 1.1f * c * open), Size(2f * c, 2.2f * c * open))
        if (open > .35f) {
            drawCircle(Color(0xFF1B1B1B), .55f * c * open, eye)
            drawCircle(Color.White, .3f * c, eye + Offset(.35f * c, -.5f * c))
        }
        // Brow, angled down toward the nose while roaring.
        drawLine(outline, eye + Offset(-side * 1.3f * c, -(1.5f + roar * .2f) * c), eye + Offset(side * 1.2f * c, -(1.5f - roar * .9f) * c) * 1f, .6f * c, StrokeCap.Round)
    }
    // The roar: a wide-open mouth with fangs and a tongue, the head shaking with it.
    if (roar > .03f) {
        val height = (1.2f + 4.6f * roar) * c
        val top = head.y + 2.2f * c
        drawOval(outline, Offset(head.x - 2.9f * c, top - .5f * c), Size(5.8f * c, height + 1f * c))
        drawOval(Color(0xFF5A1414), Offset(head.x - 2.4f * c, top), Size(4.8f * c, height))
        drawOval(Color(0xFFE07A86), Offset(head.x - 1.6f * c, top + height * .5f), Size(3.2f * c, height * .45f))
        for (side in listOf(-1f, 1f)) {
            val fang = Path().apply {
                moveTo(head.x + side * 1.9f * c, top + .2f * c); lineTo(head.x + side * 1.3f * c, top + (.2f + 1.6f * roar) * c); lineTo(head.x + side * .8f * c, top + .4f * c); close()
            }
            drawPath(fang, Color.White)
            val lower = Path().apply {
                moveTo(head.x + side * 1.7f * c, top + height); lineTo(head.x + side * 1.2f * c, top + height - 1.2f * roar * c); lineTo(head.x + side * .8f * c, top + height); close()
            }
            drawPath(lower, Color.White)
        }
    } else {
        drawArc(outline, 0f, 180f, false, topLeft = head + Offset(-1.5f * c, 1.6f * c), size = Size(1.5f * c, 1.2f * c), style = Stroke(.45f * c))
        drawArc(outline, 0f, 180f, false, topLeft = head + Offset(0f, 1.6f * c), size = Size(1.5f * c, 1.2f * c), style = Stroke(.45f * c))
    }
    val nose = Path().apply { moveTo(head.x - 1.2f * c, head.y + .4f * c); lineTo(head.x + 1.2f * c, head.y + .4f * c); lineTo(head.x, head.y + 1.8f * c); close() }
    drawPath(nose, Color(0xFF4A2E18))
    for (side in listOf(-1f, 1f)) for (k in -1..1) {
        drawLine(outline.copy(alpha = .5f), head + Offset(side * 2.8f * c, (1.4f + k * .5f) * c), head + Offset(side * 9f * c, (.8f + k * 1.2f) * c), .3f * c)
    }
}

/**
 * The western dragon turned to face the viewer: sitting up on its haunches, wings spread wide on both sides,
 * breathing fire straight out of the screen — the flames swell as they "come closer" and burst outward.
 */
internal fun DrawScope.drawDragonFront(
    fur: Color, shade: Color, belly: Color, outline: Color, feet: Offset, c: Float, time: Float,
    mouth: Float, wings: Float, fire: Float,
) {
    val x = feet.x
    val g = feet.y
    fun at(dx: Float, dy: Float) = Offset(x + dx * c, g - dy * c)
    fun oval(color: Color, center: Offset, w: Float, h: Float) =
        drawOval(color, Offset(center.x - w * c / 2, center.y - h * c / 2), Size(w * c, h * c))
    fun outlined(center: Offset, w: Float, h: Float, fill: Color = fur) {
        oval(outline, center, w + 1.2f, h + 1.2f)
        oval(fill, center, w, h)
    }

    // Wings behind everything, mirrored; they beat slowly while spread.
    val flap = sin(time * 7f) * 1.2f * wings
    for (side in listOf(-1f, 1f)) {
        fun p(fx: Float, fy: Float, sx: Float, sy: Float) = at(side * mix(fx, sx, wings), mix(fy, sy, wings) + flap * (mix(fy, sy, wings) / 22f))
        val root = at(side * 3f, 14f)
        val wrist = p(5f, 17f, 10f, 23f)
        val tips = listOf(p(7f, 16f, 17f, 23f), p(7.5f, 13f, 18f, 15f), p(7f, 10f, 15.5f, 8f))
        val flank = p(5f, 9f, 6f, 8f)
        val membrane = Path().apply {
            moveTo(root.x, root.y); lineTo(wrist.x, wrist.y); lineTo(tips[0].x, tips[0].y)
            val edge = tips + flank
            for (k in 1 until edge.size) {
                val a = edge[k - 1]
                val e = edge[k]
                val mid = Offset((a.x + e.x) / 2, (a.y + e.y) / 2)
                val sag = mid + (wrist - mid) * .22f
                quadraticTo(sag.x, sag.y, e.x, e.y)
            }
            close()
        }
        drawPath(membrane, lerp(shade, fur, .25f))
        drawPath(membrane, outline, style = Stroke(.6f * c))
        for (tip in tips) drawLine(outline, wrist, tip, .7f * c, StrokeCap.Round)
        drawLine(outline, root, wrist, 1.5f * c, StrokeCap.Round)
        drawCircle(Color(0xFFF2EAD3), .6f * c, wrist)
    }

    // Tail curling out to one side on the ground, ending in a spade.
    val sway = sin(time * 1.4f) * 1.4f * c
    val tail = Path().apply {
        val start = at(4f, 3f)
        moveTo(start.x, start.y)
        cubicTo(x + 10f * c, g - 1f * c, x + 14f * c, g - 2f * c + sway, x + 16f * c, g - 6f * c + sway)
    }
    drawPath(tail, outline, style = Stroke(4.4f * c, cap = StrokeCap.Round))
    drawPath(tail, fur, style = Stroke(3.4f * c, cap = StrokeCap.Round))
    val tip = Offset(x + 16f * c, g - 6f * c + sway)
    val spade = Path().apply {
        moveTo(tip.x, tip.y - 3.4f * c); lineTo(tip.x + 2.4f * c, tip.y + .4f * c); lineTo(tip.x, tip.y + 1.6f * c); lineTo(tip.x - 2.4f * c, tip.y + .4f * c); close()
    }
    drawPath(spade, shade)
    drawPath(spade, outline, style = Stroke(.6f * c))

    // Haunches, body and the plated belly.
    outlined(at(-5f, 3.8f), 8f, 7.6f)
    outlined(at(5f, 3.8f), 8f, 7.6f)
    oval(outline, at(0f, 9.5f), 12.4f, 15f)
    drawOval(
        Brush.verticalGradient(listOf(lerp(fur, Color.White, .14f), fur, lerp(fur, outline, .18f)), g - 17f * c, g - 2f * c),
        Offset(x - 5.6f * c, g - 16.4f * c), Size(11.2f * c, 13.8f * c),
    )
    oval(belly, at(0f, 9f), 6.6f, 12f)
    for (k in 0..4) {
        val y = 4.4f + k * 2.2f
        drawLine(lerp(belly, outline, .35f), at(-2.6f, y), at(2.6f, y), .45f * c)
    }
    // Front legs with claws.
    for (side in listOf(-1f, 1f)) {
        val shoulder = at(side * 3.4f, 12f)
        val paw = at(side * 3.4f, 1f)
        drawLine(outline, shoulder, paw, 4.2f * c, StrokeCap.Round)
        drawLine(fur, shoulder, paw, 3.2f * c, StrokeCap.Round)
        oval(outline, paw + Offset(0f, -.4f * c), 4.4f, 2.6f)
        oval(Color(0xFFF2EAD3), paw + Offset(0f, -.4f * c), 3.4f, 1.8f)
        for (t in -1..1) {
            val claw = paw + Offset(t * 1f * c, .4f * c)
            drawLine(Color(0xFF2B2B2B), claw, claw + Offset(0f, .7f * c), .5f * c, StrokeCap.Round)
        }
    }

    // Neck and head. Horns sweep up and out; the jaw drops for the fire.
    oval(outline, at(0f, 17.4f), 6.4f, 6f)
    oval(fur, at(0f, 17.4f), 5.2f, 5f)
    val head = at(0f, 22.4f)
    for (side in listOf(-1f, 1f)) {
        val base = head + Offset(side * 3f * c, -2.6f * c)
        val horn = Path().apply { moveTo(base.x, base.y); quadraticTo(base.x + side * 2.6f * c, base.y - 3.4f * c, base.x + side * 5.2f * c, base.y - 4.2f * c) }
        drawPath(horn, outline, style = Stroke(2f * c, cap = StrokeCap.Round))
        drawPath(horn, Color(0xFFF2EAD3), style = Stroke(1.3f * c, cap = StrokeCap.Round))
        // Little frills at the cheeks.
        val frill = Path().apply {
            moveTo(head.x + side * 4.2f * c, head.y - 1f * c); lineTo(head.x + side * 6.6f * c, head.y + .4f * c); lineTo(head.x + side * 4.2f * c, head.y + 1.6f * c); close()
        }
        drawPath(frill, shade)
        drawPath(frill, outline, style = Stroke(.5f * c))
    }
    oval(outline, head, 10.2f, 8.4f)
    drawOval(
        Brush.radialGradient(listOf(lerp(fur, Color.White, .16f), fur), head - Offset(1.4f * c, 1.8f * c), 6f * c),
        Offset(head.x - 4.5f * c, head.y - 3.6f * c), Size(9f * c, 7.2f * c),
    )
    // Snout, nostrils glowing as the fire builds.
    val snout = head + Offset(0f, 2.2f * c)
    oval(outline, snout, 7.4f, 4.6f)
    oval(lerp(fur, belly, .25f), snout, 6.4f, 3.6f)
    for (side in listOf(-1f, 1f)) {
        val nostril = snout + Offset(side * 1.3f * c, -.5f * c)
        drawCircle(outline, .55f * c, nostril)
        if (fire > .05f || mouth > .2f) drawCircle(Color(0xFFFFB13B).copy(alpha = max(fire, mouth * .6f)), .4f * c, nostril)
    }
    // Eyes: golden, slit pupils, heavy brows.
    for (side in listOf(-1f, 1f)) {
        val eye = head + Offset(side * 2.6f * c, -1f * c)
        oval(Color(0xFFF7D046), eye, 2.4f, 1.8f)
        drawLine(Color(0xFF1B1B1B), eye - Offset(0f, .8f * c), eye + Offset(0f, .8f * c), .5f * c)
        drawLine(outline, eye + Offset(-side * 1.6f * c, -1.6f * c), eye + Offset(side * 1.4f * c, -.9f * c), .8f * c, StrokeCap.Round)
    }
    // The open jaw below the snout, with teeth.
    val jawTop = snout.y + 1.4f * c
    if (mouth > .03f) {
        val height = (1f + 3.4f * mouth) * c
        drawOval(outline, Offset(snout.x - 3f * c, jawTop - .5f * c), Size(6f * c, height + 1f * c))
        drawOval(Color(0xFF6B1A0E), Offset(snout.x - 2.5f * c, jawTop), Size(5f * c, height))
        for (k in -1..1) {
            val tx = snout.x + k * 1.5f * c
            val tooth = Path().apply { moveTo(tx - .45f * c, jawTop + .2f * c); lineTo(tx, jawTop + (.2f + 1f * mouth) * c); lineTo(tx + .45f * c, jawTop + .2f * c); close() }
            drawPath(tooth, Color.White)
        }
    }
    if (fire > .01f) drawFireAtViewer(Offset(snout.x, jawTop + 1.4f * c), fire, time, c)
}

/** Flames coming out of the screen: a hot core, then fireballs that swell and spread as they "approach". */
private fun DrawScope.drawFireAtViewer(mouth: Offset, strength: Float, time: Float, c: Float) {
    val reach = 19f * c * strength
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFC233).copy(alpha = .45f * strength), Color(0xFFFF6A1F).copy(alpha = .18f * strength), Color.Transparent), mouth, reach * 1.5f),
        reach * 1.5f, mouth,
    )
    for (i in 0 until 42) {
        val life = (time * 1.7f + i / 42f) % 1f
        val angle = hash01(i * 7.31f) * TAU
        val spread = mix(.35f, 1f, hash01(i * 3.17f + 1f))
        val pos = mouth + Offset(kotlin.math.cos(angle) * spread * reach * life, (kotlin.math.sin(angle) * .7f * spread * life + life * .25f) * reach)
        val radius = mix(1.2f, 6.6f, life) * c * strength
        val color = when {
            life < .3f -> lerp(Color(0xFFFFF6B0), Color(0xFFFFC233), life / .3f)
            else -> lerp(Color(0xFFFF8A1F), Color(0xFFD9301C), (life - .3f) / .7f)
        }
        drawCircle(color.copy(alpha = (1f - life) * .8f * strength), radius, pos)
    }
    // The white-hot centre, pulsing.
    drawCircle(Color(0xFFFFF6D0).copy(alpha = .9f * strength), (2.4f + sin(time * 22f) * .4f) * c * strength, mouth)
    // Embers flung outward.
    for (i in 0 until 10) {
        val life = (time * 1.2f + i / 10f) % 1f
        val angle = hash01(i * 11.7f + 4f) * TAU
        val ember = mouth + Offset(kotlin.math.cos(angle), kotlin.math.sin(angle) * .7f + life * .6f) * (reach * (.6f + life * 1.2f))
        drawCircle(Color(0xFFFFA43A).copy(alpha = (1f - life) * strength), .6f * c, ember)
    }
}
