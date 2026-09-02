package app.gains.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gains.analysis.Dates
import app.gains.ui.theme.GainsColors
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
    /** Soft gradient under the curve. */
    val fill: Boolean = false,
    /** Smooth (monotone cubic) instead of straight segments. */
    val smooth: Boolean = true,
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

    /** Monotone cubic interpolation: smooth without overshooting the data. */
    fun smoothPath(pts: List<Offset>): Path {
        val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) return path
        val n = pts.size
        val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
        val dy = FloatArray(n - 1) { pts[it + 1].y - pts[it].y }
        val m = FloatArray(n - 1) { if (dx[it] == 0f) 0f else dy[it] / dx[it] }
        val t = FloatArray(n)
        t[0] = m[0]; t[n - 1] = m[n - 2]
        for (i in 1 until n - 1) t[i] = if (m[i - 1] * m[i] <= 0f) 0f else (m[i - 1] + m[i]) / 2f
        for (i in 0 until n - 1) {
            if (m[i] == 0f) { t[i] = 0f; t[i + 1] = 0f; continue }
            val a = t[i] / m[i]; val b = t[i + 1] / m[i]
            val s = a * a + b * b
            if (s > 9f) { val tau = 3f / kotlin.math.sqrt(s); t[i] = tau * a * m[i]; t[i + 1] = tau * b * m[i] }
        }
        for (i in 0 until n - 1) {
            val h = dx[i]
            path.cubicTo(pts[i].x + h / 3f, pts[i].y + t[i] * h / 3f, pts[i + 1].x - h / 3f, pts[i + 1].y - t[i + 1] * h / 3f, pts[i + 1].x, pts[i + 1].y)
        }
        return path
    }
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

/** Draw-in progress that restarts whenever the data changes. */
@Composable
private fun rememberDrawProgress(key: Any?): Float {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { anim.animateTo(1f, tween(700)) }
    return anim.value
}

@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    xLabel: (Double) -> String = { Dates.short(ChartMath.fromX(it)) },
    yLabel: (Double) -> String = { formatAxis(it) },
    secondaryLabel: (Double) -> String = { formatAxis(it) },
    yMinZero: Boolean = false,
    showLegend: Boolean = series.size > 1,
) {
    val measurer = rememberTextMeasurer()
    val palette = GainsColors.palette
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = palette.gridLine
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val primary = series.filter { !it.secondaryAxis }
    val secondary = series.filter { it.secondaryAxis }
    val progress = rememberDrawProgress(series.map { it.points })
    val markerCore = MaterialTheme.colorScheme.background

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = series.flatMap { it.points }
            if (all.isEmpty()) return@Canvas
            val leftPad = 40.dp.toPx()
            val rightPad = if (secondary.isNotEmpty()) 40.dp.toPx() else 10.dp.toPx()
            val topPad = 10.dp.toPx()
            val bottomPad = 22.dp.toPx()
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
                val pad = (hi - lo) * 0.1
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
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                axisLabel(measurer, yLabel(t), leftPad - 6.dp.toPx(), y - 6.sp.toPx(), style, alignRight = true)
            }
            val secondaryScale = if (secondary.isNotEmpty()) scale(secondary.flatMap { it.points }) else null
            secondaryScale?.let { (sTicks, sy) ->
                for (t in sTicks) axisLabel(measurer, secondaryLabel(t), leftPad + plotW + 6.dp.toPx(), sy(t) - 6.sp.toPx(), style.copy(color = secondary.first().color))
            }

            val xTicks = if (xMax - xMin < 1) listOf(xMin) else (0..4).map { xMin + (xMax - xMin) * it / 4 }
            for ((i, t) in xTicks.withIndex()) {
                axisLabel(measurer, xLabel(t), px(t), size.height - bottomPad + 6.dp.toPx(), style,
                    alignRight = i == xTicks.lastIndex && xTicks.size > 1, alignCenter = i != 0 && i != xTicks.lastIndex)
            }

            val revealRight = leftPad + plotW * progress
            clipRect(right = revealRight + 1f) {
                for (s in series) {
                    val toY = if (s.secondaryAxis) secondaryScale!!.second else py
                    val pts = s.points.sortedBy { it.x }.map { Offset(px(it.x), toY(it.y)) }
                    if (pts.size > 1) {
                        val path = if (s.smooth) ChartMath.smoothPath(pts) else Path().apply {
                            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                        }
                        if (s.fill) {
                            val area = Path().apply {
                                addPath(path)
                                lineTo(pts.last().x, topPad + plotH)
                                lineTo(pts.first().x, topPad + plotH)
                                close()
                            }
                            drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.0f)), startY = topPad, endY = topPad + plotH))
                        }
                        drawPath(path, s.color, style = Stroke(
                            width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 7f)) else null,
                        ))
                    }
                    if (s.showDots || pts.size == 1) for (p in pts) drawCircle(s.color, radius = 2.5.dp.toPx(), center = p)
                }
            }
            // Glowing end marker on the first primary series once the draw-in completes.
            if (progress >= 1f) {
                val lead = primary.firstOrNull() ?: series.first()
                val toY = if (lead.secondaryAxis) secondaryScale!!.second else py
                lead.points.maxByOrNull { it.x }?.let { last ->
                    val c = Offset(px(last.x), toY(last.y))
                    drawCircle(lead.color.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = c)
                    drawCircle(lead.color, radius = 4.dp.toPx(), center = c)
                    drawCircle(markerCore, radius = 1.8.dp.toPx(), center = c)
                }
            }
        }
        if (showLegend) Legend(series.map { it.label to it.color })
    }
}

@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    references: List<ReferenceLine> = emptyList(),
    yLabel: (Double) -> String = { formatAxis(it) },
    labelEvery: Int = 1,
    highlightLast: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val palette = GainsColors.palette
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = palette.gridLine
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val progress = rememberDrawProgress(bars)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (bars.isEmpty()) return@Canvas
        val leftPad = 34.dp.toPx()
        val rightPad = 6.dp.toPx()
        val topPad = 10.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        val maxY = maxOf(bars.maxOf { it.total }, references.maxOfOrNull { it.y } ?: 0.0, 1.0)
        val ticks = ChartMath.ticks(0.0, maxY * 1.05)
        val top = ticks.last()
        fun py(y: Double) = topPad + ((top - y) / top * plotH).toFloat()
        for (t in ticks) {
            drawLine(gridColor, Offset(leftPad, py(t)), Offset(leftPad + plotW, py(t)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            axisLabel(measurer, yLabel(t), leftPad - 6.dp.toPx(), py(t) - 6.sp.toPx(), style, alignRight = true)
        }
        val slot = plotW / bars.size
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(barW / 2.5f, barW / 2.5f)
        bars.forEachIndexed { i, bar ->
            val x = leftPad + slot * i + (slot - barW) / 2
            val dim = highlightLast && i != bars.lastIndex
            var acc = 0.0
            val bottom = py(0.0)
            val segments = bar.segments.filter { it.second > 0 }
            segments.forEachIndexed { si, (color, value) ->
                val yTop = bottom - (bottom - py(acc + value)) * progress
                val yBottom = bottom - (bottom - py(acc)) * progress
                val c = if (dim) color.copy(alpha = 0.65f) else color
                if (si == segments.lastIndex) {
                    val path = Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(rect = androidx.compose.ui.geometry.Rect(x, yTop, x + barW, yBottom + radius.y), topLeft = radius, topRight = radius, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero))
                    }
                    clipRect(top = yTop, bottom = yBottom) { drawPath(path, c) }
                } else {
                    drawRect(c, topLeft = Offset(x, yTop), size = Size(barW, (yBottom - yTop).coerceAtLeast(0f)))
                }
                acc += value
            }
            val nearLast = i != bars.lastIndex && bars.lastIndex - i < maxOf(2, labelEvery)
            if ((i % labelEvery == 0 && !nearLast) || i == bars.lastIndex) {
                axisLabel(measurer, bar.label, x + barW / 2, size.height - bottomPad + 6.dp.toPx(), if (i == bars.lastIndex) style.copy(fontWeight = FontWeight.Bold) else style, alignCenter = true)
            }
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
    val palette = GainsColors.palette
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val filled = palette.volt
    val end = Dates.weekStart(today)
    val start = Dates.run { end.minusDays((weeks - 1) * 7) }
    Canvas(modifier.fillMaxWidth().height(126.dp)) {
        val leftPad = 30.dp.toPx()
        val topPad = 16.dp.toPx()
        val cell = minOf((size.width - leftPad) / weeks, (size.height - topPad) / 7)
        val gap = cell * 0.22f
        val r = CornerRadius(cell * 0.25f, cell * 0.25f)
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
                val color = when {
                    n == 0 -> empty
                    n >= 2 -> filled
                    else -> filled.copy(alpha = 0.72f)
                }
                drawRoundRect(color, topLeft = Offset(leftPad + w * cell + gap / 2, topPad + d * cell + gap / 2), size = Size(cell - gap, cell - gap), cornerRadius = r)
            }
        }
    }
}

/** Tiny inline trend line for list rows. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.width(64.dp).height(24.dp)) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max()
        val span = if (hi > lo) hi - lo else 1.0
        val pts = values.mapIndexed { i, v ->
            Offset(i * size.width / (values.size - 1), size.height - ((v - lo) / span * (size.height - 4f)).toFloat() - 2f)
        }
        val path = ChartMath.smoothPath(pts)
        val area = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 2.2.dp.toPx(), center = pts.last())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
