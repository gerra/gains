package app.gains.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gains.resources.Res
import app.gains.resources.done
import app.gains.resources.erase
import app.gains.resources.hide_keyboard
import app.gains.resources.next
import app.gains.resources.one_less
import app.gains.resources.one_more
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import org.jetbrains.compose.resources.stringResource

/*
 * The app's own number pad, used in place of the platform's keyboard for a set's reps, seconds and
 * distance. The system keypads differ by platform (iOS's has no Done key, so it could not be put away),
 * cover whatever sits at the foot of the screen and can't be told apart from any other keyboard. This
 * one is the app's: it has a key to put it away, a Next key that walks the set table, ±1 for the
 * rep that was one more or one less than planned, and it sits under the workout's clock so the rest
 * stays in sight while the reps go in.
 *
 * A field claims the pad by taking focus ([NumberPadField]) and lets go when it loses it, so
 * everything that drops focus (a tap outside, another field, leaving the screen) also puts the pad
 * away. On the desktop the focused field takes the hardware keyboard's digits as well.
 */

/** A key of the pad that changes the value. Next and the hide key act on the pad, not the value. */
internal sealed interface PadKey {
    data class Digit(val digit: Int) : PadKey
    data object Point : PadKey
    data object Backspace : PadKey
    data object Minus : PadKey
    data object Plus : PadKey
}

internal object PadInput {
    /**
     * [value] after [key]. While [replace] is set (the field was just opened, so its value reads as
     * selected) a digit or the point starts the value afresh and the backspace clears it, as typing
     * over a selection would; ±1 always steps what is there. [decimal] allows one point; [maxLength]
     * caps the characters, past which a digit is ignored. A leading zero gives way to the next digit.
     */
    fun apply(value: String, key: PadKey, replace: Boolean, decimal: Boolean, maxLength: Int): String = when (key) {
        is PadKey.Digit -> {
            val base = if (replace || value == "0") "" else value
            if (base.length >= maxLength) value else base + key.digit
        }
        PadKey.Point -> {
            val base = if (replace) "" else value
            when {
                !decimal || '.' in base -> value
                base.isEmpty() -> "0."
                base.length >= maxLength - 1 -> value
                else -> "$base."
            }
        }
        PadKey.Backspace -> if (replace) "" else value.dropLast(1)
        PadKey.Minus -> step(value, -1)
        PadKey.Plus -> step(value, +1)
    }

    /**
     * The whole part of [value] moved by [delta], the decimals kept as typed ("5.25" + 1 is "6.25"):
     * stepping by whole units never rounds what was entered. Never below zero; an empty value
     * stays empty going down and becomes 1 going up.
     */
    private fun step(value: String, delta: Int): String {
        val whole = value.substringBefore('.')
        val fraction = if ('.' in value) "." + value.substringAfter('.') else ""
        val n = whole.toIntOrNull() ?: if (whole.isEmpty()) 0 else return value
        if (value.isEmpty() && delta < 0) return value
        return (n + delta).coerceAtLeast(0).toString() + fraction
    }
}

/**
 * Which field the pad types into, if any; a screen holds one and shows [NumberPad] for it. Also
 * tells the frame around the screen that the pad is up, so it can fold the tabs away under it as a
 * system keyboard would cover them (see [LocalNumberPadOpen]).
 */
@Stable
internal class NumberPadState {
    internal var field by mutableStateOf<PadField?>(null)
    val isOpen: Boolean get() = this.field != null
}

/** The focused field as the pad sees it: what its keys do to it. */
internal class PadField(val decimal: Boolean, val hasNext: Boolean, val onKey: (PadKey) -> Unit, val onNext: () -> Unit)

/**
 * Set by the app's frame and switched on by a screen while its pad is up: the frame folds the tabs
 * away so the pad takes their place at the foot of the screen. Null outside the frame (a test that
 * renders one screen), where there is nothing to fold.
 */
internal val LocalNumberPadOpen = compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/**
 * A field typed with the [pad] rather than the system keyboard. Tapping it focuses it, which opens
 * the pad; [content] draws it, told whether it is [focused] and whether its value is [selected]
 * (just opened, so the first key replaces it). [onNext], when set, is what the pad's Next key does,
 * usually focusing the next field; without it the key reads Done and puts the pad away.
 */
@Composable
internal fun NumberPadField(
    value: String,
    onChange: (String) -> Unit,
    pad: NumberPadState,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    decimal: Boolean = false,
    maxLength: Int = 3,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onNext: (() -> Unit)? = null,
    content: @Composable (focused: Boolean, selected: Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val next by rememberUpdatedState(onNext)
    val field = remember(decimal, maxLength, onNext != null) {
        PadField(
            decimal = decimal, hasNext = onNext != null,
            onKey = { key ->
                change(PadInput.apply(current, key, selected, decimal, maxLength))
                selected = false
            },
            onNext = { next?.invoke() ?: focusManager.clearFocus() },
        )
    }
    // The pad follows focus: claimed while this field has it, given back when it goes.
    SideEffect { if (focused && pad.field !== field) pad.field = field }
    val latest by rememberUpdatedState(field)
    DisposableEffect(pad) { onDispose { if (pad.field === latest) pad.field = null } }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.hasFocus == focused) return@onFocusChanged
                focused = state.hasFocus
                selected = state.hasFocus
                if (state.hasFocus) pad.field = field else if (pad.field === field) pad.field = null
            }
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val char = event.utf16CodePoint.toChar()
                val key = when {
                    char in '0'..'9' -> PadKey.Digit(char - '0')
                    char == '.' || char == ',' -> PadKey.Point
                    event.key == Key.Backspace -> PadKey.Backspace
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> { field.onNext(); return@onPreviewKeyEvent true }
                    event.key == Key.Escape -> { focusManager.clearFocus(); return@onPreviewKeyEvent true }
                    else -> return@onPreviewKeyEvent false
                }
                field.onKey(key)
                true
            }
            .focusable(enabled, interaction)
            // The tap only moves focus here; the click itself must not be a second stop for Tab.
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { focusRequester.requestFocus() }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(focused, selected) }
}

/**
 * The pad itself, at the foot of the screen while a field is focused: digits in a phone's order,
 * and down the right the key that puts it away, −1, +1 and Next. It rises in under whatever is
 * pinned above it (the workout's clock), and steps out of the way of the system's gesture bar.
 */
@Composable
internal fun NumberPad(pad: NumberPadState, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val focusManager = LocalFocusManager.current
    // Kept while it folds away, so the keys go with their labels.
    var last by remember { mutableStateOf(pad.field) }
    SideEffect { pad.field?.let { last = it } }
    // The frame folds the tabs while the pad is up, and unfolds them when it goes or the screen does.
    val open = LocalNumberPadOpen.current
    SideEffect { open?.value = pad.isOpen }
    DisposableEffect(open) { onDispose { open?.value = false } }
    AnimatedVisibility(
        pad.isOpen, modifier,
        enter = if (reduce) EnterTransition.None else expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
        exit = if (reduce) ExitTransition.None else shrinkVertically(tween(Motion.STANDARD)) + fadeOut(tween(Motion.EXIT)),
    ) {
        val field = pad.field ?: last ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().testTag(NUMBER_PAD_TAG)
                .padding(top = 8.dp)
                .clip(PadShape).background(MaterialTheme.colorScheme.surfaceContainer)
                // A tap between the keys is still a tap on the pad: it must not reach the frame, which would put the pad away.
                .pointerInput(Unit) { detectTapGestures {} }
                .navigationBarsPadding()
                .padding(PAD_PADDING),
            verticalArrangement = Arrangement.spacedBy(KEY_GAP),
        ) {
            val hide = stringResource(Res.string.hide_keyboard)
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                for (d in 1..3) DigitKey(d, field)
                IconKey(hide, onClick = { focusManager.clearFocus() }) {
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                for (d in 4..6) DigitKey(d, field)
                TextKey("−1", stringResource(Res.string.one_less)) { field.onKey(PadKey.Minus) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                for (d in 7..9) DigitKey(d, field)
                TextKey("+1", stringResource(Res.string.one_more)) { field.onKey(PadKey.Plus) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                // The point only where a value can have one; otherwise the corner is left empty, as on a phone's keypad.
                if (field.decimal) TextKey(".", null) { field.onKey(PadKey.Point) } else Box(Modifier.weight(1f))
                DigitKey(0, field)
                IconKey(stringResource(Res.string.erase), onClick = { field.onKey(PadKey.Backspace) }) { BackspaceGlyph(MaterialTheme.colorScheme.onSurface) }
                val nextLabel = if (field.hasNext) stringResource(Res.string.next) else stringResource(Res.string.done)
                Key(nextLabel, fill = MaterialTheme.colorScheme.primary, onClick = field.onNext) {
                    Text(nextLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                }
            }
        }
    }
}

/** Finds the pad in tests. */
internal const val NUMBER_PAD_TAG = "number-pad"

@Composable
private fun RowScope.DigitKey(digit: Int, field: PadField) = TextKey(digit.toString(), null) { field.onKey(PadKey.Digit(digit)) }

@Composable
private fun RowScope.TextKey(label: String, description: String?, onClick: () -> Unit) =
    Key(description, onClick = onClick) { Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }

@Composable
private fun RowScope.IconKey(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) = Key(description, onClick = onClick, content = icon)

/**
 * One key: a raised box, the secondary button's fill unless [fill] says otherwise. It never takes
 * focus, so pressing it leaves the field being typed into focused.
 */
@Composable
private fun RowScope.Key(description: String?, fill: Color = MaterialTheme.colorScheme.surfaceContainerHighest, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.weight(1f).height(KEY_HEIGHT).clip(KeyShape).background(fill)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The backspace mark (Material's "backspace", outlined), drawn here as the bundled icon set lacks it. */
@Composable
private fun BackspaceGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(BackspacePath, color, style = Fill) }
    }
}

private val BackspacePath: Path = PathParser().parsePathString(
    "M22,3H7C6.31,3 5.77,3.35 5.41,3.88L0,12l5.41,8.11C5.77,20.64 6.31,21 7,21h15c1.1,0 2,-0.9 2,-2V5C24,3.9 23.1,3 22,3z" +
        "M22,19H7.07L2.4,12l4.66,-7H22V19z" +
        "M10.41,17L14,13.41 17.59,17 19,15.59 15.41,12 19,8.41 17.59,7 14,10.59 10.41,7 9,8.41 12.59,12 9,15.59z",
).toPath()

/** Keys are a button's height: four rows and the gaps come to about a phone keypad's height, less a little. */
private val KEY_HEIGHT = 48.dp
private val KEY_GAP = 8.dp
private val PAD_PADDING = 12.dp
private val KeyShape = RoundedCornerShape(10.dp)
/** A sheet's top corners: the pad is a panel that rises from the foot of the screen. */
private val PadShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
