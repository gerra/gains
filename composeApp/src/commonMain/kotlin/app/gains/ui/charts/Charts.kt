package app.gains.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gains.analysis.Dates
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartPoint(val x: Double, val y: Double)

data class LineSeries(
    val points: List<ChartPoint>,
    val color: Color,
    val label: String,
    val showDots: Boolean = true,
    val dashed: Boolean = false,
    /** Drawn against a separate right-hand axis. */
    val secondaryAxis: Boolean = false,
)

data class StackedBar(val label: String, val segments: List<Pair<Color, Double>>) {
    val total: Double get() = segments.sumOf { it.second }
}

data class ReferenceLine(val y: Double, val color: Color, val label: String)

object ChartMath {
    /** "Nice" axis ticks covering [min, max]. */
    fun ticks(min: Double, max: Double, count: Int = 4): List<Double> {
        if (max <= min) return listOf(min)
        val raw = (max - min) / count
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val step = when {
            norm <= 1 -> 1.0
            norm <= 2 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5 -> 5.0
            else -> 10.0
        } * mag
        val start = floor(min / step) * step
        val end = ceil(max / step) * step
        val result = ArrayList<Double>()
        var v = start
        while (v <= end + step / 2) { result.add(v); v += step }
        return result
    }

    fun LocalDate.x(): Double = toEpochDays().toDouble()
    fun fromX(x: Double): LocalDate = LocalDate.fromEpochDays(x.toLong())
}

private fun DrawScope.axisLabel(measurer: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, alignRight: Boolean = false, alignCenter: Boolean = false) {
    val layout = measurer.measure(text, style)
    val left = when {
        alignRight -> x - layout.size.width
        alignCenter -> x - layout.size.width / 2f
        else -> x
    }
    drawText(layout, topLeft = Offset(left, y))
}

@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    xLabel: (Double) -> String = { Dates.short(ChartMath.fromX(it)) },
    yLabel: (Double) -> String = { formatAxis(it) },
    secondaryLabel: (Double) -> String = { formatAxis(it) },
    yMinZero: Boolean = false,
    showLegend: Boolean = series.size > 1,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = labelColor.copy(alpha = 0.2f)
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val primary = series.filter { !it.secondaryAxis }
    val secondary = series.filter { it.secondaryAxis }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val all = series.flatMap { it.points }
            if (all.isEmpty()) return@Canvas
            val leftPad = 44.dp.toPx()
            val rightPad = if (secondary.isNotEmpty()) 44.dp.toPx() else 12.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 20.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad

            val xMin = all.minOf { it.x }
            val xMaxRaw = all.maxOf { it.x }
            val xMax = if (xMaxRaw > xMin) xMaxRaw else xMin + 1
            fun px(x: Double) = leftPad + ((x - xMin) / (xMax - xMin) * plotW).toFloat()

            fun scale(pts: List<ChartPoint>): Pair<List<Double>, (Double) -> Float> {
                val ys = pts.map { it.y }
                var lo = if (yMinZero) 0.0 else ys.minOrNull() ?: 0.0
                var hi = ys.maxOrNull() ?: 1.0
                if (hi <= lo) { hi = lo + 1; if (!yMinZero) lo -= 1 }
                val pad = (hi - lo) * 0.08
                if (!yMinZero) lo -= pad
                hi += pad
                val ticks = ChartMath.ticks(lo, hi)
                val tLo = ticks.first(); val tHi = ticks.last()
                val f: (Double) -> Float = { y -> topPad + ((tHi - y) / (tHi - tLo) * plotH).toFloat() }
                return ticks to f
            }

            val (ticks, py) = scale(primary.flatMap { it.points }.ifEmpty { all })
            for (t in ticks) {
                val y = py(t)
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
                axisLabel(measurer, yLabel(t), leftPad - 4.dp.toPx(), y - 6.sp.toPx(), style, alignRight = true)
            }
            val secondaryScale = if (secondary.isNotEmpty()) scale(secondary.flatMap { it.points }) else null
            secondaryScale?.let { (sTicks, sy) ->
                for (t in sTicks) axisLabel(measurer, secondaryLabel(t), leftPad + plotW + 4.dp.toPx(), sy(t) - 6.sp.toPx(), style)
            }

            // X labels: first, last and up to 3 in between.
            val xTicks = if (xMax - xMin < 1) listOf(xMin) else (0..4).map { xMin + (xMax - xMin) * it / 4 }
            for ((i, t) in xTicks.withIndex()) {
                axisLabel(measurer, xLabel(t), px(t), size.height - bottomPad + 4.dp.toPx(), style,
                    alignRight = i == xTicks.lastIndex && xTicks.size > 1, alignCenter = i != 0 && i != xTicks.lastIndex)
            }

            for (s in series) {
                val toY = if (s.secondaryAxis) secondaryScale!!.second else py
                val pts = s.points.sortedBy { it.x }
                if (pts.size > 1) {
                    val path = Path()
                    pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), toY(p.y)) else path.lineTo(px(p.x), toY(p.y)) }
                    drawPath(path, s.color, style = Stroke(
                        width = 2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                    ))
                }
                if (s.showDots || pts.size == 1) for (p in pts) drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(px(p.x), toY(p.y)))
            }
        }
        if (showLegend) Legend(series.map { it.label to it.color })
    }
}

@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    modifier: Modifier = Modifier,
    references: List<ReferenceLine> = emptyList(),
    yLabel: (Double) -> String = { formatAxis(it) },
    labelEvery: Int = 1,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = labelColor.copy(alpha = 0.2f)
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        if (bars.isEmpty()) return@Canvas
        val leftPad = 36.dp.toPx()
        val rightPad = 8.dp.toPx()
        val topPad = 8.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        val maxY = maxOf(bars.maxOf { it.total }, references.maxOfOrNull { it.y } ?: 0.0, 1.0)
        val ticks = ChartMath.ticks(0.0, maxY * 1.05)
        val top = ticks.last()
        fun py(y: Double) = topPad + ((top - y) / top * plotH).toFloat()
        for (t in ticks) {
            drawLine(gridColor, Offset(leftPad, py(t)), Offset(leftPad + plotW, py(t)), 1f)
            axisLabel(measurer, yLabel(t), leftPad - 4.dp.toPx(), py(t) - 6.sp.toPx(), style, alignRight = true)
        }
        val slot = plotW / bars.size
        val barW = slot * 0.7f
        bars.forEachIndexed { i, bar ->
            val x = leftPad + slot * i + (slot - barW) / 2
            var acc = 0.0
            for ((color, value) in bar.segments) {
                if (value <= 0) continue
                val yTop = py(acc + value)
                val yBottom = py(acc)
                drawRect(color, topLeft = Offset(x, yTop), size = Size(barW, yBottom - yTop))
                acc += value
            }
            if (i % labelEvery == 0) axisLabel(measurer, bar.label, x + barW / 2, size.height - bottomPad + 4.dp.toPx(), style, alignCenter = true)
        }
        for (ref in references) {
            drawLine(ref.color, Offset(leftPad, py(ref.y)), Offset(leftPad + plotW, py(ref.y)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            axisLabel(measurer, ref.label, leftPad + plotW, py(ref.y) - 12.sp.toPx(), style.copy(color = ref.color), alignRight = true)
        }
    }
}

@Composable
fun CalendarHeatmap(
    counts: Map<LocalDate, Int>,
    today: LocalDate,
    weeks: Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val empty = labelColor.copy(alpha = 0.12f)
    val filled = MaterialTheme.colorScheme.primary
    val end = Dates.weekStart(today)
    val start = Dates.run { end.minusDays((weeks - 1) * 7) }
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        val leftPad = 28.dp.toPx()
        val topPad = 14.dp.toPx()
        val cell = minOf((size.width - leftPad) / weeks, (size.height - topPad) / 7)
        val gap = cell * 0.15f
        listOf(0 to "Mon", 2 to "Wed", 4 to "Fri", 6 to "Sun").forEach { (row, name) ->
            axisLabel(measurer, name, 0f, topPad + row * cell + cell / 2 - 6.sp.toPx(), style)
        }
        var lastMonth = -1
        for (w in 0 until weeks) {
            val weekStart = Dates.run { start.plusDays(w * 7) }
            if (weekStart.month.ordinal != lastMonth) {
                lastMonth = weekStart.month.ordinal
                if (w == 0 || weekStart.day <= 7) axisLabel(measurer, Dates.monthShort(weekStart), leftPad + w * cell, 0f, style)
            }
            for (d in 0 until 7) {
                val date = Dates.run { weekStart.plusDays(d) }
                if (date > today) continue
                val n = counts[date] ?: 0
                val color = if (n == 0) empty else filled.copy(alpha = if (n >= 2) 1f else 0.7f)
                drawRect(color, topLeft = Offset(leftPad + w * cell + gap / 2, topPad + d * cell + gap / 2), size = Size(cell - gap, cell - gap))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(10.dp).background(color))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

fun formatAxis(value: Double): String {
    val v = if (abs(value) < 1e-9) 0.0 else value
    return if (v == floor(v)) v.toLong().toString() else {
        val r = (v * 10).toLong() / 10.0
        r.toString()
    }
}
