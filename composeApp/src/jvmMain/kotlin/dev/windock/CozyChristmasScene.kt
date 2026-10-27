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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** A warm living room on Christmas Eve: a crackling fire, a blinking tree, snow at the window and a sleeping cat. */
@Composable
internal fun CozyChristmasScene(modifier: Modifier = Modifier) {
    val time by rememberSceneClock()
    val textMeasurer = rememberTextMeasurer()
    val zzz = remember { textMeasurer.measure("z", TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8E2F4))) }
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val u = 1.dp.toPx()
        val floor = 37 * u
        drawCeilingLights(time)
        drawWoodFloor(floor)
        val fireX = size.width * .78f
        // The fire's light spills across the rug and floor in front of the hearth.
        val pulse = .75f + .25f * flicker(time, 9f)
        drawOval(Brush.radialGradient(listOf(Color(0xFFFF9A3C).copy(alpha = .45f * pulse), Color(0xFFFF9A3C).copy(alpha = 0f)), Offset(fireX, floor), 60 * u),
            Offset(fireX - 60 * u, floor - 6 * u), Size(120 * u, 12 * u))
        drawOval(Color(0xFF8E2B2B), Offset(fireX - 26 * u, floor - 1.5f * u), Size(52 * u, 4 * u))
        drawOval(Color(0xFFB5453A), Offset(fireX - 22 * u, floor - 1 * u), Size(44 * u, 2.6f * u), style = Stroke(.6f * u))

        drawCozyTree(Offset(size.width * .1f, floor), time)
        drawSnowyWindow(Offset(size.width * .3f, floor - 12 * u), time)
        drawArmchair(Offset(size.width * .5f, floor), time, zzz)
        drawCocoa(Offset(size.width * .5f + 26 * u, floor), time)
        drawFireplace(Offset(fireX, floor), time)
        drawLogStack(Offset(size.width * .92f, floor))
    }
}

private fun DrawScope.drawWoodFloor(floor: Float) {
    val u = 1.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8A5634), Color(0xFF5E3820)), startY = floor, endY = floor + 3 * u), Offset(0f, floor), Size(size.width, 3 * u))
    var x = 0f
    var k = 0
    while (x < size.width) {
        drawLine(Color(0xFF4A2C18), Offset(x, floor + (k % 2) * 1.5f * u), Offset(x, floor + 3 * u), .5f * u)
        x += (18 + (k % 3) * 6) * u
        k++
    }
}

/** A sagging string of bulbs along the top of the room, on the same blink programs as the tree. */
private fun DrawScope.drawCeilingLights(time: Float) {
    val u = 1.dp.toPx()
    val span = 110 * u
    fun wireY(x: Float): Float {
        val local = (x % span) / span
        return 1.5f * u + 4 * u * 4 * local * (1 - local)
    }
    val wire = Path().apply {
        moveTo(0f, wireY(0f))
        var x = 0f
        while (x <= size.width) { lineTo(x, wireY(x)); x += 3 * u }
    }
    drawPath(wire, Color(0xFF1E3B2A), style = Stroke(.7f * u))
    var i = 0
    var x = 6 * u
    while (x < size.width) {
        val level = bulbLevel(time, i + 40)
        val color = BulbColors[i % BulbColors.size]
        val c = Offset(x, wireY(x) + 2 * u)
        if (level > .05f) drawCircle(color.copy(alpha = .35f * level), 3.2f * u, c)
        drawOval(lerp(lerp(color, Color.Black, .55f), color, level), Offset(c.x - 1 * u, c.y - 1.4f * u), Size(2 * u, 2.8f * u))
        x += 11 * u
        i++
    }
}

private fun DrawScope.drawCozyTree(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    drawRect(Color(0xFF5B3A24), Offset(base.x - 2 * u, base.y - 4 * u), Size(4 * u, 4 * u))
    drawRect(Color(0xFFB3141B), Offset(base.x - 4 * u, base.y - 3 * u), Size(8 * u, 3 * u))
    val tiers = 4
    for (tier in 0 until tiers) {
        val w = (24 - tier * 5) * u
        val bottom = base.y - (3 + tier * 7.5f) * u
        val layer = Path().apply {
            moveTo(base.x - w / 2, bottom)
            // Scalloped bottom edge for each tier of branches.
            for (s in 0..3) quadraticTo(base.x - w / 2 + (s + .5f) * w / 4, bottom + 1.5f * u, base.x - w / 2 + (s + 1) * w / 4, bottom)
            lineTo(base.x, bottom - 11 * u)
            close()
        }
        drawPath(layer, Brush.verticalGradient(listOf(Color(0xFF2F9160), Color(0xFF1B5A3A)), startY = bottom - 11 * u, endY = bottom))
    }
    // Gold tinsel swags.
    for (swag in 0..2) {
        val y = base.y - (8 + swag * 8) * u
        val w = (18 - swag * 4.5f) * u
        drawArc(Color(0xFFF2C14E), 20f, 140f, false, Offset(base.x - w / 2, y - 3 * u), Size(w, 5 * u), style = Stroke(.7f * u))
    }
    // Baubles stay lit; the bulbs blink.
    listOf(-7f to -7f, 6f to -10f, -3f to -16f, 4f to -20f, -1f to -26f).forEachIndexed { i, (dx, dy) ->
        drawCircle(listOf(Color(0xFFD42A2A), Color(0xFF3E7BD6), Color(0xFFF2C14E))[i % 3], 1.3f * u, Offset(base.x + dx * u, base.y + dy * u))
    }
    listOf(-9f to -5f, -2f to -9f, 8f to -6f, 5f to -14f, -6f to -13f, 1f to -19f, -4f to -22f, 3f to -25f, 0f to -30f).forEachIndexed { i, (dx, dy) ->
        val level = bulbLevel(time, i)
        val color = BulbColors[i % BulbColors.size]
        val c = Offset(base.x + dx * u, base.y + dy * u)
        if (level > .05f) drawCircle(color.copy(alpha = .45f * level), 2.6f * u, c)
        drawCircle(lerp(lerp(color, Color.Black, .55f), color, level), .9f * u, c)
    }
    val star = Offset(base.x, base.y - 34.5f * u)
    drawCircle(Color(0xFFFFE08A).copy(alpha = .35f + .2f * sin(time * 2.5f)), 5 * u, star)
    drawSparkle(star, (3.2f + sin(time * 2.5f) * .5f) * u, 1f)
    // Presents underneath.
    drawPresent(Offset(base.x - 11 * u, base.y), 7 * u, 5 * u, Color(0xFFB3141B), Color(0xFFF2C14E))
    drawPresent(Offset(base.x + 10 * u, base.y), 6 * u, 6 * u, Color(0xFF2E6FD6), Color.White)
    drawPresent(Offset(base.x + 16 * u, base.y), 5 * u, 3.6f * u, Color(0xFF9B59B6), Color(0xFFF2C14E))
}

private fun DrawScope.drawPresent(baseCenter: Offset, width: Float, height: Float, box: Color, ribbon: Color) {
    val u = 1.dp.toPx()
    drawRect(box, Offset(baseCenter.x - width / 2, baseCenter.y - height), Size(width, height))
    drawRect(ribbon, Offset(baseCenter.x - .5f * u, baseCenter.y - height), Size(1 * u, height))
    drawCircle(ribbon, .9f * u, Offset(baseCenter.x - .9f * u, baseCenter.y - height - .5f * u))
    drawCircle(ribbon, .9f * u, Offset(baseCenter.x + .9f * u, baseCenter.y - height - .5f * u))
}

/** A window onto a snowy night, framed by curtains, with snow piling on the sill. */
private fun DrawScope.drawSnowyWindow(sillCenter: Offset, time: Float) {
    val u = 1.dp.toPx()
    val glass = Rect(sillCenter.x - 12 * u, sillCenter.y - 20 * u, sillCenter.x + 12 * u, sillCenter.y)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFF34507E)), startY = glass.top, endY = glass.bottom), glass.topLeft, glass.size)
    clipRect(glass.left, glass.top, glass.right, glass.bottom) {
        drawCircle(Color(0xFFF4F1DE), 2.2f * u, Offset(glass.right - 5 * u, glass.top + 5 * u))
        repeat(16) { i ->
            val fall = (5 + hash01(i * 1.9f) * 5) * u
            val y = glass.top + ((time * fall + hash01(i * 3.1f) * 26 * u) % (24 * u)) - 2 * u
            val x = glass.left + hash01(i * 5.3f) * glass.width + sin(time * 1.2f + i) * 1.4f * u
            drawCircle(Color.White.copy(alpha = .9f), (.5f + hash01(i * 7.1f) * .5f) * u, Offset(x, y))
        }
        drawRect(Color.White, Offset(glass.left, glass.bottom - 2 * u), Size(glass.width, 2 * u))
    }
    val frame = Color(0xFFEDE3D1)
    drawRect(frame, glass.topLeft, glass.size, style = Stroke(1.4f * u))
    drawLine(frame, Offset(sillCenter.x, glass.top), Offset(sillCenter.x, glass.bottom), 1 * u)
    drawLine(frame, Offset(glass.left, glass.top + 10 * u), Offset(glass.right, glass.top + 10 * u), 1 * u)
    drawRect(frame, Offset(glass.left - 2 * u, glass.bottom), Size(glass.width + 4 * u, 1.6f * u))
    // Curtains swagged to the sides.
    for (side in intArrayOf(-1, 1)) {
        val edge = if (side < 0) glass.left else glass.right
        val curtain = Path().apply {
            moveTo(edge - side * 4 * u, glass.top - 2 * u)
            lineTo(edge + side * 4 * u, glass.top - 2 * u)
            quadraticTo(edge + side * 1 * u, glass.top + 12 * u, edge + side * 3 * u, glass.bottom + 2 * u)
            lineTo(edge - side * 4 * u, glass.bottom + 2 * u)
            close()
        }
        drawPath(curtain, Color(0xFF9E2A2B))
        drawLine(Color(0xFF7A1E20), Offset(edge, glass.top), Offset(edge + side * 1.5f * u, glass.bottom), .6f * u)
    }
    drawLine(Color(0xFF5B3A24), Offset(glass.left - 6 * u, glass.top - 2 * u), Offset(glass.right + 6 * u, glass.top - 2 * u), 1.2f * u, StrokeCap.Round)
}

/** An armchair with a throw and a cat asleep on it, breathing slowly while z's drift up. */
private fun DrawScope.drawArmchair(base: Offset, time: Float, zzz: TextLayoutResult) {
    val u = 1.dp.toPx()
    val fabric = Color(0xFF2F6B5A)
    val dark = Color(0xFF214D41)
    drawRoundRect(fabric, Offset(base.x - 12 * u, base.y - 24 * u), Size(24 * u, 14 * u), CornerRadius(5 * u))
    drawRoundRect(dark, Offset(base.x - 14 * u, base.y - 14 * u), Size(5 * u, 11 * u), CornerRadius(2.5f * u))
    drawRoundRect(dark, Offset(base.x + 9 * u, base.y - 14 * u), Size(5 * u, 11 * u), CornerRadius(2.5f * u))
    drawRoundRect(fabric, Offset(base.x - 10 * u, base.y - 11 * u), Size(20 * u, 6 * u), CornerRadius(2 * u))
    drawRect(Color(0xFF3E2618), Offset(base.x - 11 * u, base.y - 3 * u), Size(1.6f * u, 3 * u))
    drawRect(Color(0xFF3E2618), Offset(base.x + 9.4f * u, base.y - 3 * u), Size(1.6f * u, 3 * u))
    // Plaid throw over the back.
    val throwRect = Rect(base.x - 9 * u, base.y - 23 * u, base.x + 1 * u, base.y - 15 * u)
    drawRect(Color(0xFFB3141B), throwRect.topLeft, throwRect.size)
    for (k in 1..3) {
        drawLine(Color(0xFFE8C547).copy(alpha = .7f), Offset(throwRect.left + k * 2.5f * u, throwRect.top), Offset(throwRect.left + k * 2.5f * u, throwRect.bottom), .5f * u)
        drawLine(Color(0xFF6E0E12), Offset(throwRect.left, throwRect.top + k * 2 * u), Offset(throwRect.right, throwRect.top + k * 2 * u), .5f * u)
    }
    // Sleeping cat, curled up; its body swells a little with each breath.
    val breath = 1f + .06f * sin(time * 1.6f)
    val cat = Offset(base.x + 2 * u, base.y - 13 * u)
    drawOval(Color(0xFFEC8A2F), Offset(cat.x - 5.5f * u, cat.y - 3 * u * breath), Size(11 * u, 4.4f * u * breath))
    drawArc(Color(0xFFEC8A2F), 0f, 200f, false, Offset(cat.x - 6 * u, cat.y - 2 * u), Size(12 * u, 5 * u), style = Stroke(1.6f * u, cap = StrokeCap.Round))
    val head = Offset(cat.x + 4.5f * u, cat.y - 1.6f * u)
    drawCircle(Color(0xFFEC8A2F), 2.4f * u, head)
    for (side in intArrayOf(-1, 1)) {
        val ear = Path().apply { moveTo(head.x + side * 1.8f * u - .8f * u, head.y - 1.4f * u); lineTo(head.x + side * 1.6f * u, head.y - 3.6f * u); lineTo(head.x + side * 1.8f * u + .9f * u, head.y - 1.2f * u); close() }
        drawPath(ear, Color(0xFFEC8A2F))
    }
    drawArc(Color(0xFF5A3418), 20f, 140f, false, head + Offset(-1.6f * u, -.6f * u), Size(1.2f * u, .8f * u), style = Stroke(.4f * u))
    drawArc(Color(0xFF5A3418), 20f, 140f, false, head + Offset(.4f * u, -.6f * u), Size(1.2f * u, .8f * u), style = Stroke(.4f * u))
    repeat(3) { k ->
        val p = (time * .35f + k / 3f) % 1f
        val z = head + Offset((3 + p * 6) * u, -(3 + p * 12) * u)
        drawText(zzz, topLeft = z, alpha = (1f - p) * .9f)
    }
}

/** Mug of cocoa on a little table, with steam curling up. */
private fun DrawScope.drawCocoa(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    drawRect(Color(0xFF6B4228), Offset(base.x - 6 * u, base.y - 11 * u), Size(12 * u, 1.6f * u))
    drawRect(Color(0xFF5B3A24), Offset(base.x - .8f * u, base.y - 9.4f * u), Size(1.6f * u, 9.4f * u))
    drawRect(Color(0xFF5B3A24), Offset(base.x - 3.5f * u, base.y - 1 * u), Size(7 * u, 1 * u))
    val mug = Rect(base.x - 2.5f * u, base.y - 16 * u, base.x + 2.5f * u, base.y - 11 * u)
    drawRoundRect(Color(0xFFD42A2A), mug.topLeft, mug.size, CornerRadius(.8f * u))
    drawArc(Color(0xFFD42A2A), -90f, 180f, false, Offset(mug.right - 1.4f * u, mug.top + 1 * u), Size(3 * u, 3 * u), style = Stroke(.8f * u))
    drawRect(Color.White, Offset(mug.left, mug.top + 1.5f * u), Size(mug.width, .8f * u))
    repeat(3) { k ->
        val p = (time * .4f + k / 3f) % 1f
        val steam = Path().apply {
            val x0 = mug.center.x + (k - 1) * 1.4f * u
            moveTo(x0, mug.top - 1 * u)
            for (s in 1..4) {
                val y = mug.top - 1 * u - s * 2.2f * u
                lineTo(x0 + sin(time * 3f + s + k * 2f) * 1.2f * u, y)
            }
        }
        withTransformTranslateY(-p * 4 * u) {
            drawPath(steam, Color.White.copy(alpha = .45f * (1f - p)), style = Stroke(.7f * u, cap = StrokeCap.Round))
        }
    }
}

private inline fun DrawScope.withTransformTranslateY(dy: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(0f, dy)
    block()
    drawContext.transform.translate(0f, -dy)
}

/** Brick fireplace with a garland on the mantel, stockings and candles, and a crackling fire in the hearth. */
private fun DrawScope.drawFireplace(base: Offset, time: Float) {
    val u = 1.dp.toPx()
    val body = Rect(base.x - 24 * u, base.y - 28 * u, base.x + 24 * u, base.y)
    drawRect(Color(0xFF9C4A35), body.topLeft, body.size)
    // Brick courses with offset joints.
    var row = 0
    var y = body.top
    while (y < body.bottom) {
        drawLine(Color(0xFF6E3324), Offset(body.left, y), Offset(body.right, y), .5f * u)
        var x = body.left + if (row % 2 == 0) 0f else 3 * u
        while (x < body.right) {
            drawLine(Color(0xFF6E3324), Offset(x, y), Offset(x, minOf(y + 3 * u, body.bottom)), .5f * u)
            x += 6 * u
        }
        y += 3 * u
        row++
    }
    val opening = Path().apply {
        moveTo(base.x - 14 * u, base.y)
        lineTo(base.x - 14 * u, base.y - 12 * u)
        quadraticTo(base.x, base.y - 21 * u, base.x + 14 * u, base.y - 12 * u)
        lineTo(base.x + 14 * u, base.y)
        close()
    }
    drawPath(opening, Color(0xFF1A0E08))
    clipPath(opening) {
        drawFire(Offset(base.x, base.y - 2 * u), 24 * u, 15 * u, time)
    }
    drawPath(opening, Color(0xFF4A2418), style = Stroke(1.2f * u))
    // Mantel shelf, garland and decorations.
    drawRect(Color(0xFF5B3A24), Offset(base.x - 28 * u, body.top - 3 * u), Size(56 * u, 3 * u))
    var g = base.x - 27 * u
    var k = 0
    while (g < base.x + 27 * u) {
        drawCircle(Color(0xFF2E7D4F), 1.6f * u, Offset(g, body.top + (k % 2) * .6f * u))
        if (k % 3 == 0) drawCircle(Color(0xFFD42A2A), .6f * u, Offset(g, body.top + 1 * u))
        g += 2.4f * u
        k++
    }
    for ((i, color) in listOf(Color(0xFFD42A2A), Color(0xFF2E7D4F), Color(0xFFF0EDE6)).withIndex()) {
        val hook = Offset(base.x + (-14 + i * 14) * u, body.top + 1.5f * u)
        rotate(sin(time * 1.3f + i) * 4f, hook) { drawStocking(hook, color) }
    }
    for (side in intArrayOf(-1, 1)) {
        val candle = Offset(base.x + side * 23 * u, body.top - 3 * u)
        drawRect(Color(0xFFF3E9D2), Offset(candle.x - 1 * u, candle.y - 5 * u), Size(2 * u, 5 * u))
        val f = flicker(time, side * 3f)
        val tip = Offset(candle.x + sin(time * 8f + side) * .3f * u, candle.y - 5 * u - (2.2f + f) * u)
        drawCircle(Color(0xFFFFC266).copy(alpha = .35f), 3 * u, tip + Offset(0f, 1 * u))
        drawPath(Path().apply {
            moveTo(candle.x - .8f * u, candle.y - 5 * u)
            quadraticTo(candle.x - .9f * u, tip.y + 1 * u, tip.x, tip.y)
            quadraticTo(candle.x + .9f * u, tip.y + 1 * u, candle.x + .8f * u, candle.y - 5 * u)
            close()
        }, Color(0xFFFFB531))
    }
}

private fun DrawScope.drawStocking(hook: Offset, color: Color) {
    val u = 1.dp.toPx()
    val sock = Path().apply {
        moveTo(hook.x - 2 * u, hook.y + 2 * u)
        lineTo(hook.x + 2 * u, hook.y + 2 * u)
        lineTo(hook.x + 2 * u, hook.y + 7 * u)
        quadraticTo(hook.x + 5.5f * u, hook.y + 7.5f * u, hook.x + 5 * u, hook.y + 9.5f * u)
        lineTo(hook.x - 1 * u, hook.y + 9.5f * u)
        quadraticTo(hook.x - 2.5f * u, hook.y + 9 * u, hook.x - 2 * u, hook.y + 7 * u)
        close()
    }
    drawPath(sock, color)
    drawRoundRect(Color.White, Offset(hook.x - 2.6f * u, hook.y), Size(5.2f * u, 2.6f * u), CornerRadius(1 * u))
}

/**
 * The fire: four colour layers of flame tongues, each tongue flickering on its own rhythm, with
 * embers drifting up and an occasional crackle of sparks.
 */
private fun DrawScope.drawFire(base: Offset, width: Float, height: Float, time: Float) {
    val u = 1.dp.toPx()
    val glow = .7f + .3f * flicker(time, 2f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF8A2A).copy(alpha = .55f * glow), Color(0xFFFF8A2A).copy(alpha = 0f)), base, width), width, base)
    // Logs, with glowing ends.
    rotate(-8f, base) { drawRoundRect(Color(0xFF4A2A18), Offset(base.x - width * .45f, base.y - 2.5f * u), Size(width * .9f, 3 * u), CornerRadius(1.5f * u)) }
    rotate(10f, base) { drawRoundRect(Color(0xFF5B3420), Offset(base.x - width * .4f, base.y - 3 * u), Size(width * .8f, 3 * u), CornerRadius(1.5f * u)) }
    drawCircle(Color(0xFFFF6A1A).copy(alpha = .8f * glow), 1.2f * u, Offset(base.x - width * .42f, base.y))
    drawCircle(Color(0xFFFF6A1A).copy(alpha = .8f * glow), 1.2f * u, Offset(base.x + width * .38f, base.y - 1 * u))
    val layers = listOf(Color(0xFFD9381E) to 1f, Color(0xFFFF7A1A) to .8f, Color(0xFFFFC53D) to .58f, Color(0xFFFFF3B0) to .32f)
    for ((color, scale) in layers) {
        for (i in 0 until 5) {
            val f = flicker(time * 1.25f, i * 2.1f + scale * 7f)
            val x = base.x + (i - 2) * width / 6.5f * scale
            val h = height * scale * (.55f + .45f * f) * (1f - abs(i - 2) * .17f)
            val w = width / 3.6f * scale
            val sway = sin(time * 5.2f + i * 1.7f + scale * 3f) * w * .35f
            val tongue = Path().apply {
                moveTo(x - w / 2, base.y)
                cubicTo(x - w / 2, base.y - h * .5f, x + sway - w * .15f, base.y - h * .75f, x + sway, base.y - h)
                cubicTo(x + sway + w * .15f, base.y - h * .75f, x + w / 2, base.y - h * .5f, x + w / 2, base.y)
                close()
            }
            drawPath(tongue, color)
        }
    }
    // Embers drifting up out of the flames.
    repeat(10) { k ->
        val life = (time * .55f + hash01(k * 1.7f)) % 1f
        val p = Offset(base.x + (hash01(k * 2.3f) - .5f) * width * .7f + sin(time * 2f + k) * 2 * u, base.y - life * height * 1.4f)
        drawCircle(Color(0xFFFFB347).copy(alpha = (1f - life) * .9f), (.4f + (1f - life) * .4f) * u, p)
    }
    // Crackle: in some quarter-second slots a burst of sparks pops from a random spot in the fire.
    val slot = floor(time * 4f)
    if (hash01(slot * 1.31f) > .62f) {
        val p = time * 4f - slot
        val origin = Offset(base.x + (hash01(slot * 2.7f) - .5f) * width * .5f, base.y - height * .35f)
        repeat(4) { s ->
            val dir = -140f + s * 33f + hash01(slot + s) * 20f
            val spark = along(origin, dir, p * 7 * u)
            drawCircle(Color(0xFFFFF0A8).copy(alpha = 1f - p), .5f * u, Offset(spark.x, spark.y + p * p * 5 * u))
        }
    }
}

private fun DrawScope.drawLogStack(base: Offset) {
    val u = 1.dp.toPx()
    for ((row, count) in listOf(0 to 3, 1 to 2, 2 to 1)) {
        for (k in 0 until count) {
            val c = Offset(base.x + (k - (count - 1) / 2f) * 5.2f * u, base.y - 2.4f * u - row * 4.4f * u)
            drawCircle(Color(0xFF6B4228), 2.5f * u, c)
            drawCircle(Color(0xFFC9A26B), 1.7f * u, c)
            drawCircle(Color(0xFF8A6A40), .8f * u, c, style = Stroke(.4f * u))
        }
    }
}
