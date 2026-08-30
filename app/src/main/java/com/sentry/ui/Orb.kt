package com.sentry.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The orb.
 *
 * Four soft radial blobs orbiting on ellipses at incommensurate rates, composited
 * additively inside a circle and blurred. Because the rates never line up, the shape
 * never repeats — which is the whole trick behind this class of animation, and the
 * reason it reads as alive rather than as a looping GIF.
 *
 * Scale follows the microphone through a spring rather than tracking it directly.
 * Raw amplitude is jittery and a shape that follows it exactly looks like a VU meter;
 * a slightly under-damped spring lags the voice by a few frames and reads as
 * something responding to you.
 */
@Composable
fun Orb(
    state: AssistantViewModel.UiState,
    amplitude: Float,
    justOpened: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Cross-faded rather than swapped. An instant palette change reads as a cut;
    // the hand-off from answering to listening is the moment we most want to read
    // as one continuous thing paying attention.
    val target = paletteFor(state)
    val palette = Palette(
        core = animateColorAsState(target.core, tween(FADE_MS), label = "core").value,
        first = animateColorAsState(target.first, tween(FADE_MS), label = "first").value,
        second = animateColorAsState(target.second, tween(FADE_MS), label = "second").value,
        third = animateColorAsState(target.third, tween(FADE_MS), label = "third").value,
        fourth = animateColorAsState(target.fourth, tween(FADE_MS), label = "fourth").value,
        rim = animateColorAsState(target.rim, tween(FADE_MS), label = "rim").value,
    )

    val transition = rememberInfiniteTransition(label = "orb")

    // Three rotations at deliberately unrelated periods.
    val slow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Restart),
        label = "slow",
    )
    val medium by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7_300, easing = LinearEasing), RepeatMode.Restart),
        label = "medium",
    )
    val fast by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_100, easing = LinearEasing), RepeatMode.Restart),
        label = "fast",
    )

    /** A slow breath, so an idle orb is never perfectly still. */
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3_800, easing = LinearEasing), RepeatMode.Restart),
        label = "breath",
    )

    // Thinking has no microphone input to react to, so it gets its own pulse —
    // otherwise the orb goes dead exactly when the user is waiting on it.
    val thinkingPulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "thinking",
    )

    val reactive = remember { Animatable(0f) }
    LaunchedEffect(amplitude, state) {
        val energy = when (state) {
            // Speech lands roughly in 0.12..0.35 on this meter, so feeding raw
            // amplitude into a 0..1 response used about a fifth of the range and the
            // orb barely moved. Subtract the floor, rescale, and put a gamma on it so
            // quiet speech still registers visibly.
            AssistantViewModel.UiState.LISTENING ->
                (((amplitude - FLOOR) / SPAN).coerceIn(0f, 1f)).pow(0.6f)

            AssistantViewModel.UiState.SPEAKING -> 0.35f + amplitude * 0.2f
            AssistantViewModel.UiState.THINKING -> 0.25f
            AssistantViewModel.UiState.IDLE -> 0f
        }
        reactive.animateTo(
            targetValue = energy,
            animationSpec = spring(
                dampingRatio = 0.55f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // One bump at the hand-off, kept separate from the amplitude response so the two
    // compose instead of fighting over the same value.
    val greeting = remember { Animatable(0f) }
    LaunchedEffect(justOpened) {
        if (justOpened) {
            greeting.snapTo(0f)
            greeting.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
            greeting.animateTo(0f, tween(420))
        }
    }

    val pulse = when (state) {
        AssistantViewModel.UiState.THINKING -> 0.06f * sin(thinkingPulse)
        else -> 0.02f * sin(breath)
    }
    val scale = 1f + reactive.value * 0.20f + pulse + greeting.value * 0.12f

    Canvas(
        modifier = modifier
            // Blur turns the separate blobs into one soft body. Unbounded edge
            // treatment matters: the default clamps the blur to the layer's
            // rectangle, which draws a visible square around a round orb.
            .blurCompat(26.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        // Drawn well inside the canvas: a blur samples outwards, and a shape drawn
        // to the edge would have its halo cut off square by the layer bounds.
        val radius = size.minDimension / 2f * 0.72f
        val centre = Offset(size.width / 2f, size.height / 2f)

        // Every layer is a radial gradient that reaches zero alpha before its own
        // edge, so the orb is round by construction rather than by clipping.
        blob(centre, radius, slow, 0.30f, 0.95f, palette.first, reactive.value)
        blob(centre, radius, medium + 120f, 0.26f, 0.88f, palette.second, reactive.value)
        blob(centre, radius, fast + 240f, 0.22f, 0.80f, palette.third, reactive.value)
        blob(centre, radius, -medium + 60f, 0.18f, 0.72f, palette.fourth, reactive.value)

        // A single ring, expanding once as the microphone reopens. Deliberately not
        // a repeating pulse: a ring beating on a timer beside an orb reacting to the
        // voice makes the orb look less responsive than it actually is.
        if (greeting.value > 0.01f) {
            val spread = 0.72f + (1f - greeting.value) * 0.63f
            drawCircle(
                color = palette.rim.copy(alpha = 0.5f * greeting.value),
                radius = radius * spread,
                center = centre,
                style = Stroke(width = radius * 0.045f),
            )
        }

        // A hot core, standing in for the additive blend an offscreen layer would
        // have given us — without the square that came with it.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.rim.copy(alpha = 0.55f + reactive.value * 0.35f),
                    palette.core.copy(alpha = 0.35f),
                    Color.Transparent,
                ),
                center = centre,
                radius = radius * (0.75f + reactive.value * 0.15f),
            ),
            radius = radius * (0.75f + reactive.value * 0.15f),
            center = centre,
        )
    }
}

/**
 * One orbiting blob.
 *
 * @param phase degrees around the orbit
 * @param orbit how far from the centre it travels, as a fraction of the radius
 * @param extent the blob's own radius, as a fraction of the orb's
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(
    centre: Offset,
    radius: Float,
    phase: Float,
    orbit: Float,
    extent: Float,
    color: Color,
    energy: Float,
) {
    val radians = phase * PI.toFloat() / 180f
    // An ellipse rather than a circle: a circular orbit makes the motion read as a
    // rotating rigid object, which is precisely the wrong impression.
    val distance = radius * orbit * (1f + energy * 0.35f)
    val position = Offset(
        centre.x + cos(radians) * distance,
        centre.y + sin(radians) * distance * 0.75f,
    )
    val blobRadius = radius * extent * (1f + energy * 0.18f)

    drawCircle(
        brush = Brush.radialGradient(
            // Three stops rather than two: a linear fade to transparent reads as a
            // hard-edged disc once blurred, while an early mid-stop keeps the falloff
            // soft all the way out.
            colors = listOf(
                color.copy(alpha = 0.70f),
                color.copy(alpha = 0.28f),
                color.copy(alpha = 0f),
            ),
            center = position,
            radius = blobRadius,
        ),
        radius = blobRadius,
        center = position,
    )
}

private data class Palette(
    val core: Color,
    val first: Color,
    val second: Color,
    val third: Color,
    val fourth: Color,
    val rim: Color,
)

/**
 * Colour carries the state, so the orb is legible with the screen at arm's length and
 * the text unreadable.
 */
private fun paletteFor(state: AssistantViewModel.UiState): Palette = when (state) {
    // Idle is dimmer than the active states but not by so much that the orb looks
    // switched off — it is the only thing on screen telling you Sentry is there.
    AssistantViewModel.UiState.IDLE -> Palette(
        core = Color(0xFF3A4A80),
        first = Color(0xFF5B7FD4),
        second = Color(0xFF6C5CC0),
        third = Color(0xFF4568B0),
        fourth = Color(0xFF7E6ACF),
        rim = Color(0xFFA8C0F5),
    )

    AssistantViewModel.UiState.LISTENING -> Palette(
        core = Color(0xFF1B4C7A),
        first = Color(0xFF23D3F0),
        second = Color(0xFF3B82F6),
        third = Color(0xFF7C5CFF),
        fourth = Color(0xFF12B9C9),
        rim = Color(0xFF9BE8FF),
    )

    AssistantViewModel.UiState.THINKING -> Palette(
        core = Color(0xFF44206B),
        first = Color(0xFF9B5CFF),
        second = Color(0xFFE05CC8),
        third = Color(0xFF5C6BFF),
        fourth = Color(0xFFFF8A5C),
        rim = Color(0xFFE8B4FF),
    )

    AssistantViewModel.UiState.SPEAKING -> Palette(
        core = Color(0xFF12513F),
        first = Color(0xFF2BE0A8),
        second = Color(0xFF3BC7F6),
        third = Color(0xFF6EE07A),
        fourth = Color(0xFF23A5C4),
        rim = Color(0xFFA6FFE0),
    )
}

/** Amplitude below this is room tone; the span above it is where speech lives. */
private const val FLOOR = 0.06f
private const val SPAN = 0.34f

/** Palette cross-fade duration for a state change. */
private const val FADE_MS = 260

/** [blur] exists only from API 31; below it this is simply nothing. */
private fun Modifier.blurCompat(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blur(radius, BlurredEdgeTreatment.Unbounded)
    } else {
        this
    }
