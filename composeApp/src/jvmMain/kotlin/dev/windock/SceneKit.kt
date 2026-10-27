package dev.windock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

internal const val TAU = 6.2831855f

/** Decorative scenes don't need the display's full refresh rate; 30 fps looks the same and costs half. */
internal const val SCENE_FPS = 30

/**
 * Seconds since the scene appeared. One continuous clock drives every motion so loops never
 * stall or drift into sync; read it inside a draw lambda so only the draw phase is invalidated.
 * Frames are requested only every 1/[SCENE_FPS] s, so the window isn't redrawn on every vsync.
 */
@Composable
internal fun rememberSceneClock(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    val frameMs = 1000L / SCENE_FPS
    while (true) {
        withFrameNanos { value = (it - start) / 1_000_000_000f }
        delay(frameMs)
    }
}

internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun clamp01(t: Float) = t.coerceIn(0f, 1f)
internal fun smooth(t: Float) = clamp01(t).let { it * it * (3f - 2f * it) }
internal fun easeInCubic(t: Float) = t * t * t
internal fun easeOutBack(t: Float): Float {
    val c = 1.4f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/** Stable pseudo-random value in 0..1 for a seed, so procedural shapes look hand-placed but never flicker. */
internal fun hash01(seed: Float): Float {
    val v = sin(seed * 12.9898f) * 43758.547f
    return v - kotlin.math.floor(v)
}

/** Point [length] away from [from] at [degrees] (0 = right, -90 = up); [dir] -1 mirrors it horizontally. */
internal fun along(from: Offset, degrees: Float, length: Float, dir: Float = 1f): Offset {
    val r = Math.toRadians(degrees.toDouble())
    return Offset(from.x + dir * cos(r).toFloat() * length, from.y + sin(r).toFloat() * length)
}

/**
 * Organic 0..1 flicker for flames and candle light: a few incommensurate sines, so the pattern never
 * visibly repeats. Different [seed]s give independent flames.
 */
internal fun flicker(time: Float, seed: Float): Float =
    (.5f + .25f * sin(time * 7.3f + seed) + .15f * sin(time * 13.1f + seed * 2.3f) + .1f * sin(time * 23.7f + seed * 4.1f)).coerceIn(0f, 1f)
