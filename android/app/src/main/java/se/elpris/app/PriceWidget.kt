package se.elpris.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.roundToInt

class PriceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val forecast = ForecastRepository(context).cached()
        val settings = Settings.get(context)
        provideContent { Content(forecast, settings) }
    }

    @Composable
    private fun Content(f: Forecast?, s: UserSettings) {
        val fg = ColorProvider(Color(0xFFF0EBE3))
        val dim = ColorProvider(Color(0x99F0EBE3))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Color(0xFF111111)).cornerRadius(20.dp)
                .padding(12.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (f == null) {
                Text("Elpris – öppna appen", style = TextStyle(color = fg))
                return@Column
            }
            val today = LocalDate.now()
            val days = f.days.filter { !it.date.isBefore(today) }.take(4)
            Text("Elpris ${f.zone}", style = TextStyle(color = dim, fontSize = 12.sp))
            Spacer(GlanceModifier.height(6.dp))
            Row {
                days.forEachIndexed { i, d ->
                    if (i > 0) Spacer(GlanceModifier.width(14.dp))
                    Column {
                        val label = when (d.date) {
                            today -> "Idag"
                            today.plusDays(1) -> "Imorgon"
                            else -> d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale("sv"))
                        }
                        Text(label, style = TextStyle(color = dim, fontSize = 11.sp))
                        Text(
                            (if (d.known) "" else "~") + d.mean.shown(s).roundToInt(),
                            style = TextStyle(color = fg, fontSize = 22.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }
            ChargingPlanner.best(f, s.chargeHours, s.nightsOnly)?.let { w ->
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    "Ladda: ${se.elpris.app.ui.windowLabel(w).lowercase()} ${se.elpris.app.ui.windowTime(w)} · " +
                        (if (w.known) "" else "~") + "${w.avg.shown(s).roundToInt()} öre",
                    style = TextStyle(color = ColorProvider(Color(0xFF8FD6A2)), fontSize = 13.sp,
                        fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

class PriceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceWidget()
}
