package se.elpris.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Every 3 hours: download the forecast, refresh the widget, maybe notify. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val forecast = ForecastRepository(applicationContext).refresh().getOrElse { return Result.retry() }
        PriceWidget().updateAll(applicationContext)
        maybeNotify(applicationContext, forecast)
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

private const val CHANNEL = "price_alerts"

/** At most one notification per day, sent once tomorrow's prices are known. */
suspend fun maybeNotify(context: Context, f: Forecast) {
    val s = Settings.get(context)
    if (!s.notify) return
    val today = LocalDate.now()
    val tomorrow = f.days.firstOrNull { it.date == today.plusDays(1) && it.known } ?: return
    if (Settings.lastNotifiedDay(context) >= today.toEpochDay().toInt()) return

    if (tomorrow.date != today.plusDays(1)) return

    // Daily charging tip: best window among published prices, plus a heads-up if a
    // later (forecast) night looks clearly cheaper.
    val known = ChargingPlanner.best(f.copy(hours = f.hours.filter { it.known }), s.chargeHours, s.nightsOnly)
        ?: return
    val overall = ChargingPlanner.best(f, s.chargeHours, s.nightsOnly)
    val title = "Ladda ${se.elpris.app.ui.windowLabel(known).lowercase()} ${se.elpris.app.ui.windowTime(known)} · " +
        "${known.avg.shown(s).roundToInt()} öre"

    val lines = mutableListOf<String>()
    if (overall != null && !overall.known && known.avg - overall.avg > 15 / (if (s.includeVat) 1.25 else 1.0)) {
        lines += "Ännu billigare väntas: ${se.elpris.app.ui.windowLabel(overall).lowercase()} " +
            "${se.elpris.app.ui.windowTime(overall)} (~${overall.avg.shown(s).roundToInt()} öre)"
    }
    val expensive = f.days.filter { !it.known && it.mean.shown(s) > s.expensiveOre }
    if (expensive.isNotEmpty()) {
        val names = expensive.joinToString(", ") {
            it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("sv"))
        }
        lines += "Dyrt väntas: $names (~${expensive.maxOf { it.mean.shown(s) }.roundToInt()} öre)"
    }
    send(context, title, lines.joinToString("\n").ifEmpty { "Billigaste ${s.chargeHours} timmarna i sträck" })
    Settings.setLastNotifiedDay(context, today.toEpochDay().toInt())
}

fun send(context: Context, title: String, text: String) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) return
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Elpris", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_bolt)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(1, n)
}
