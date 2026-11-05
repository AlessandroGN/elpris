package se.elpris.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import se.elpris.app.HourPrice
import se.elpris.app.UserSettings
import se.elpris.app.shown
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * 7-day hourly chart. Known prices are a solid line; the forecast is a dashed
 * median with a shaded 10–90 % band.
 */
@Composable
fun PriceChart(
    hours: List<HourPrice>,
    settings: UserSettings,
    modifier: Modifier = Modifier,
    knownColor: Color = Color(0xFFF0EBE3),
    forecastColor: Color = Color(0xFF8FB8DE),
    gridColor: Color = Color(0x33F0EBE3),
    /** Start (inclusive) and end (exclusive) of the hours to shade, e.g. the best charging window. */
    highlight: Pair<OffsetDateTime, OffsetDateTime>? = null,
    highlightColor: Color = Color(0xFF8FD6A2),
) {
    if (hours.isEmpty()) return
    val now = OffsetDateTime.now()
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val left = 36.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val w = size.width - left
        val maxY = max(10.0, ceil(hours.maxOf { it.p90.shown(settings) } / 25.0) * 25.0)
        val n = hours.size
        fun x(i: Int) = left + w * i / (n - 1).coerceAtLeast(1)
        fun y(v: Double) = (bottom - (v / maxY) * bottom).toFloat()

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(150, 240, 235, 227)
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        // Horizontal grid with labels.
        val steps = 4
        for (k in 0..steps) {
            val v = maxY * k / steps
            drawLine(gridColor, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            drawContext.canvas.nativeCanvas.drawText("${v.toInt()}", 0f, y(v) + 4.dp.toPx(), paint)
        }
        // Day separators and weekday labels.
        hours.forEachIndexed { i, h ->
            if (h.time.hour == 0) {
                drawLine(gridColor, Offset(x(i), 0f), Offset(x(i), bottom), 1f)
                val label = h.time.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("sv"))
                drawContext.canvas.nativeCanvas.drawText(label, x(i) + 3.dp.toPx(), size.height - 4.dp.toPx(), paint)
            }
        }
        // Best charging window.
        highlight?.let { (from, to) ->
            val a = hours.indexOfFirst { !it.time.isBefore(from) }
            val b = hours.indexOfLast { it.time.isBefore(to) }
            if (a >= 0 && b >= a) {
                val step = w / (n - 1).coerceAtLeast(1)
                drawRect(
                    highlightColor.copy(alpha = 0.25f),
                    topLeft = Offset(x(a), 0f),
                    size = androidx.compose.ui.geometry.Size(x(b) - x(a) + step, bottom),
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
            drawPath(band, forecastColor.copy(alpha = 0.22f))
        }
        // Lines.
        fun line(range: IntRange, color: Color, dashed: Boolean) {
            if (range.isEmpty()) return
            val path = Path()
            range.forEachIndexed { k, i ->
                val p = y(hours[i].p50.shown(settings))
                if (k == 0) path.moveTo(x(i), p) else path.lineTo(x(i), p)
            }
            drawPath(path, color, style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
            ))
        }
        val lastKnown = if (first < 0) n - 1 else first - 1
        line(0..lastKnown, knownColor, false)
        if (first >= 0) line((first - 1).coerceAtLeast(0) until n, forecastColor, true)

        // "Now" marker.
        val nowIdx = hours.indexOfLast { !it.time.isAfter(now) }
        if (nowIdx >= 0) {
            drawCircle(knownColor, 4.dp.toPx(), Offset(x(nowIdx), y(hours[nowIdx].p50.shown(settings))))
        }
    }
}
