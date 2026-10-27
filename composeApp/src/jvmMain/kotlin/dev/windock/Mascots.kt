package dev.windock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import dev.windock.mvi.DockTheme
import dev.windock.mvi.Mascot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Themes whose scene is the mascot walking along the dock. */
internal fun DockTheme.hasMascot() =
    this == DockTheme.CASUAL || this == DockTheme.FLOATING || this == DockTheme.GLASS || this == DockTheme.DARK || this == DockTheme.RANDOM

/** How far below the bar window's top the floating capsule's top edge sits (see the capsule in DockBar). */
private const val CAPSULE_TOP_INSET = 8f

/**
 * The chosen animal, walking along the top edge of the bar (or of the capsule): it heads for a spot, slows
 * down, stops, now and then does its thing — a cat licks its paw, a dog pants and barks, a jaguar stretches
 * or roars, a western dragon breathes fire, an eastern dragon spirals up and dives — and sets off again.
 */
@Composable
internal fun MascotScene(mascot: Mascot, hover: DockHover, capsule: Boolean, modifier: Modifier = Modifier) {
    if (mascot == Mascot.NONE) return
    val time by rememberSceneClock()
    val walker = remember(mascot) { Walker(mascot) }
    Canvas(modifier.fillMaxSize()) {
        val u = 1.dp.toPx()
        val ground = (SceneHeadroom.value + if (capsule) CAPSULE_TOP_INSET else 0f) * u
        val span = hover.capsuleSpan
        val lo = if (capsule && span != null) span.start + 34 * u else 30 * u
        val hi = if (capsule && span != null) span.endInclusive - 34 * u else size.width - 30 * u
        if (hi <= lo) return@Canvas
        walker.step(time, lo, hi, u)
        if (mascot == Mascot.EASTERN_DRAGON) {
            drawEasternDragon(walker, ground, size.height - 8 * u, time)
        } else {
            val beast = beastOf(mascot)
            drawOval(Color.Black.copy(alpha = .13f), Offset(walker.x - beast.bodyLen * .6f * beast.k * u, ground - 1.2f * u),
                Size(beast.bodyLen * 1.2f * beast.k * u, 2.4f * u))
            drawDust(walker.x, ground, time - walker.startedAt, -walker.dir, u)
            drawDust(walker.x + walker.dir * beast.bodyLen * .3f * beast.k * u, ground, time - walker.stoppedAt, walker.dir, u)
            drawBeast(beast, Offset(walker.x, ground), walker.face, walker.pose(time, beast, u), time)
        }
    }
}

/** The mascot choice in the settings. */
@Composable
internal fun MascotPicker(selected: Mascot, onSelect: (Mascot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mascote", color = Ink, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mascot.entries.forEach { mascot ->
                val on = mascot == selected
                Text(
                    mascot.label, color = if (on) Blue else Ink, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) Color(0xFFEAF3FD) else Color.White)
                        .border(1.dp, if (on) Blue else Color(0xFFE5E7EB), RoundedCornerShape(16.dp))
                        .clickable { onSelect(mascot) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Movement

private enum class Act(val seconds: Float) {
    NONE(0f), SIT(4.5f), LICK(6.8f), PANT_BARK(7.2f), SNIFF(3f), STRETCH(3.6f), ROAR(4.6f), FIRE(5.8f), SPIRAL(5.6f), UTURN(1.1f)
}

private fun actsOf(mascot: Mascot) = when (mascot) {
    Mascot.CAT -> listOf(Act.LICK, Act.LICK, Act.SIT)
    Mascot.DOG -> listOf(Act.PANT_BARK, Act.PANT_BARK, Act.SNIFF)
    Mascot.JAGUAR -> listOf(Act.STRETCH, Act.ROAR, Act.SIT)
    Mascot.WESTERN_DRAGON -> listOf(Act.FIRE)
    Mascot.EASTERN_DRAGON -> listOf(Act.SPIRAL)
    Mascot.NONE -> emptyList()
}

private fun speedOf(mascot: Mascot) = when (mascot) {
    Mascot.CAT -> 34f
    Mascot.DOG -> 42f
    Mascot.JAGUAR -> 40f
    Mascot.WESTERN_DRAGON -> 30f
    else -> 46f
}

/**
 * Where the mascot is and what it's doing. Stepped from the draw pass with the scene clock: it accelerates,
 * cruises and brakes into each stop, turns round before walking back, and plays an act at most stops.
 */
private class Walker(val kind: Mascot) {
    private val random = java.util.Random()
    var x = Float.NaN
    var target = 0f
    /** Walking direction, and the drawn facing that eases through zero when the animal turns round. */
    var dir = 1f
    var face = 1f
    var speed = 0f
    var distance = 0f
    var act = Act.NONE
    var actTime = 0f
    /** Where an eastern dragon's flight figure starts, and which way it goes. */
    var anchor = Offset.Zero
    var actDir = 1f
    private var wait = 1f
    private var last = -1f
    /** When the animal last set off or pulled up, for the little puffs of dust its paws kick up. */
    var startedAt = -10f
    var stoppedAt = -10f
    // Eastern dragon: the head's height, and the path it has flown (newest last), which the body follows.
    var headY = Float.NaN
    val trail = ArrayList<Offset>()

    fun step(time: Float, lo: Float, hi: Float, u: Float) {
        val dt = if (last < 0f) 0f else (time - last).coerceIn(0f, .1f)
        last = time
        if (x.isNaN()) {
            stop = 1
            x = stopX(lo, hi)
            target = x
        }
        if (x < lo - u || x > hi + u) {
            // The capsule changed size: step back inside, to the nearest stop.
            stop = ((x.coerceIn(lo, hi) - lo) / (hi - lo) * STOP_SPANS).roundToInt()
            x = stopX(lo, hi)
            target = x
            act = Act.NONE
        }
        face = (face + sign(dir - face) * dt / .32f * 2f).let { if (dir > 0) min(it, 1f) else max(it, -1f) }
        if (act != Act.NONE) {
            actTime += dt
            if (actTime >= act.seconds) finishAct()
            return
        }
        if (wait > 0f) {
            wait -= dt
            if (wait <= 0f) setOff(lo, hi, u)
            return
        }
        val remaining = target - x
        if (abs(remaining) < .5f * u) {
            arrive(u, lo, hi)
            return
        }
        // Turn round before walking off the other way.
        if (kind != Mascot.EASTERN_DRAGON && face * dir < .7f) {
            speed = 0f
            return
        }
        val top = speedOf(kind) * u
        val accel = top / .45f
        if (speed == 0f) startedAt = time
        speed = min(min(speed + accel * dt, top), sqrt(2 * accel * abs(remaining)) + 4 * u)
        val moved = min(speed * dt, abs(remaining))
        x += dir * moved
        distance += moved
        if (abs(target - x) < .5f * u) {
            stoppedAt = time
            arrive(u, lo, hi)
        }
    }

    /** Which stop (0..[STOP_SPANS], every quarter of the way) the animal is at or heading for. */
    private var stop = 0
    /** Which way along the stops it's travelling; kept apart from [dir], since a dragon's figure can leave it facing back. */
    private var travel = 1f

    private fun stopX(lo: Float, hi: Float) = lo + (hi - lo) * stop / STOP_SPANS

    /** On to the next quarter mark, turning round at either end. */
    private fun setOff(lo: Float, hi: Float, u: Float) {
        if (stop + travel.toInt() !in 0..STOP_SPANS) travel = -travel
        val next = stop + travel.toInt()
        stop = next
        target = stopX(lo, hi)
        val newDir = sign(target - x).takeIf { it != 0f } ?: dir
        if (kind == Mascot.EASTERN_DRAGON && newDir != dir) {
            // A serpent can't about-face: it loops over its own back instead.
            start(Act.UTURN, Offset(x, headY), dir)
        }
        dir = newDir
        if (abs(target - x) < 2 * u) wait = .6f
    }

    private fun arrive(u: Float, lo: Float, hi: Float) {
        x = target
        speed = 0f
        // Every stop has its act.
        val acts = actsOf(kind)
        if (acts.isNotEmpty()) {
            val chosen = acts[random.nextInt(acts.size)]
            // A flight figure heads the way it was going if there's room, otherwise back over the dock.
            val toward = if (kind == Mascot.EASTERN_DRAGON) (if ((dir > 0 && hi - x >= 60 * u) || (dir < 0 && x - lo >= 60 * u)) dir else -dir) else dir
            start(chosen, Offset(x, headY), toward)
        } else {
            wait = .8f + random.nextFloat() * 1.4f
        }
    }

    private fun start(next: Act, at: Offset, toward: Float) {
        act = next
        actTime = 0f
        anchor = at
        actDir = toward
    }

    private fun finishAct() {
        if (act == Act.UTURN) {
            act = Act.NONE
            return // keep going to the spot picked before the turn
        }
        // The spiral ends coming back into place from the far side, so the dragon now faces the other way.
        if (act == Act.SPIRAL) dir = -actDir
        act = Act.NONE
        wait = .4f + random.nextFloat() * .9f
    }

    /** The body language for the current moment: gait, sitting, the act's motions. */
    fun pose(time: Float, beast: Beast, u: Float): Pose {
        val p = Pose()
        p.gait = (speed / (speedOf(kind) * .9f)).coerceIn(0f, 1f) * if (act == Act.NONE) 1f else 0f
        p.phase = distance / (beast.stride * beast.k * u) * TAU
        p.wag = sin(time * 9f) * 14f
        // Breathing while still; a little nod with each step while walking.
        p.breath = sin(time * 2.6f) * .028f * (1f - p.gait)
        p.nod = sin(p.phase * 2f + .9f) * .45f * p.gait
        // A dragon at rest half-opens its wings now and then, as if stretching them.
        if (kind == Mascot.WESTERN_DRAGON && act == Act.NONE) p.wings = sin(time * .8f).coerceAtLeast(0f).let { it * it * it * it * it * it } * .35f
        val t = actTime
        val len = act.seconds
        fun env(start: Float, length: Float, fadeIn: Float = .45f, fadeOut: Float = .45f): Float {
            val local = t - start
            return smooth(local / fadeIn) * (1f - smooth((local - (length - fadeOut)) / fadeOut))
        }
        fun pulse(at: Float, width: Float = .22f): Float {
            val local = t - at
            return if (local in 0f..width) sin(local / width * PI.toFloat()) else 0f
        }
        when (act) {
            Act.SIT -> p.sit = env(0f, len, .5f, .5f)
            // Sits, turns to face the viewer, does its thing, turns back and gets up.
            Act.LICK -> {
                p.sit = env(0f, len, .5f, .5f)
                p.front = env(.55f, len - 1.1f, .55f, .55f)
                p.lick = env(1.4f, len - 2.8f, .4f, .4f)
                val lap = sin(time * 8f)
                p.pitch = (9f + lap * 4f) * p.lick
                p.tongue = if (lap > .2f) p.lick else 0f
            }
            Act.PANT_BARK -> {
                p.sit = env(0f, len, .5f, .5f)
                p.front = env(.55f, len - 1.1f, .55f, .55f)
                val panting = env(1.1f, 3f, .3f, .3f)
                val bark = max(pulse(4.4f), max(pulse(5f), pulse(5.6f)))
                p.mouth = max((.5f + .14f * sin(time * 16f)) * panting, bark)
                p.tongue = panting * (1f - bark)
                p.breath += sin(time * 16f) * .06f * panting
                p.pitch = -12f * bark
                p.bark = bark
                p.wag = sin(time * 15f) * 26f
            }
            Act.SNIFF -> {
                val down = env(0f, len, .4f, .4f)
                p.pitch = (26f + sin(time * 22f) * 3f) * down
                p.headDrop = 3.5f * down
                p.wag = sin(time * 13f) * 22f
            }
            Act.STRETCH -> {
                p.bow = env(0f, len, .8f, .8f)
                p.mouth = env(1.1f, 1.5f, .4f, .4f)
                p.pitch = -10f * p.mouth
            }
            // Sits, turns to the viewer and roars at them, then turns back.
            Act.ROAR -> {
                p.sit = env(0f, len, .5f, .5f)
                p.front = env(.55f, len - 1.1f, .55f, .55f)
                p.mouth = env(1.3f, len - 2.6f, .25f, .5f)
                p.pitch = sin(time * 30f) * 2f * p.mouth
            }
            // Turns to the viewer, spreads its wings, rears up and breathes fire out of the screen.
            Act.FIRE -> {
                p.front = env(.2f, len - .4f, .55f, .55f)
                p.wings = env(.7f, len - 1.4f, .5f, .5f)
                val rear = env(1.1f, .8f, .3f, .3f)
                p.fire = env(1.7f, 2.6f, .25f, .5f)
                p.mouth = max(rear * .4f, p.fire)
                p.pitch = -18f * rear + 6f * p.fire
            }
            else -> Unit
        }
        return p
    }
}

/** A momentary pose; angles in degrees, the rest 0..1 unless noted. */
private class Pose {
    var gait = 0f
    var phase = 0f
    var sit = 0f
    var lick = 0f
    var mouth = 0f
    var tongue = 0f
    var pitch = 0f
    var headDrop = 0f
    var breath = 0f
    var bow = 0f
    var wag = 0f
    var bark = 0f
    var wings = 0f
    var fire = 0f
    var nod = 0f
    /** 0 = side on, 1 = facing the viewer; the side view squeezes to nothing, then the front view opens out. */
    var front = 0f
}

// ---------------------------------------------------------------- Four-legged mascots

private enum class HeadKind { CAT, DOG, JAGUAR, DRAGON }
private enum class TailKind { CURL, WAG, LONG, DRAGON }

/** Proportions and colours, in "cat units" (1 = 1dp for the cat) scaled by [k]. */
private class Beast(
    val head: HeadKind,
    val tail: TailKind,
    val fur: Color,
    val shade: Color,
    val belly: Color,
    val outline: Color,
    val k: Float,
    val bodyLen: Float,
    val bodyH: Float,
    val legLen: Float,
    val legWidth: Float,
    /** Distance covered by one full step cycle, in cat units — paws stay planted when it matches the speed. */
    val stride: Float,
)

private val beasts = mapOf(
    Mascot.CAT to Beast(HeadKind.CAT, TailKind.CURL, Color(0xFFEC8A2F), Color(0xFFB85F1A), Color(0xFFFBE3C2), Color(0xFF7A3F12),
        k = 1f, bodyLen = 18f, bodyH = 9f, legLen = 8f, legWidth = 2.2f, stride = 11f),
    Mascot.DOG to Beast(HeadKind.DOG, TailKind.WAG, Color(0xFFCB8C4E), Color(0xFF8E5B2C), Color(0xFFF4E0C0), Color(0xFF5E3A1C),
        k = 1.08f, bodyLen = 19f, bodyH = 9.5f, legLen = 9f, legWidth = 2.6f, stride = 12f),
    Mascot.JAGUAR to Beast(HeadKind.JAGUAR, TailKind.LONG, Color(0xFFE5AA3C), Color(0xFF3B2A14), Color(0xFFF8EAC8), Color(0xFF6E4612),
        k = 1.2f, bodyLen = 21f, bodyH = 8.5f, legLen = 8f, legWidth = 2.6f, stride = 14f),
    Mascot.WESTERN_DRAGON to Beast(HeadKind.DRAGON, TailKind.DRAGON, Color(0xFF3F9150), Color(0xFF2A6638), Color(0xFFE9D27E), Color(0xFF173F22),
        k = 1.15f, bodyLen = 20f, bodyH = 10f, legLen = 7f, legWidth = 3f, stride = 13f),
)

private fun beastOf(mascot: Mascot) = beasts.getValue(mascot)

/**
 * The animal in its current pose. Turning to face the viewer isn't a card flip: the profile only narrows to
 * about 60% while the front view, already that wide, fades in over it — the eye reads a three-quarter view —
 * and the animal settles with a small hop as it shifts its weight.
 */
private fun DrawScope.drawBeast(b: Beast, feet: Offset, face: Float, p: Pose, time: Float) {
    val turn = p.front
    when {
        turn <= .001f -> drawBeastSide(b, feet, face, 1f, p, time)
        turn >= .999f -> drawBeastFront(b, feet, p, time)
        else -> {
            val c = 1.dp.toPx() * b.k
            val eased = smooth(turn)
            val hop = sin(turn * PI.toFloat()) * 1.4f * c
            val blend = smooth((turn - .4f) / .22f)
            withTransform({
                translate(0f, -hop)
                scale(1f, 1f + .05f * sin(turn * TAU), feet)
            }) {
                withLayerAlpha(1f - blend) { drawBeastSide(b, feet, face, mix(1f, .6f, eased), p, time) }
                withLayerAlpha(blend) {
                    withTransform({ scale(mix(.6f, 1f, eased), 1f, feet) }) { drawBeastFront(b, feet, p, time) }
                }
            }
        }
    }
}

/** Draws [block] into a layer composited at [alpha]; skipped when invisible, direct when opaque. */
private inline fun DrawScope.withLayerAlpha(alpha: Float, block: DrawScope.() -> Unit) {
    when {
        alpha <= .01f -> Unit
        alpha >= .99f -> block()
        else -> {
            val canvas = drawContext.canvas
            canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
            block()
            canvas.restore()
        }
    }
}

private fun DrawScope.drawBeastFront(b: Beast, feet: Offset, p: Pose, time: Float) {
    val c = 1.dp.toPx() * b.k
    if (b.head == HeadKind.DRAGON) {
        drawDragonFront(b.fur, b.shade, b.belly, b.outline, feet, c, time, p.mouth, p.wings, p.fire)
        return
    }
    val kind = when (b.head) {
        HeadKind.DOG -> FrontKind.DOG
        HeadKind.JAGUAR -> FrontKind.JAGUAR
        else -> FrontKind.CAT
    }
    val tilt = when (kind) {
        FrontKind.CAT -> -p.pitch
        FrontKind.DOG -> p.bark * 4f
        FrontKind.JAGUAR -> p.pitch
    }
    drawSittingFront(kind, b.fur, b.shade, b.belly, b.outline, feet, c, time, p.mouth, p.tongue, p.bark, p.breath, p.lick, tilt, p.wag)
}

/** The side view; [squeeze] narrows it while turning toward the viewer. */
private fun DrawScope.drawBeastSide(b: Beast, feet: Offset, face: Float, squeeze: Float, p: Pose, time: Float) {
    val c = 1.dp.toPx() * b.k
    val g = feet.y
    val x = feet.x
    val s = p.sit
    val gait = p.gait * (1f - s)
    val phase = p.phase
    val bob = sin(phase * 2f) * .5f * c * gait
    val half = b.bodyLen / 2
    val stance = b.bodyLen / 3

    withTransform({ scale(face * squeeze, 1f, feet) }) {
        val bodyCenter = Offset(x + mix(0f, -2f, s) * c, g - mix(b.legLen + 3.4f, b.legLen + 3.5f, s) * c + bob + p.bow * 1.2f * c)
        val bodyAngle = mix(0f, -58f, s) + p.bow * 13f
        val neck = if (b.head == HeadKind.DRAGON) 1f else 0f
        val head = Offset(
            x + (mix(half + 1f, 3.5f, s) + neck * mix(3.5f, 2f, s) + p.bow * 1f) * c,
            g - (mix(b.legLen + 7.6f, b.legLen + 14.5f, s) + neck * 5f - p.headDrop - p.bow * 6f) * c + bob * .25f + p.nod * c,
        )
        val frontHip = Offset(x + mix(stance, 2.8f, s) * c, g - mix(b.legLen, b.legLen + 4f, s) * c + bob + p.bow * 3f * c)
        val backHip = Offset(x + mix(-stance, -3f, s) * c, g - mix(b.legLen, b.legLen - 2f, s) * c + bob)

        drawTail(b, along(bodyCenter, bodyAngle + 180f, mix(half - 1f, 5.5f, s) * c), g, x, s, p, time, c)
        if (b.head == HeadKind.DRAGON) drawWing(b, bodyCenter, bodyAngle, p, time, c, far = true)

        // Two-bone legs: the knee is solved from hip and paw, front wrists flexing forward and hind hocks back,
        // so lifted paws fold the way an animal's do.
        fun leg(hip: Offset, footX: Float, offset: Float, far: Boolean, front: Boolean, raise: Float = 0f) {
            val swing = sin(phase + offset) * 3.2f * c * gait
            val lift = max(0f, cos(phase + offset)) * 2.2f * c * gait
            var paw = Offset(footX + swing, g - lift)
            // Licking: the near front paw comes up to the mouth.
            if (raise > 0f) paw = Offset(mix(paw.x, head.x + 1.5f * c, raise), mix(paw.y, head.y + 4f * c, raise))
            val color = if (far) lerp(b.fur, b.outline, .35f) else b.fur
            val bone = b.legLen * (if (front) .5f else .54f) * c
            val knee = joint(hip, paw, bone, bone, forward = front)
            val width = b.legWidth * c
            drawLine(b.outline, hip, knee, width * 1.2f + c, StrokeCap.Round)
            drawLine(b.outline, knee, paw, width + c, StrokeCap.Round)
            drawLine(color, hip, knee, width * 1.2f, StrokeCap.Round)
            drawLine(color, knee, paw, width, StrokeCap.Round)
            val pawColor = when {
                b.head == HeadKind.DRAGON -> Color(0xFFF2EAD3)
                far -> color
                else -> b.belly
            }
            val pawSize = Size(width * 1.3f, width * .8f)
            val pawAt = paw - Offset(width * .45f, pawSize.height * .75f)
            drawOval(b.outline, pawAt - Offset(.45f * c, .45f * c), Size(pawSize.width + .9f * c, pawSize.height + .9f * c))
            drawOval(pawColor, pawAt, pawSize)
            if (b.head == HeadKind.DRAGON) for (k in 0..2) {
                // Claws.
                val tip = pawAt + Offset(pawSize.width * (.55f + k * .2f), pawSize.height)
                drawLine(Color(0xFF2B2B2B), tip, tip + Offset(.6f * c, .3f * c), .5f * c, StrokeCap.Round)
            }
        }
        leg(frontHip + Offset(-c, 0f), x + mix(stance, 2f, s) * c + p.bow * 3f * c, PI.toFloat(), far = true, front = true)
        leg(backHip + Offset(-c, 0f), x + mix(-stance, 0f, s) * c, 0f, far = true, front = false)

        rotate(bodyAngle, bodyCenter) {
            val bodySize = Size(mix(b.bodyLen, b.bodyLen * .83f, s) * c, mix(b.bodyH, b.bodyH * 1.1f, s) * c * (1f + p.breath))
            val topLeft = bodyCenter - Offset(bodySize.width / 2, bodySize.height / 2)
            drawOval(b.outline, topLeft - Offset(.6f * c, .6f * c), Size(bodySize.width + 1.2f * c, bodySize.height + 1.2f * c))
            // Lit from above: lighter along the back, a touch darker underneath.
            drawOval(
                Brush.verticalGradient(listOf(lerp(b.fur, Color.White, .16f), b.fur, lerp(b.fur, b.outline, .2f)), topLeft.y, topLeft.y + bodySize.height),
                topLeft, bodySize,
            )
            drawOval(Color.White.copy(alpha = .16f), topLeft + Offset(bodySize.width * .22f, bodySize.height * .1f), Size(bodySize.width * .42f, bodySize.height * .2f))
            drawOval(b.belly, topLeft + Offset(bodySize.width * .3f, bodySize.height * .55f), Size(bodySize.width * .5f, bodySize.height * .4f))
            drawBodyPattern(b, bodyCenter, topLeft, bodySize, c)
        }
        if (s > 0f) {
            drawCircle(b.outline, (4.6f * s + .6f) * c, Offset(x - 3.5f * c, g - 4.4f * c))
            drawCircle(b.fur, 4.6f * s * c, Offset(x - 3.5f * c, g - 4.4f * c))
        }
        leg(frontHip, x + mix(stance + 1.5f, 4f, s) * c + p.bow * 4f * c, 0f, far = false, front = true, raise = p.lick)
        leg(backHip, x + mix(-stance + 1f, 1f, s) * c, PI.toFloat(), far = false, front = false)

        if (neck > 0f) {
            val shoulder = frontHip + Offset(-1f * c, -3f * c)
            drawLine(b.outline, shoulder, head + Offset(-2f * c, 1f * c), 6.2f * c, StrokeCap.Round)
            drawLine(b.fur, shoulder, head + Offset(-2f * c, 1f * c), 5f * c, StrokeCap.Round)
            drawLine(b.belly, shoulder + Offset(1.4f * c, 1.6f * c), head + Offset(-.6f * c, 2.6f * c), 1.6f * c, StrokeCap.Round)
            drawWing(b, bodyCenter, bodyAngle, p, time, c, far = false)
        }
        if (b.head == HeadKind.DOG) {
            // A red collar with a little tag.
            val collar = Offset(head.x - 3.2f * c, head.y + 3.2f * c)
            drawLine(Color(0xFFD23A3A), collar + Offset(-1f * c, -1.6f * c), collar + Offset(1f * c, 1.6f * c), 1.6f * c, StrokeCap.Round)
            drawCircle(Color(0xFFF6C744), .9f * c, collar + Offset(1.2f * c, 2.4f * c))
        }
        if (p.fire > .01f) {
            drawOval(Color(0xFFFF8A2A).copy(alpha = .22f * p.fire), Offset(head.x + 6f * c, g - 2.2f * c), Size(38f * c * p.fire, 4.4f * c))
        }
        rotate(p.pitch, head + Offset(-2f * c, 2f * c)) {
            when (b.head) {
                HeadKind.CAT, HeadKind.JAGUAR -> drawFelineHead(b, head, p, s, time, c)
                HeadKind.DOG -> drawDogHead(b, head, p, time, c)
                HeadKind.DRAGON -> drawDragonHead(b, head, p, time, c)
            }
        }
    }
}

private fun DrawScope.drawBodyPattern(b: Beast, center: Offset, topLeft: Offset, size: Size, c: Float) {
    when (b.head) {
        HeadKind.CAT -> for (k in 0..2) {
            val sx = center.x + (k - 1) * 4 * c
            drawLine(b.shade, Offset(sx - .8f * c, topLeft.y + .6f * c), Offset(sx + .6f * c, topLeft.y + 3.4f * c), 1.3f * c, StrokeCap.Round)
        }
        HeadKind.JAGUAR -> {
            // Rosettes: dark broken rings with a tawny centre.
            for (k in 0 until 7) {
                val fx = .15f + (k % 4) * .22f + (k / 4) * .1f
                val fy = if (k < 4) .3f else .58f
                val spot = Offset(topLeft.x + size.width * fx, topLeft.y + size.height * fy)
                drawCircle(b.shade, 1.25f * c, spot, style = Stroke(.7f * c))
                drawCircle(lerp(b.fur, b.shade, .25f), .45f * c, spot)
            }
        }
        HeadKind.DOG -> drawOval(b.shade.copy(alpha = .55f), topLeft + Offset(size.width * .18f, size.height * .08f), Size(size.width * .32f, size.height * .42f))
        HeadKind.DRAGON -> {
            // Spines along the back and plated scales.
            for (k in 0..4) {
                val sx = topLeft.x + size.width * (.18f + k * .16f)
                val spine = Path().apply {
                    moveTo(sx - 1.3f * c, topLeft.y + .8f * c); lineTo(sx, topLeft.y - 2.4f * c); lineTo(sx + 1.3f * c, topLeft.y + .8f * c); close()
                }
                drawPath(spine, Color(0xFFE9D27E))
                drawPath(spine, b.outline, style = Stroke(.5f * c))
            }
            for (k in 0..5) drawCircle(b.shade.copy(alpha = .6f), .7f * c, Offset(topLeft.x + size.width * (.2f + k * .12f), topLeft.y + size.height * .35f))
            for (k in 0..3) {
                val px = topLeft.x + size.width * (.36f + k * .1f)
                drawLine(lerp(b.belly, b.outline, .35f), Offset(px, topLeft.y + size.height * .6f), Offset(px - .3f * c, topLeft.y + size.height * .9f), .45f * c)
            }
        }
    }
}

private fun DrawScope.drawTail(b: Beast, base: Offset, g: Float, x: Float, s: Float, p: Pose, time: Float, c: Float) {
    val tail = Path()
    tail.moveTo(base.x, base.y)
    var width = 2.3f * c
    var tip: Offset? = null
    when (b.tail) {
        TailKind.CURL -> {
            val flick = sin(time * mix(2.2f, 3.8f, s)) * c + sin(p.phase) * 1.4f * c * p.gait
            val c1 = Offset(mix(base.x - 6 * c, base.x - 4 * c, s), mix(base.y - 2 * c, g - 1 * c, s))
            val c2 = Offset(mix(base.x - 6 * c + flick, x - 2 * c, s), mix(base.y - 10 * c, g + .5f * c, s))
            val end = Offset(mix(base.x - 2 * c + flick * 1.5f, x + 6 * c + flick, s), mix(base.y - 12 * c, g - 1 * c, s))
            tail.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }
        TailKind.WAG -> {
            val end = along(base, -135f + p.wag + s * 40f, 8f * c)
            val mid = along(base, -150f + p.wag * .5f + s * 30f, 4.5f * c)
            tail.quadraticTo(mid.x, mid.y, end.x, end.y)
            width = 2.6f * c
        }
        TailKind.LONG -> {
            val sway = sin(time * 1.7f) * 2f * c + sin(p.phase) * 1.6f * c * p.gait
            val c1 = Offset(base.x - 7 * c, base.y + mix(3f, 2f, s) * c)
            val c2 = Offset(base.x - 13 * c, mix(base.y + 5 * c, g - 1 * c, s) + sway)
            val end = Offset(mix(base.x - 17 * c, x + 7 * c, s), mix(base.y - 2 * c + sway, g - 1.4f * c, s))
            tail.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
            tip = end
            width = 2.4f * c
        }
        TailKind.DRAGON -> {
            val sway = sin(time * 1.4f) * 2.5f * c + sin(p.phase) * 2f * c * p.gait
            val c1 = Offset(base.x - 8 * c, base.y + 4 * c)
            val c2 = Offset(base.x - 15 * c, g - 2 * c + sway * .3f)
            val end = Offset(base.x - 21 * c, g - 6 * c + sway)
            tail.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
            tip = end
            width = 3.6f * c
        }
    }
    drawPath(tail, b.outline, style = Stroke(width + 1f * c, cap = StrokeCap.Round))
    drawPath(tail, b.fur, style = Stroke(width, cap = StrokeCap.Round))
    when {
        b.tail == TailKind.LONG && tip != null -> drawCircle(b.shade, 1.5f * c, tip)
        b.tail == TailKind.DRAGON && tip != null -> {
            // The spade at the tip.
            val spade = Path().apply {
                moveTo(tip.x + 1 * c, tip.y); lineTo(tip.x - 2.5f * c, tip.y - 3.2f * c); lineTo(tip.x - 4.5f * c, tip.y + .5f * c)
                lineTo(tip.x - 2f * c, tip.y + 2.6f * c); close()
            }
            drawPath(spade, b.shade)
            drawPath(spade, b.outline, style = Stroke(.6f * c))
        }
    }
}

/** A bat-like wing: folded along the back while walking, raised wide while breathing fire. */
private fun DrawScope.drawWing(b: Beast, body: Offset, bodyAngle: Float, p: Pose, time: Float, c: Float, far: Boolean) {
    val w = p.wings
    val flap = sin(time * 9f) * 1.6f * w
    val root = body + Offset((if (far) 1.5f else 0f) * c, -3.5f * c)
    // Wrist, three finger tips and where the membrane meets the flank; folded and spread positions.
    fun point(fx: Float, fy: Float, sx: Float, sy: Float) = root + Offset(mix(fx, sx, w) * c, (mix(fy, sy, w) - flap * (-sy / 20f)) * c)
    val wrist = point(-1f, -4f, 3f, -13f)
    val fingers = listOf(point(-8f, -5f, -4f, -22f), point(-9f, -3.5f, -11f, -16f), point(-9.5f, -2f, -14f, -7f))
    val flank = point(-8f, .5f, -9f, 1f)
    val membrane = Path().apply {
        moveTo(root.x, root.y)
        lineTo(wrist.x, wrist.y)
        lineTo(fingers[0].x, fingers[0].y)
        // Scalloped trailing edge: each span sags in toward the wrist.
        val edge = fingers + flank
        for (k in 1 until edge.size) {
            val a = edge[k - 1]
            val e = edge[k]
            val mid = Offset((a.x + e.x) / 2, (a.y + e.y) / 2)
            val sag = mid + (wrist - mid) * .22f
            quadraticTo(sag.x, sag.y, e.x, e.y)
        }
        close()
    }
    val color = if (far) lerp(b.shade, b.outline, .45f) else lerp(b.shade, b.fur, .25f)
    drawPath(membrane, color)
    drawPath(membrane, b.outline, style = Stroke(.6f * c))
    for (tip in fingers) drawLine(b.outline, wrist, tip, .7f * c, StrokeCap.Round)
    drawLine(b.outline, root, wrist, 1.5f * c, StrokeCap.Round)
    drawCircle(Color(0xFFF2EAD3), .6f * c, wrist)
}

private fun DrawScope.drawFelineHead(b: Beast, head: Offset, p: Pose, sitting: Float, time: Float, c: Float) {
    val jaguar = b.head == HeadKind.JAGUAR
    val r = 5 * c
    val twitch = if ((time % 3.3f) < .18f) -10f else 0f
    fun ear(base: Offset, tilt: Float, inner: Boolean) {
        rotate(tilt, base) {
            if (jaguar) {
                drawCircle(b.outline, 1.9f * c, base + Offset(0f, -1.2f * c))
                drawCircle(b.fur, 1.5f * c, base + Offset(0f, -1.2f * c))
                if (inner) drawCircle(b.shade, .7f * c, base + Offset(0f, -1.1f * c))
            } else {
                val ear = Path().apply {
                    moveTo(base.x - 2.2f * c, base.y); lineTo(base.x, base.y - 4.2f * c); lineTo(base.x + 2.2f * c, base.y); close()
                }
                drawPath(ear, b.fur)
                drawPath(ear, b.outline, style = Stroke(.7f * c))
                if (inner) drawLine(Color(0xFFF2A7A0), Offset(base.x, base.y - .6f * c), Offset(base.x, base.y - 2.8f * c), 1.1f * c, StrokeCap.Round)
            }
        }
    }
    ear(head + Offset(-2.4f * c, -3.4f * c), -18f + twitch, false)
    drawCircle(b.outline, r + .6f * c, head)
    drawCircle(b.fur, r, head)
    ear(head + Offset(2f * c, -3.8f * c), 14f, true)
    drawCircle(Color.White.copy(alpha = .16f), r * .45f, head + Offset(-r * .3f, -r * .35f))
    drawCircle(Color(0xFFFF8A8A).copy(alpha = .28f), r * .26f, head + Offset(r * .2f, r * .45f))
    if (jaguar) {
        for ((dx, dy) in listOf(-1.8f to -2.6f, .2f to -3.6f, -3f to -.4f, -2.2f to 1.6f)) drawCircle(b.shade, .55f * c, head + Offset(dx * c, dy * c))
    } else {
        drawLine(b.shade, head + Offset(-.6f * c, -4.6f * c), head + Offset(-.2f * c, -2.6f * c), .9f * c, StrokeCap.Round)
        drawLine(b.shade, head + Offset(1.2f * c, -4.4f * c), head + Offset(1.2f * c, -2.8f * c), .9f * c, StrokeCap.Round)
    }
    drawOval(b.belly, head + Offset(1.2f * c, .4f * c), Size(4.6f * c, 3.4f * c))
    drawCircle(if (jaguar) Color(0xFF3B2A14) else Color(0xFFE46F7A), .75f * c, head + Offset(4.6f * c, .9f * c))
    // Open mouth (a roar or a yawn): the jaw drops below the muzzle, fangs showing.
    if (p.mouth > .02f) {
        val open = p.mouth * 4.4f * c
        val lip = head.y + 2.6f * c
        drawOval(b.outline, Offset(head.x + 1.2f * c, lip - .6f * c), Size(5.4f * c, open + 1.2f * c))
        drawOval(Color(0xFF5A1414), Offset(head.x + 1.6f * c, lip - .2f * c), Size(4.6f * c, open))
        drawOval(Color(0xFFE07A86), Offset(head.x + 2.2f * c, lip + open * .45f), Size(3.2f * c, open * .45f))
        for (fx in floatArrayOf(2.2f, 4.6f)) {
            val tooth = Path().apply {
                moveTo(head.x + fx * c, lip); lineTo(head.x + (fx + .5f) * c, lip + 1.5f * c * p.mouth); lineTo(head.x + (fx + 1f) * c, lip); close()
            }
            drawPath(tooth, Color.White)
        }
    }

    if (p.tongue > .05f) {
        drawOval(Color(0xFFE46F7A), head + Offset(4.2f * c, 2.6f * c), Size(1.6f * c, 1.6f * c * p.tongue))
    }
    for (k in -1..1) {
        drawLine(b.outline.copy(alpha = .6f), head + Offset(3.6f * c, 2f * c), head + Offset(8.6f * c, (1.6f + k * 1.1f) * c), .35f * c)
    }
    val period = mix(3.2f, 2.4f, sitting)
    val blinkPhase = time % period
    val closing = mix(.14f, .5f, sitting)
    val blink = (if (blinkPhase < closing) 1f - sin(blinkPhase / closing * PI.toFloat()) * .9f else 1f) * (1f - p.lick * .6f)
    val iris = if (jaguar) Color(0xFFC98A1C) else Color(0xFF3E6B2C)
    for (eyeX in floatArrayOf(.4f, 3f)) {
        val eye = head + Offset(eyeX * c, -1.2f * c)
        drawOval(iris, eye - Offset(.9f * c, 1.1f * c * blink), Size(1.8f * c, 2.2f * c * blink))
        if (blink > .5f) {
            drawOval(Color(0xFF1B1B1B), eye - Offset(.35f * c, .9f * c * blink), Size(.7f * c, 1.8f * c * blink))
            drawCircle(Color.White, .3f * c, eye + Offset(.3f * c, -.5f * c))
        }
    }
}

private fun DrawScope.drawDogHead(b: Beast, head: Offset, p: Pose, time: Float, c: Float) {
    val r = 4.8f * c
    // Far ear, then the skull, snout and jaw, then the near ear flopping over.
    drawCircle(b.outline, r + .6f * c, head)
    drawCircle(b.fur, r, head)
    drawCircle(Color.White.copy(alpha = .16f), r * .45f, head + Offset(-r * .3f, -r * .35f))
    val jawPivot = head + Offset(3f * c, 2.2f * c)
    rotate(p.mouth * 30f, jawPivot) {
        drawRoundRectSafe(b.outline, head + Offset(2f * c, 1.6f * c), Size(6.4f * c, 2.6f * c), 1.3f * c)
        drawRoundRectSafe(b.belly, head + Offset(2.3f * c, 1.8f * c), Size(5.8f * c, 2.1f * c), 1.1f * c)
        if (p.tongue > .05f) {
            drawOval(Color(0xFFE5707E), head + Offset(4.2f * c, 2.4f * c), Size(2.4f * c, (2f + 2.6f * p.tongue) * c))
            drawLine(Color(0xFFC24E5C), head + Offset(5.4f * c, 2.8f * c), head + Offset(5.4f * c, (3.4f + 2f * p.tongue) * c), .3f * c)
        }
    }
    if (p.mouth > .05f) drawOval(Color(0xFF5A1414), head + Offset(3f * c, 1.2f * c), Size(4.6f * c, 1.6f * c * p.mouth))
    drawRoundRectSafe(b.outline, head + Offset(1.6f * c, -1.4f * c), Size(7f * c, 3.6f * c), 1.8f * c)
    drawRoundRectSafe(b.belly, head + Offset(1.9f * c, -1.1f * c), Size(6.4f * c, 3f * c), 1.5f * c)
    drawOval(Color(0xFF241812), head + Offset(7.2f * c, -1.4f * c), Size(2f * c, 1.6f * c))
    drawCircle(Color.White.copy(alpha = .6f), .3f * c, head + Offset(7.7f * c, -1f * c))
    // Eye with a brow that lifts when barking.
    val blink = if ((time % 3.7f) < .14f) .2f else 1f
    drawOval(Color(0xFF2A1B10), head + Offset(.8f * c, -2.2f * c * blink), Size(1.7f * c, 2f * c * blink))
    if (blink > .5f) drawCircle(Color.White, .35f * c, head + Offset(1.9f * c, -1.6f * c))
    drawLine(b.outline, head + Offset(.4f * c, -3.4f * c - p.bark * c), head + Offset(2.6f * c, -3.6f * c - p.bark * c), .6f * c, StrokeCap.Round)
    val flop = sin(p.phase * 2f) * 6f * p.gait + p.bark * -20f
    rotate(18f + flop, head + Offset(-1.6f * c, -3.6f * c)) {
        drawOval(b.outline, head + Offset(-3.1f * c, -4.2f * c), Size(3.4f * c, 6.6f * c))
        drawOval(b.shade, head + Offset(-2.8f * c, -3.9f * c), Size(2.8f * c, 6f * c))
    }
    // "Woof": sound waves in front of the mouth.
    if (p.bark > .05f) for (k in 0..2) {
        val radius = (3f + k * 2.2f) * c
        drawArc(
            b.outline.copy(alpha = p.bark * (1f - k * .25f)), -35f, 70f, false,
            topLeft = head + Offset(9f * c - radius, 1f * c - radius), size = Size(radius * 2, radius * 2), style = Stroke(.8f * c, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawRoundRectSafe(color: Color, topLeft: Offset, size: Size, radius: Float) =
    drawRoundRect(color, topLeft, size, androidx.compose.ui.geometry.CornerRadius(radius, radius))

private fun DrawScope.drawDragonHead(b: Beast, head: Offset, p: Pose, time: Float, c: Float) {
    // Horns sweeping back.
    for ((dy, len) in listOf(-2.6f to 6f, -1.4f to 4.6f)) {
        val base = head + Offset(-1f * c, dy * c)
        val horn = Path().apply {
            moveTo(base.x, base.y)
            quadraticTo(base.x - len * .6f * c, base.y - 2.8f * c, base.x - len * c, base.y - 1f * c)
        }
        drawPath(horn, b.outline, style = Stroke(1.9f * c, cap = StrokeCap.Round))
        drawPath(horn, Color(0xFFF2EAD3), style = Stroke(1.2f * c, cap = StrokeCap.Round))
    }
    val jawPivot = head + Offset(1f * c, 1.6f * c)
    rotate(p.mouth * 24f, jawPivot) {
        drawRoundRectSafe(b.outline, head + Offset(.4f * c, 1f * c), Size(8f * c, 2.6f * c), 1.2f * c)
        drawRoundRectSafe(b.belly, head + Offset(.7f * c, 1.2f * c), Size(7.4f * c, 2f * c), 1f * c)
    }
    if (p.mouth > .05f) {
        drawOval(Color(0xFF6B1A0E), head + Offset(1.6f * c, .6f * c), Size(6.2f * c, 1.8f * c * p.mouth))
        for (k in 0..2) {
            val tx = head.x + (3f + k * 1.6f) * c
            val tooth = Path().apply { moveTo(tx, head.y + .7f * c); lineTo(tx + .45f * c, head.y + (.7f + 1f * p.mouth) * c); lineTo(tx + .9f * c, head.y + .7f * c); close() }
            drawPath(tooth, Color.White)
        }
    }
    drawOval(b.outline, head + Offset(-4f * c, -3.6f * c), Size(8.6f * c, 6.6f * c))
    drawOval(b.fur, head + Offset(-3.6f * c, -3.2f * c), Size(7.8f * c, 5.8f * c))
    drawRoundRectSafe(b.outline, head + Offset(1.4f * c, -1.8f * c), Size(8.4f * c, 3.4f * c), 1.6f * c)
    drawRoundRectSafe(b.fur, head + Offset(1.7f * c, -1.5f * c), Size(7.8f * c, 2.8f * c), 1.4f * c)
    drawCircle(b.outline, .45f * c, head + Offset(8.6f * c, -1f * c))
    // Brow ridge and a golden slit eye.
    drawLine(b.outline, head + Offset(-1.4f * c, -2.8f * c), head + Offset(2.2f * c, -2.2f * c), 1f * c, StrokeCap.Round)
    drawOval(Color(0xFFF7D046), head + Offset(-.6f * c, -2.2f * c), Size(2.2f * c, 1.6f * c))
    drawLine(Color(0xFF1B1B1B), head + Offset(.5f * c, -2.1f * c), head + Offset(.5f * c, -.7f * c), .5f * c)
    // Wisps of smoke from the nostril between fires.
    if (p.fire < .1f) {
        val puff = (time * .7f) % 1f
        drawCircle(Color(0xFF9AA0A8).copy(alpha = (1f - puff) * .35f), (.6f + puff * 1.8f) * c, head + Offset((9f + puff * 3f) * c, (-1.6f - puff * 5f) * c))
    }
    if (p.fire > .01f) drawFire(head + Offset(9.4f * c, 1.6f * c), p.fire, time, c)
}

/** A cone of flame particles streaming forward from [mouth]: yellow at the core, red and fading at the end. */
private fun DrawScope.drawFire(mouth: Offset, strength: Float, time: Float, c: Float) {
    val reach = 34f * c * strength
    drawOval(Color(0xFFFFB13B).copy(alpha = .18f * strength), Offset(mouth.x, mouth.y - 5f * c), Size(reach, 10f * c))
    for (i in 0 until 26) {
        val life = (time * 1.9f + i / 26f) % 1f
        val dist = life * reach
        val spread = life * 6f * c
        val y = mouth.y + sin(i * 12.9f + time * 11f) * spread * .7f - life * 2f * c
        val radius = mix(1.2f, 4.6f, life) * c
        val color = when {
            life < .35f -> lerp(Color(0xFFFFF6B0), Color(0xFFFFC233), life / .35f)
            else -> lerp(Color(0xFFFF8A1F), Color(0xFFD9301C), (life - .35f) / .65f)
        }
        drawCircle(color.copy(alpha = (1f - life) * .9f * strength), radius, Offset(mouth.x + dist, y))
    }
    drawLine(Color(0xFFFFF6B0).copy(alpha = .9f * strength), mouth, mouth + Offset(reach * .45f, 0f), 1.6f * c, StrokeCap.Round)
    for (i in 0 until 8) {
        val life = (time * 1.1f + i / 8f) % 1f
        val ember = Offset(mouth.x + (8f + i * 3.4f) * c * strength + life * 4f * c, mouth.y + life * life * 12f * c)
        drawCircle(Color(0xFFFFA43A).copy(alpha = (1f - life) * strength), .55f * c, ember)
    }
}

// ---------------------------------------------------------------- Eastern dragon

private val LungRed = Color(0xFFD7322A)
private val LungDark = Color(0xFF6E1410)
private val LungGold = Color(0xFFF2C14E)
private val LungMane = Color(0xFFF6B935)
private const val LUNG_SEGMENTS = 26
private const val LUNG_SPACING = 2.3f

/**
 * A long serpentine dragon that swims through the air above the dock. Its body follows the path its head has
 * flown, so turns become loops and its act — a spiral up, a dive to the bottom of the bar and back — is traced
 * by the whole body.
 */
private fun DrawScope.drawEasternDragon(w: Walker, ground: Float, bottom: Float, time: Float) {
    val top = ground - SceneHeadroom.toPx() + 7.dp.toPx()
    val u = 1.dp.toPx()
    val cruise = ground - 11f * u
    if (w.headY.isNaN()) w.headY = cruise
    val headPos = when (w.act) {
        Act.SPIRAL -> spiralPath(w.anchor, w.actDir, w.actTime / Act.SPIRAL.seconds, top = top, bottom = bottom, u = u)
        Act.UTURN -> {
            val t = smooth(w.actTime / Act.UTURN.seconds)
            val radius = 7f * u
            val center = w.anchor - Offset(0f, radius)
            Offset(center.x + w.actDir * radius * sin(PI.toFloat() * t), center.y + radius * cos(PI.toFloat() * t))
        }
        else -> {
            // Glide back to cruising height, swimming in gentle waves.
            val wave = sin(w.distance / (34f * u) * TAU) * 3f * u + sin(time * 1.8f) * 1.2f * u
            w.headY += (cruise + wave - w.headY) * .08f
            Offset(w.x, w.headY)
        }
    }
    // After a figure the head carries on from where it actually is.
    if (w.act == Act.SPIRAL || w.act == Act.UTURN) w.headY = headPos.y

    val trail = w.trail
    if (trail.isEmpty()) for (k in 60 downTo 0) trail += Offset(headPos.x - w.dir * k * u, headPos.y)
    if (hypot(headPos.x - trail.last().x, headPos.y - trail.last().y) >= .8f * u) trail += headPos else trail[trail.size - 1] = headPos
    while (trail.size > 140) trail.removeAt(0)

    // Sample the body at even distances back along the trail.
    val points = ArrayList<Offset>(LUNG_SEGMENTS)
    points += headPos
    var need = LUNG_SPACING * u
    var i = trail.size - 1
    var cursor = headPos
    while (points.size < LUNG_SEGMENTS && i > 0) {
        val prev = trail[i - 1]
        val seg = hypot(prev.x - cursor.x, prev.y - cursor.y)
        if (seg >= need) {
            val t = need / seg
            cursor = Offset(mix(cursor.x, prev.x, t), mix(cursor.y, prev.y, t))
            points += cursor
            need = LUNG_SPACING * u
        } else {
            need -= seg
            cursor = prev
            i--
        }
    }
    while (points.size < LUNG_SEGMENTS) points += points.last() - Offset(w.dir * LUNG_SPACING * u, 0f)

    // Tapering to the tail, with a slow swell travelling down the body as it swims.
    fun radius(k: Int) = mix(3.6f, .9f, k / (LUNG_SEGMENTS - 1f)) * u * (if (k < 3) mix(.85f, 1f, k / 3f) else 1f) *
        (1f + .06f * sin(time * 6f - k * .45f))
    // Tail tuft, then outline, body, belly, mane — back to front so the head sits on top.
    val tail = points.last()
    val tailDir = (points[points.size - 1] - points[points.size - 3]).let { it / max(1f, it.getDistance()) }
    for (k in 0..2) {
        val wave = sin(time * 6f + k) * 1.5f * u
        drawLine(LungMane, tail, tail + tailDir * (5f + k) * u + Offset(-tailDir.y, tailDir.x) * ((k - 1) * 2.4f * u + wave), 1.4f * u, StrokeCap.Round)
    }
    for (k in points.indices.reversed()) drawCircle(LungDark, radius(k) + .7f * u, points[k])
    for (k in points.indices.reversed()) {
        val r = radius(k)
        drawCircle(LungRed, r, points[k])
        // A scale arc on every segment and a sheen along the top.
        drawArc(
            LungDark.copy(alpha = .3f), 200f, 140f, false, topLeft = points[k] - Offset(r * .55f, r * .7f),
            size = Size(r * 1.1f, r * 1.1f), style = Stroke(.45f * u),
        )
        drawCircle(Color.White.copy(alpha = .14f), r * .45f, points[k] - Offset(r * .2f, r * .45f))
    }
    for (k in points.indices.reversed()) drawCircle(LungGold, radius(k) * .45f, points[k] + Offset(0f, radius(k) * .5f))
    // Dorsal fin of golden spikes.
    for (k in 3 until LUNG_SEGMENTS - 3 step 2) {
        val a = points[k]
        drawCircle(LungMane, radius(k) * .32f, a - Offset(0f, radius(k) + .6f * u))
    }
    // Two pairs of little legs, paddling.
    for ((k, phase) in listOf(5 to 0f, 15 to PI.toFloat())) {
        val hip = points[k]
        val paddle = sin(time * 7f + phase) * 2f * u
        for (side in 0..1) {
            val foot = hip + Offset(paddle + (side - .5f) * 2.4f * u, radius(k) + 3f * u)
            drawLine(LungDark, hip, foot, 1.8f * u, StrokeCap.Round)
            drawLine(LungRed, hip, foot, 1.1f * u, StrokeCap.Round)
            drawCircle(LungGold, .6f * u, foot)
        }
    }

    val heading = points[0] - points[2]
    val angle = Math.toDegrees(atan2(heading.y, heading.x).toDouble()).toFloat()
    val flip = heading.x < 0f
    withTransform({
        rotate(angle, headPos)
        if (flip) scale(1f, -1f, headPos)
    }) {
        drawLungHead(headPos, time, u)
    }
}

/** Spiral up, dive to the bottom, swing back to [start] — the eastern dragon's figure; [t] 0..1. */
private fun spiralPath(start: Offset, d: Float, t: Float, top: Float, bottom: Float, u: Float): Offset {
    val radius = 8f * u
    return when {
        t < .45f -> {
            val p = t / .45f
            val theta = p * 3f * PI.toFloat()
            val cy = mix(start.y - radius, top + radius, smooth(p))
            val cx = start.x + d * 12f * u * p
            // The circle is entered smoothly from the cruising position.
            val onCircle = Offset(cx + d * radius * sin(theta), cy + radius * cos(theta))
            val entry = smooth(p * 5f)
            Offset(mix(start.x, onCircle.x, entry), mix(start.y, onCircle.y, entry))
        }
        t < .72f -> {
            val p = easeInCubic(((t - .45f) / .27f).coerceIn(0f, 1f)) * .6f + ((t - .45f) / .27f) * .4f
            val from = Offset(start.x + d * 12f * u, top)
            val control = Offset(start.x + d * 36f * u, top)
            val to = Offset(start.x + d * 42f * u, bottom)
            quad(from, control, to, p)
        }
        else -> {
            val p = smooth((t - .72f) / .28f)
            val from = Offset(start.x + d * 42f * u, bottom)
            val control = Offset(start.x + d * 66f * u, start.y + (bottom - start.y) * .1f)
            quad(from, control, start, p)
        }
    }
}

private fun quad(a: Offset, b: Offset, c: Offset, t: Float): Offset {
    val m = 1f - t
    return Offset(m * m * a.x + 2 * m * t * b.x + t * t * c.x, m * m * a.y + 2 * m * t * b.y + t * t * c.y)
}

private fun DrawScope.drawLungHead(head: Offset, time: Float, u: Float) {
    // Flowing whiskers trailing back from the snout.
    for (side in listOf(-1f, 1f)) {
        val whisker = Path().apply {
            val from = head + Offset(6.5f * u, side * .8f * u)
            moveTo(from.x, from.y)
            for (k in 1..6) {
                val px = from.x - k * 2.6f * u
                val py = from.y + side * (k * .7f * u) + sin(time * 5f + k * .9f + side) * 1.2f * u
                lineTo(px, py)
            }
        }
        drawPath(whisker, LungMane, style = Stroke(.6f * u, cap = StrokeCap.Round))
    }
    // Golden mane behind the head.
    for (k in 0..3) {
        val flow = sin(time * 4f + k) * .8f * u
        drawCircle(LungMane, (2.4f - k * .3f) * u, head + Offset((-3f - k * 1.6f) * u, (-2f + k * .5f) * u + flow))
    }
    // Antlers.
    val antler = Path().apply {
        moveTo(head.x - 1f * u, head.y - 2.4f * u)
        quadraticTo(head.x - 3f * u, head.y - 6f * u, head.x - 6f * u, head.y - 6.4f * u)
        moveTo(head.x - 3f * u, head.y - 5.2f * u)
        lineTo(head.x - 3.4f * u, head.y - 7.4f * u)
    }
    drawPath(antler, Color(0xFFF5E6C4), style = Stroke(1.1f * u, cap = StrokeCap.Round))
    drawOval(LungDark, head + Offset(-3.6f * u, -3.4f * u), Size(7.6f * u, 6.4f * u))
    drawOval(LungRed, head + Offset(-3.2f * u, -3f * u), Size(6.8f * u, 5.6f * u))
    drawRoundRectSafe(LungDark, head + Offset(1.6f * u, -1.8f * u), Size(6.4f * u, 3.8f * u), 1.6f * u)
    drawRoundRectSafe(LungRed, head + Offset(1.9f * u, -1.5f * u), Size(5.8f * u, 3.2f * u), 1.4f * u)
    drawRoundRectSafe(LungGold, head + Offset(2.2f * u, .2f * u), Size(5.2f * u, 1.2f * u), .6f * u)
    drawCircle(LungDark, .5f * u, head + Offset(7.3f * u, -.9f * u))
    drawOval(Color(0xFFFFF1B8), head + Offset(-.4f * u, -2.2f * u), Size(2.2f * u, 1.6f * u))
    drawCircle(Color(0xFF1B1B1B), .5f * u, head + Offset(.9f * u, -1.4f * u))
    drawLine(LungDark, head + Offset(-1f * u, -2.8f * u), head + Offset(2.2f * u, -2.4f * u), .8f * u, StrokeCap.Round)
}

/** The knee of a two-bone limb from [hip] to [paw], bending toward +x when [forward] (in the drawing's frame). */
private fun joint(hip: Offset, paw: Offset, upper: Float, lower: Float, forward: Boolean): Offset {
    val d = (paw - hip).getDistance().coerceAtLeast(.001f)
    if (d >= upper + lower) return hip + (paw - hip) * (upper / (upper + lower))
    val along = (upper * upper - lower * lower + d * d) / (2 * d)
    val h = sqrt(max(0f, upper * upper - along * along))
    val unit = (paw - hip) / d
    val side = Offset(-unit.y, unit.x)
    val mid = hip + unit * along
    return if (forward) mid - side * h else mid + side * h
}

/** Three soft puffs kicked up by the paws, drifting toward [toward] and fading over half a second. */
private fun DrawScope.drawDust(x: Float, ground: Float, age: Float, toward: Float, u: Float) {
    if (age !in 0f..DUST_SECONDS) return
    val t = age / DUST_SECONDS
    for (k in 0..2) {
        val spread = (2f + k * 2.6f + t * 4f) * u
        drawCircle(
            Color(0xFFB9B0A4).copy(alpha = (1f - t) * .38f), (1f + k * .3f + t * 2.2f) * u,
            Offset(x + toward * spread, ground - (1.2f + t * 2.4f + k * .4f) * u),
        )
    }
}

private const val DUST_SECONDS = .55f

/** The walk is split in quarters: the animal stops at 0, 25, 50, 75 and 100% of the way. */
private const val STOP_SPANS = 4
