package app.gains.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The app's motion, in one place so every screen moves alike. Motion here says what just changed —
 * a set ticked, a tab chosen, a number gone up — and is never decoration: nothing loops but the
 * few things that were already alive (the sign-in backdrop, the at-risk ring, the exercise demos).
 *
 * - [PRESS]: a finger on a card.
 * - [EXIT] / [STANDARD]: colours, fades and one piece of content handing over to another; the same
 *   timings as the screen transitions.
 * - [move]: things changing place or size (the tab pill, list items), a spring without bounce.
 * - [pop]: the few "done" moments only — a set ticked, a workout logged, a question answered.
 * - [REVEAL]: data arriving (charts drawing in, meters filling).
 *
 * Everything collapses to a cut when the platform asks for reduced motion ([LocalReduceMotion]).
 */
internal object Motion {
    const val PRESS = 120
    const val EXIT = 160
    const val STANDARD = 220
    const val REVEAL = 700
    /** Between items entering one after another; only the first few are staggered. */
    const val STAGGER = 50
    const val MAX_STAGGERED = 5

    @Composable @ReadOnlyComposable
    fun <T> press(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(PRESS)

    @Composable @ReadOnlyComposable
    fun <T> standard(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(STANDARD, easing = FastOutSlowInEasing)

    @Composable @ReadOnlyComposable
    fun <T> move(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    @Composable @ReadOnlyComposable
    fun <T> pop(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)

    @Composable @ReadOnlyComposable
    fun <T> reveal(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(REVEAL, easing = FastOutSlowInEasing)
}

/** True when the platform asks for less motion (iOS Reduce Motion, Android's animations turned off). */
internal val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * One screen giving way to the next: the new one fades in a short way from the side it is coming
 * from ([forward]: the right), the old one drifts a shorter way out to the other side. Used for the
 * screens themselves and for anything that pages like them (onboarding's questions, calendar months).
 */
internal fun <S> AnimatedContentTransitionScope<S>.screenSlide(forward: Boolean, reduce: Boolean): ContentTransform =
    if (reduce) EnterTransition.None togetherWith ExitTransition.None
    else (fadeIn(tween(Motion.STANDARD)) + slideInHorizontally(tween(Motion.STANDARD + 40)) { if (forward) it / 12 else -it / 12 }) togetherWith
        (fadeOut(tween(Motion.EXIT)) + slideOutHorizontally(tween(Motion.STANDARD)) { if (forward) -it / 16 else it / 16 })

/** One piece of content fading into another in place, the container easing to the new size. */
internal fun <S> AnimatedContentTransitionScope<S>.fadeThrough(reduce: Boolean): ContentTransform =
    if (reduce) EnterTransition.None togetherWith ExitTransition.None
    else (fadeIn(tween(Motion.STANDARD, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EXIT)))
        .using(SizeTransform(clip = false) { _, _ -> tween<IntSize>(Motion.STANDARD, easing = FastOutSlowInEasing) })

/**
 * A value rolling to its next one: up when it grew, down when it shrank, a plain fade when the two
 * are not numbers that compare.
 */
internal fun AnimatedContentTransitionScope<String>.roll(reduce: Boolean): ContentTransform {
    if (reduce) return EnterTransition.None togetherWith ExitTransition.None
    val from = initialState.leadingNumber()
    val to = targetState.leadingNumber()
    val direction = if (from == null || to == null || from == to) 0 else if (to > from) 1 else -1
    if (direction == 0) return fadeThrough(false)
    return (slideInVertically(tween(Motion.STANDARD, easing = FastOutSlowInEasing)) { it * direction / 2 } + fadeIn(tween(Motion.STANDARD)))
        .togetherWith(slideOutVertically(tween(Motion.STANDARD, easing = FastOutSlowInEasing)) { -it * direction / 2 } + fadeOut(tween(Motion.EXIT)))
        .using(SizeTransform(clip = false))
}

/** The number a label starts with ("12", "−3.5 kg", "1 240"), or null when it does not start with one. */
internal fun String.leadingNumber(): Double? {
    val match = Regex("^[+\\-\u2212]?[\\d ,.\u00A0\u202F]*\\d").find(trim()) ?: return null
    return match.value.replace('\u2212', '-').filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
}

/**
 * What this spot showed the last time it was on screen, kept with the screen's saved state. A value
 * that changed while the screen was covered — the streak, after a workout — can then still be seen
 * changing when the lifter comes back, instead of simply being there. The first time, it is [value].
 */
@Composable
internal fun <T : Any> rememberPreviouslyShown(value: T): T {
    var shown by rememberSaveable { mutableStateOf(value) }
    val previous = remember { shown }
    SideEffect { shown = value }
    return previous
}

/**
 * Rises and fades in once, the first time the screen shows it; [index] staggers a few in a row. Kept
 * with the screen's saved state, so scrolling back or returning to the screen does not play it again.
 */
@Composable
internal fun Modifier.enterOnce(index: Int = 0): Modifier {
    val reduce = LocalReduceMotion.current
    var played by rememberSaveable { mutableStateOf(reduce) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        kotlinx.coroutines.delay((index.coerceAtMost(Motion.MAX_STAGGERED) * Motion.STAGGER).toLong())
        progress.animateTo(1f, tween(Motion.STANDARD + 120, easing = FastOutSlowInEasing))
        played = true
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 16.dp.toPx()
    }
}

/** The small pop a control gives when it is switched on: in from [from] with a little overshoot. */
internal fun popIn(reduce: Boolean, from: Float = 0.6f): EnterTransition =
    if (reduce) EnterTransition.None
    else scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), initialScale = from) + fadeIn(tween(Motion.PRESS))
