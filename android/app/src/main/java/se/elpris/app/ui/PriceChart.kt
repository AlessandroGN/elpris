package se.elpris.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.elpris.app.HourPrice
import se.elpris.app.UserSettings
import se.elpris.app.shown
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hourly price chart. Known prices: solid line. Forecast: dashed line with a soft band.
 * The best charging window is shaded green. Tap or drag to read a single hour.
 */
@Composable
fun PriceChart(
    hours: List<HourPrice>,
    settings: UserSettings,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    highlight: Pair<OffsetDateTime, OffsetDateTime>? = null,
) {
    if (hours.size < 2) return
    var selected by remember(hours) { mutableStateOf<Int?>(null) }
    val now = OffsetDateTime.now()
    val sv = Locale("sv")
    val spanHours = hours.size
    val showBand = settings.chartDays > 1 || hours.any { !it.known }

    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(hours) {
                val left = ChartLeft.toPx()
                detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), left, hours.size) }
            }
            .pointerInput(hours) {
                val left = ChartLeft.toPx()
                detectHorizontalDragGestures { change, _ ->
                    selected = indexAt(change.position.x, size.width.toFloat(), left, hours.size)
                }
            },
    ) {
        val left = ChartLeft.toPx()
        val top = 34.dp.toPx() // room for the readout bubble
        val bottom = size.height - 22.dp.toPx()
        val w = size.width - left
        val maxVal = hours.maxOf { if (showBand) it.p90.shown(settings) else it.p50.shown(settings) }
        val stepY = if (maxVal > 300) 100.0 else 50.0
        val maxY = max(stepY, ceil(maxVal / stepY) * stepY)
        val n = hours.size
        fun x(i: Int) = left + w * i / (n - 1)
        fun y(v: Double) = (bottom - (v / maxY) * (bottom - top)).toFloat()

        val label = android.graphics.Paint().apply {
            color = Dim.toArgb()
            textSize = 11.dp.toPx()
            isAntiAlias = true
        }

        // Y grid.
        var v = 0.0
        while (v <= maxY + 0.1) {
            drawLine(Grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            drawContext.canvas.nativeCanvas.drawText("${v.toInt()}", 0f, y(v) + 4.dp.toPx(), label)
            v += stepY
        }

        // X labels: clock times for short ranges, weekdays for long ones.
        hours.forEachIndexed { i, h ->
            val t = h.time
            val text = when {
                spanHours <= 30 && t.hour % 3 == 0 -> "%02d".format(t.hour)
                spanHours in 31..80 && t.hour % 6 == 0 ->
                    if (t.hour == 0) t.dayOfWeek.getDisplayName(TextStyle.SHORT, sv) else "%02d".format(t.hour)
                spanHours > 80 && t.hour == 0 -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, sv)
                else -> null
            }
            if (t.hour == 0) drawLine(Grid, Offset(x(i), top), Offset(x(i), bottom), 1f)
            if (text != null) {
                val tw = label.measureText(text)
                drawContext.canvas.nativeCanvas.drawText(text, x(i) - tw / 2, size.height - 4.dp.toPx(), label)
            }
        }

        // Charging limit line (e.g. 1 kr).
        val limit = settings.chargeLimitOre.toDouble()
        if (limit in 1.0..maxY) {
            val ly = y(limit)
            drawLine(Green.copy(alpha = 0.8f), Offset(left, ly), Offset(size.width, ly), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            val text = if (settings.chargeLimitOre % 100 == 0) "${settings.chargeLimitOre / 100} kr"
            else "${settings.chargeLimitOre} öre"
            val p = android.graphics.Paint(label).apply { color = Green.toArgb() }
            val tw = p.measureText(text)
            drawContext.canvas.nativeCanvas.drawText(text, size.width - tw - 2.dp.toPx(), ly - 4.dp.toPx(), p)
        }

        // Best charging window.
        highlight?.let { (from, to) ->
            val a = hours.indexOfFirst { !it.time.isBefore(from) }
            val b = hours.indexOfLast { it.time.isBefore(to) }
            if (a >= 0 && b >= a) {
                drawRoundRect(
                    Green.copy(alpha = 0.22f),
                    topLeft = Offset(x(a), top),
                    size = Size((x(b) - x(a)).coerceAtLeast(w / n), bottom - top),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }

        // Forecast band.
        val first = hours.indexOfFirst { !it.known }
        if (first >= 0) {
            val band = Path()
            for (i in first until n) {
                val p = y(hours[i].p90.shown(settings))
                if (i == first) band.moveTo(x(i), p) else band.lineTo(x(i), p)
            }
            for (i in n - 1 downTo first) band.lineTo(x(i), y(hours[i].p10.shown(settings)))
            band.close()
            drawPath(band, Blue.copy(alpha = 0.16f))
        }

        fun line(range: IntRange, color: Color, dashed: Boolean) {
            if (range.first >= range.last) return
            val path = Path()
            for (i in range) {
                val p = y(hours[i].p50.shown(settings))
                if (i == range.first) path.moveTo(x(i), p) else path.lineTo(x(i), p)
            }
            drawPath(path, color, style = Stroke(
                width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 9f)) else null,
            ))
        }
        val lastKnown = if (first < 0) n - 1 else first - 1
        line(0..lastKnown, Fg, false)
        if (first >= 0) line((first - 1).coerceAtLeast(0) until n, Blue, true)

        // Now marker.
        val nowIdx = hours.indexOfLast { !it.time.isAfter(now) }
        if (nowIdx >= 0) {
            drawLine(Fg.copy(alpha = 0.35f), Offset(x(nowIdx), top), Offset(x(nowIdx), bottom), 1.5f)
            drawCircle(Fg, 4.5.dp.toPx(), Offset(x(nowIdx), y(hours[nowIdx].p50.shown(settings))))
        }

        // Readout for the touched hour.
        selected?.let { i ->
            val h = hours[i]
            val px = x(i)
            val py = y(h.p50.shown(settings))
            drawLine(Fg.copy(alpha = 0.6f), Offset(px, top), Offset(px, bottom), 1.5f)
            drawCircle(if (h.known) Fg else Blue, 5.dp.toPx(), Offset(px, py))
            val text = h.time.dayOfWeek.getDisplayName(TextStyle.SHORT, sv) +
                " %02d:00  ".format(h.time.hour) + (if (h.known) "" else "~") +
                "${h.p50.shown(settings).roundToInt()} öre"
            val bubble = android.graphics.Paint(label).apply {
                color = Fg.toArgb()
                textSize = 13.dp.toPx()
            }
            val tw = bubble.measureText(text)
            val pad = 8.dp.toPx()
            val bx = (px - tw / 2 - pad).coerceIn(left, size.width - tw - 2 * pad)
            drawRoundRect(Card2, Offset(bx, 0f), Size(tw + 2 * pad, 26.dp.toPx()),
                cornerRadius = CornerRadius(13.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(text, bx + pad, 18.dp.toPx(), bubble)
        }
    }
}

private val ChartLeft = 34.dp

private fun indexAt(px: Float, width: Float, left: Float, n: Int): Int {
    val frac = ((px - left) / (width - left)).coerceIn(0f, 1f)
    return (frac * (n - 1)).roundToInt()
}
