package app.gains.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.gains.analysis.Dates
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.analysis.Format
import app.gains.domain.WeightUnit
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Value choosers in the style of iOS: a spinning wheel for anything numeric (time, duration,
 * weight) and a calendar for dates, each in a bottom sheet that applies the value as it changes.
 * Nothing here is typed; there is no keyboard to put away and no format to get wrong.
 */

private val WheelRowHeight = 40.dp
private val WheelBandShape = RoundedCornerShape(10.dp)

/**
 * A single spinning column: [items] are the labels, [selected] the index under the band. Flicks
 * snap to a row and each row that passes the band ticks the haptics. [onSelect] fires once the
 * wheel has settled, never mid-fling, so the caller's value is always the row that can be read.
 * A value set from outside (a stepper, a reset) turns the wheel to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WheelPicker(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleRows: Int = 5,
    /** Drawn behind the middle row; off when the caller draws one band across several wheels. */
    showBand: Boolean = true,
    /** The colour the top and bottom rows fade into: the surface the wheel sits on. */
    fadeColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    require(visibleRows % 2 == 1) { "A wheel needs a middle row" }
    val pad = visibleRows / 2
    val count = items.size
    val current = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(current)

    // The real row nearest the middle of the viewport (the blank rows at either end never qualify).
    val centred by remember(count) {
        derivedStateOf {
            val info = state.layoutInfo
            if (info.visibleItemsInfo.isEmpty() || count == 0) return@derivedStateOf null
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.let { (it.index - pad).coerceIn(0, count - 1) }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { centred }.filterNotNull().drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    LaunchedEffect(state) {
        snapshotFlow { centred to state.isScrollInProgress }.collect { (index, scrolling) ->
            if (index != null && !scrolling && index != latest) onSelect(index)
        }
    }
    LaunchedEffect(current) {
        if (!state.isScrollInProgress && centred != null && centred != current) state.animateScrollToItem(current)
    }

    Box(modifier.height(WheelRowHeight * visibleRows), contentAlignment = Alignment.Center) {
        if (showBand) WheelBand(Modifier.fillMaxWidth())
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to fadeColor, 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to fadeColor))
            },
        ) {
            items(pad) { Spacer(Modifier.height(WheelRowHeight)) }
            itemsIndexed(items) { index, label ->
                val distance = centred?.let { abs(it - index) } ?: pad
                val style = MaterialTheme.typography.titleLarge
                Box(
                    Modifier.fillMaxWidth().height(WheelRowHeight)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { state.animateScrollToItem(index) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (distance == 0) style else style.copy(fontWeight = FontWeight.Normal),
                        color = if (distance == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = (1f - 0.22f * distance).coerceAtLeast(0.35f)),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(pad) { Spacer(Modifier.height(WheelRowHeight)) }
        }
    }
}

/** The highlighted middle row of a wheel, one row tall. Drawn once across a row of wheels that share a value. */
@Composable
internal fun WheelBand(modifier: Modifier = Modifier) {
    Box(modifier.height(WheelRowHeight).clip(WheelBandShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

/**
 * The bottom sheet every chooser sits in: a title, the wheels or calendar, and Done. Values apply
 * as they change, so swiping the sheet away is the same as Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    /** A small action beside the title, such as a reset. */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Done", onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Label on the left, the current value and a chevron on the right; tapping opens the chooser. */
@Composable
internal fun ChooserRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp)
            .semantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A calendar, a month at a time; the picked day applies straight away. */
@Composable
internal fun DatePickerSheet(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    PickerSheet("Date", onDismiss = onDismiss) { CalendarPicker(date, onPick) }
}

/**
 * A month grid, Monday first, with the chosen day filled and today ringed. Material's own
 * DatePicker is not used: the one in this Compose release still looks for kotlinx.datetime.Instant,
 * which kotlinx-datetime 0.7 moved to kotlin.time, and crashes the moment it is shown.
 */
@Composable
internal fun CalendarPicker(selected: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = Dates.today()
    var month by remember(selected.year, selected.month) { mutableStateOf(LocalDate(selected.year, selected.month, 1)) }
    val daysInMonth = month.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
    // Blank cells before the 1st, so every column is one weekday.
    val leading = month.dayOfWeek.isoDayNumber - 1
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${Dates.monthName(month)} ${month.year}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            MonthArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") { month = month.minus(1, DateTimeUnit.MONTH) }
            Spacer(Modifier.width(6.dp))
            MonthArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") { month = month.plus(1, DateTimeUnit.MONTH) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            for (day in DayOfWeek.entries) {
                Text(Dates.dayLabel(day).take(2), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val rows = (leading + daysInMonth + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayNumber = row * 7 + column - leading + 1
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val day = LocalDate(month.year, month.month, dayNumber)
                            val isSelected = day == selected
                            val isToday = day == today
                            val description = "${Dates.dayLabel(day.dayOfWeek)} ${Dates.shortWithYear(day)}" + if (isSelected) ", chosen" else ""
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (isSelected) palette.volt else Color.Transparent)
                                    .border(1.5.dp, if (isToday && !isSelected) palette.volt else Color.Transparent, CircleShape)
                                    .clickable { onPick(day) }
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    dayNumber.toString(),
                                    style = if (isSelected || isToday) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> palette.volt
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
}

private val HourLabels = List(24) { it.toString().padStart(2, '0') }
private val MinuteLabels = List(60) { it.toString().padStart(2, '0') }

/** Hours and minutes on two wheels, 24-hour. */
@Composable
internal fun TimePickerSheet(time: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    PickerSheet("Time", onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WheelBand(Modifier.width(200.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(HourLabels, time.hour, { onPick(LocalTime(it, time.minute)) }, Modifier.width(80.dp), showBand = false)
                Text(":", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                WheelPicker(MinuteLabels, time.minute, { onPick(LocalTime(time.hour, it)) }, Modifier.width(80.dp), showBand = false)
            }
        }
    }
}

private val DurationHourLabels = List(24) { it.toString() }
private val DurationMinuteLabels = List(60) { it.toString() }

/**
 * Hours and minutes on two wheels, as the timer in Clock lays them out. Inline, so it can sit in a
 * sheet or a dialog. [fadeColor] is the surface it sits on.
 */
@Composable
internal fun DurationWheels(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, fadeColor: Color = MaterialTheme.colorScheme.surfaceContainer) {
    val total = minutes.coerceIn(0, 24 * 60 - 1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        WheelBand(Modifier.width(240.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(DurationHourLabels, total / 60, { onChange(it * 60 + total % 60) }, Modifier.width(64.dp), showBand = false, fadeColor = fadeColor)
            Text("h", style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.padding(start = 4.dp, end = 28.dp))
            WheelPicker(DurationMinuteLabels, total % 60, { onChange((total / 60) * 60 + it) }, Modifier.width(64.dp), showBand = false, fadeColor = fadeColor)
            Text("min", style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** How long a workout took; zero means it was not timed and is stored as no duration. */
@Composable
internal fun DurationPickerSheet(minutes: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    PickerSheet("Duration", onDismiss = onDismiss, subtitle = "Leave at zero if you didn't time it.") {
        DurationWheels(minutes ?: 0, onChange = { onPick(it.takeIf { m -> m > 0 }) })
    }
}

/**
 * A weight as the wheels hold it: whole units and quarters, which is the app's stored precision.
 * Parsed from whatever the row held, and turned back into the row's text.
 */
internal data class WheelWeight(val whole: Int, val quarters: Int) {
    val value: Double get() = whole + quarters / 4.0

    /** The set row's text: "62.5", "60"; empty when zero, which the row reads as no added weight. */
    val text: String get() = if (whole == 0 && quarters == 0) "" else Format.number(value, 2)

    fun plus(delta: Double, max: Int): WheelWeight = of((value + delta).coerceIn(0.0, max.toDouble()))

    companion object {
        fun parse(text: String): WheelWeight = of(text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0)

        fun of(value: Double): WheelWeight {
            val quarters = (value.coerceAtLeast(0.0) * 4).roundToInt()
            return WheelWeight(quarters / 4, quarters % 4)
        }

        /** The heaviest whole value the wheel offers. */
        fun max(unit: WeightUnit): Int = if (unit == WeightUnit.KG) 500 else 1100

        /** Plate jumps for the quick buttons: a small and a large step either way. */
        fun steps(unit: WeightUnit): List<Double> = if (unit == WeightUnit.KG) listOf(-5.0, -2.5, 2.5, 5.0) else listOf(-10.0, -5.0, 5.0, 10.0)
    }
}

private val QuarterLabels = listOf(".00", ".25", ".50", ".75")

/**
 * Whole units and quarters on two wheels, as Health enters a body weight, with plate-jump buttons
 * for the usual step between sets. [value] is the row's text and [onPick] receives the new text.
 */
@Composable
internal fun WeightPickerSheet(value: String, unit: WeightUnit, title: String, subtitle: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val weight = WheelWeight.parse(value)
    val max = WheelWeight.max(unit)
    val wholeLabels = remember(max) { List(max + 1) { it.toString() } }
    PickerSheet(
        title, onDismiss = onDismiss, subtitle = subtitle,
        trailing = { TextButton(onClick = { onPick("") }) { Text("No weight", color = muted) } },
    ) {
        Text(
            if (weight.value == 0.0) "No weight" else Format.number(weight.value, 2) + " " + unit.label,
            style = MaterialTheme.typography.displaySmall, color = palette.volt, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WheelBand(Modifier.width(220.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(wholeLabels, weight.whole, { onPick(WheelWeight(it, weight.quarters).text) }, Modifier.width(96.dp), showBand = false)
                WheelPicker(QuarterLabels, weight.quarters, { onPick(WheelWeight(weight.whole, it).text) }, Modifier.width(80.dp), showBand = false)
                Text(unit.label, style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for (step in WheelWeight.steps(unit)) {
                StepChip((if (step < 0) "−" else "+") + Format.number(abs(step), 2)) { onPick(weight.plus(step, max).text) }
            }
        }
    }
}

@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
}
