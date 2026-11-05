package se.elpris.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.elpris.app.DayPrice
import se.elpris.app.Forecast
import se.elpris.app.UserSettings
import se.elpris.app.shown
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

val Bg = Color(0xFF0B0B0B)
val Fg = Color(0xFFF0EBE3)
val Dim = Fg.copy(alpha = 0.55f)
val Card = Color(0xFF161616)
val Blue = Color(0xFF8FB8DE)

private val sv = Locale("sv")

@Composable
fun MainScreen(
    forecast: Forecast?,
    settings: UserSettings,
    status: String?,
    onRefresh: () -> Unit,
    onSettings: (UserSettings) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Elpris ${forecast?.zone ?: "SE3"}", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Uppdatera", color = Dim) }
            TextButton(onClick = { showSettings = !showSettings }) { Text("Inställn.", color = Dim) }
        }
        val unit = if (settings.includeVat) "öre/kWh inkl. moms" else "öre/kWh exkl. moms"
        Text("Spotpris, $unit", color = Dim, fontSize = 13.sp)
        status?.let { Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }

        if (showSettings) {
            SettingsCard(settings, onSettings)
        }

        if (forecast == null) {
            Spacer(Modifier.height(40.dp))
            Text("Ingen prognos hämtad än.", color = Fg)
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        TodayHeader(forecast, settings)
        Spacer(Modifier.height(16.dp))
        PriceChart(forecast.hours, settings)
        Row(Modifier.padding(top = 6.dp)) {
            Legend(Fg, "Känt pris"); Spacer(Modifier.width(16.dp)); Legend(Blue, "Prognos (10–90 %)")
        }

        Spacer(Modifier.height(20.dp))
        forecast.days.forEach { DayRow(it, settings, forecast) }

        forecast.drivers?.let { d ->
            Spacer(Modifier.height(20.dp))
            Text("Vad styr priset", color = Fg, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            forecast.days.filter { !it.date.isBefore(LocalDate.now()) }.forEach { day ->
                val wind = d.windMs[day.date]?.let { "vind %.1f m/s".format(it) } ?: ""
                val temp = d.tempC[day.date]?.let { "%.0f°".format(it) } ?: ""
                val nuc = d.nuclearOutMw[day.date]?.let { if (it > 50) "kärnkraft −${it} MW" else "kärnkraft full" } ?: ""
                Text("${dayLabel(day.date)}: " + listOf(wind, temp, nuc).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            if (!d.hydroFill.isNaN()) {
                val dev = (d.hydroDev * 100).roundToInt()
                Text("Norska magasin: ${(d.hydroFill * 100).roundToInt()} % (${if (dev >= 0) "+" else ""}$dev p.e. mot normalt)",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (forecast.accuracy.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Träffsäkerhet senaste 60 dagarna", color = Fg, fontWeight = FontWeight.Medium)
            forecast.accuracy.filter { it.lead >= 2 }.forEach {
                val mae = it.maeOre.shown(settings).roundToInt()
                Text("${it.lead} dagar fram: ±$mae öre i snitt, ${(it.coverage * 100).roundToInt()} % inom intervallet",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Uppdaterad ${forecast.generatedAt.format(DateTimeFormatter.ofPattern("d MMM HH:mm", sv))}",
            color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun TodayHeader(f: Forecast, s: UserSettings) {
    val now = java.time.OffsetDateTime.now()
    val current = f.hours.lastOrNull { !it.time.isAfter(now) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(current?.p50?.shown(s)?.roundToInt()?.toString() ?: "–", color = Fg, fontSize = 56.sp,
            fontWeight = FontWeight.Medium)
        Text(" öre just nu", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(3.dp).background(color))
        Text(" $label", color = Dim, fontSize = 12.sp)
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Idag"
        today.plusDays(1) -> "Imorgon"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, sv).replaceFirstChar { it.uppercase() } +
            " " + d.dayOfMonth + "/" + d.monthValue
    }
}

@Composable
private fun DayRow(d: DayPrice, s: UserSettings, f: Forecast) {
    if (d.date.isBefore(LocalDate.now())) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Card)
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayLabel(d.date), color = Fg, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text((if (d.known) "" else "~") + d.mean.shown(s).roundToInt(), color = if (d.known) Fg else Blue,
                fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(" öre", color = Dim, fontSize = 13.sp)
        }
        val sub = if (d.known) {
            "${d.min.shown(s).roundToInt()}–${d.max.shown(s).roundToInt()} öre · billigast ${d.cheapestHours.joinToString(", ")}"
        } else {
            "troligen ${d.low.shown(s).roundToInt()}–${d.high.shown(s).roundToInt()} · billigast runt ${d.cheapestHours.joinToString(", ")}"
        }
        Text(sub, color = Dim, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsCard(s: UserSettings, onChange: (UserSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visa inkl. moms", color = Fg, modifier = Modifier.weight(1f))
            Switch(checked = s.includeVat, onCheckedChange = { onChange(s.copy(includeVat = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notiser", color = Fg, modifier = Modifier.weight(1f))
            Switch(checked = s.notify, onCheckedChange = { onChange(s.copy(notify = it)) })
        }
        HorizontalDivider(color = Dim.copy(alpha = 0.2f))
        Text("Billigt-notis under ${s.cheapOre} öre (snitt billigaste 3 h imorgon)", color = Dim, fontSize = 13.sp)
        Slider(value = s.cheapOre.toFloat(), onValueChange = { onChange(s.copy(cheapOre = it.roundToInt())) },
            valueRange = 0f..150f, steps = 29)
        Text("Dyrt-notis över ${s.expensiveOre} öre (dagssnitt i prognos)", color = Dim, fontSize = 13.sp)
        Slider(value = s.expensiveOre.toFloat(), onValueChange = { onChange(s.copy(expensiveOre = it.roundToInt())) },
            valueRange = 50f..400f, steps = 34)
    }
}
