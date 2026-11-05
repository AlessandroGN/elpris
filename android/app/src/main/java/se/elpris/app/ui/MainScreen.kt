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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import se.elpris.app.ChargeWindow
import se.elpris.app.ChargingPlanner
import se.elpris.app.Forecast
import se.elpris.app.UserSettings
import se.elpris.app.shown
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

val Bg = Color(0xFF0B0B0B)
val Fg = Color(0xFFF0EBE3)
val Dim = Fg.copy(alpha = 0.5f)
val Grid = Fg.copy(alpha = 0.08f)
val Card = Color(0xFF151515)
val Card2 = Color(0xFF2A2A2A)
val Blue = Color(0xFF8FB8DE)
val Green = Color(0xFF8FD6A2)

private val sv = Locale("sv")
private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val RANGES = listOf(1 to "24 h", 2 to "2 dygn", 3 to "3 dygn", 7 to "7 dygn")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    forecast: Forecast?,
    settings: UserSettings,
    status: String?,
    onRefresh: () -> Unit,
    onSettings: (UserSettings) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Elpris", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("${forecast?.zone ?: "SE3"} · öre/kWh ${if (settings.includeVat) "inkl." else "exkl."} moms",
                    color = Dim, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Uppdatera", tint = Dim) }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Inställningar", tint = Dim) }
        }
        status?.let { Text(it, color = Dim, fontSize = 12.sp) }

        if (forecast == null) {
            Spacer(Modifier.height(60.dp))
            Text("Ingen prognos hämtad än.", color = Fg)
        } else {
            val now = OffsetDateTime.now()
            val fromHour = now.withMinute(0).withSecond(0).withNano(0).minusHours(2)
            val toHour = fromHour.plusHours(2L + 24L * settings.chartDays)
            val visible = forecast.hours.filter { !it.time.isBefore(fromHour) && it.time.isBefore(toHour) }
            val best = ChargingPlanner.best(forecast.copy(hours = visible), settings.chargeHours, settings.nightsOnly)

            Spacer(Modifier.height(16.dp))
            val current = forecast.hours.lastOrNull { !it.time.isAfter(now) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(current?.p50?.shown(settings)?.roundToInt()?.toString() ?: "–", color = Fg,
                    fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Text(" öre just nu", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            Spacer(Modifier.height(12.dp))
            RangePicker(settings.chartDays) { onSettings(settings.copy(chartDays = it)) }
            Spacer(Modifier.height(8.dp))
            PriceChart(visible, settings, highlight = best?.let { it.start to it.end },
                height = if (settings.chartDays == 7) 280.dp else 320.dp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Fg, "Känt")
                Legend(Blue, "Prognos")
                Legend(Green, "Ladda")
            }

            Spacer(Modifier.height(16.dp))
            BestWindow(best, settings, forecast)

            Spacer(Modifier.height(22.dp))
            Nights(forecast, settings)

            Spacer(Modifier.height(18.dp))
            Text(if (showDetails) "Dölj detaljer" else "Visa detaljer", color = Dim, fontSize = 13.sp,
                modifier = Modifier.clickable { showDetails = !showDetails }.padding(vertical = 6.dp))
            if (showDetails) Details(forecast, settings)
            Spacer(Modifier.height(8.dp))
            Text("Uppdaterad ${forecast.generatedAt.format(DateTimeFormatter.ofPattern("d MMM HH:mm", sv))}",
                color = Dim.copy(alpha = 0.35f), fontSize = 11.sp)
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Card) {
            SettingsSheet(settings, onSettings)
        }
    }
}

@Composable
private fun RangePicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Card).padding(3.dp)) {
        RANGES.forEach { (days, label) ->
            val on = days == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) Card2 else Color.Transparent)
                    .clickable { onSelect(days) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Fg else Dim, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(" $label", color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun BestWindow(best: ChargeWindow?, s: UserSettings, f: Forecast) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Green.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Bäst att ladda ${s.chargeHours} h", color = Green, fontSize = 12.sp)
            Text(best?.let { windowLabel(it) + " " + windowTime(it) } ?: "–", color = Fg, fontSize = 20.sp,
                fontWeight = FontWeight.Medium)
            best?.let {
                val saving = ChargingPlanner.savingVsAverage(f, it).shown(s).roundToInt()
                if (saving > 0) Text("$saving öre under snittet", color = Dim, fontSize = 12.sp)
            }
        }
        best?.let {
            Text((if (it.known) "" else "~") + it.avg.shown(s).roundToInt(), color = if (it.known) Fg else Blue,
                fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Text(" öre", color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Nights(f: Forecast, s: UserSettings) {
    val perDay = ChargingPlanner.perDay(f, s.chargeHours, s.nightsOnly).toSortedMap()
    if (perDay.isEmpty()) return
    Text("Bästa laddtid per natt", color = Dim, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    val today = LocalDate.now()
    perDay.forEach { (day, w) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = when (day) {
                today -> "Ikväll"
                today.plusDays(1) -> "Imorgon"
                else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, sv).replaceFirstChar { it.uppercase() }
            }
            Text(name, color = Fg, fontSize = 15.sp, modifier = Modifier.width(96.dp))
            Text(shortTime(w), color = Green, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text((if (w.known) "" else "~") + w.avg.shown(s).roundToInt(), color = if (w.known) Fg else Blue,
                fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(" öre", color = Dim, fontSize = 12.sp)
        }
        HorizontalDivider(color = Grid)
    }
}

@Composable
private fun Details(f: Forecast, s: UserSettings) {
    val small = 12.sp
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        f.drivers?.let { d ->
            f.days.filter { !it.date.isBefore(LocalDate.now()) }.forEach { day ->
                val parts = listOfNotNull(
                    "dygn ${(if (day.known) "" else "~")}${day.mean.shown(s).roundToInt()} öre",
                    d.windMs[day.date]?.let { "vind %.0f m/s".format(it) },
                    d.tempC[day.date]?.let { "%.0f°".format(it) },
                    d.nuclearOutMw[day.date]?.takeIf { it > 50 }?.let { "kärnkraft −${(it / 100) / 10.0} GW" },
                )
                Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, sv) + ": " + parts.joinToString(" · "),
                    color = Dim, fontSize = small)
            }
            if (!d.hydroFill.isNaN()) {
                val dev = (d.hydroDev * 100).roundToInt()
                Text("Norska magasin ${(d.hydroFill * 100).roundToInt()} % (${if (dev >= 0) "+" else ""}$dev mot normalt)",
                    color = Dim, fontSize = small)
            }
        }
        f.accuracy.filter { it.lead >= 2 }.takeIf { it.isNotEmpty() }?.let { acc ->
            Text("Träffsäkerhet: " + acc.joinToString(" · ") { "${it.lead} d ±${it.maeOre.shown(s).roundToInt()}" },
                color = Dim, fontSize = small)
        }
    }
}

@Composable
private fun SettingsSheet(s: UserSettings, onChange: (UserSettings) -> Unit) {
    val sliderColors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
    val switchColors = SwitchDefaults.colors(checkedTrackColor = Green)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Inställningar", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("Laddtid: ${s.chargeHours} h i sträck", color = Fg)
        Slider(s.chargeHours.toFloat(), { onChange(s.copy(chargeHours = it.roundToInt())) },
            valueRange = 1f..12f, steps = 10, colors = sliderColors)
        SwitchRow("Bara kväll/natt (18–08)", s.nightsOnly, switchColors) { onChange(s.copy(nightsOnly = it)) }
        SwitchRow("Visa inkl. moms", s.includeVat, switchColors) { onChange(s.copy(includeVat = it)) }
        SwitchRow("Daglig laddnotis", s.notify, switchColors) { onChange(s.copy(notify = it)) }
        if (s.notify) {
            Text("Varna för dyr dag över ${s.expensiveOre} öre", color = Dim, fontSize = 13.sp)
            Slider(s.expensiveOre.toFloat(), { onChange(s.copy(expensiveOre = it.roundToInt())) },
                valueRange = 50f..400f, steps = 34, colors = sliderColors)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, colors: androidx.compose.material3.SwitchColors,
                      onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fg, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = colors)
    }
}

/** "Ikväll", "I natt", "Natt mot lördag" etc. for the start of a charging window. */
fun windowLabel(w: ChargeWindow): String {
    val today = LocalDate.now()
    val d = w.start.toLocalDate()
    return when {
        d == today && w.start.hour >= 18 -> "Ikväll"
        d == today -> "Idag"
        d == today.plusDays(1) && w.start.hour < 8 -> "I natt"
        d == today.plusDays(1) -> "Imorgon"
        w.start.hour < 8 -> "Natt mot " + d.dayOfWeek.getDisplayName(TextStyle.FULL, sv)
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, sv).replaceFirstChar { it.uppercase() }
    }
}

fun windowTime(w: ChargeWindow) = "${w.start.format(hm)}–${w.end.format(hm)}"

private fun shortTime(w: ChargeWindow) = "%02d–%02d".format(w.start.hour, w.end.hour)
