package se.elpris.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class UserSettings(
    val includeVat: Boolean = true,
    val notify: Boolean = true,
    /** Notify when tomorrow's cheapest 3 hours average below this (öre/kWh, as displayed). */
    val cheapOre: Int = 30,
    /** Notify when a forecast day averages above this (öre/kWh, as displayed). */
    val expensiveOre: Int = 150,
    /** How many consecutive hours the cars need to charge. */
    val chargeHours: Int = 4,
    /** Only consider 18:00–08:00 when looking for the best charging window. */
    val nightsOnly: Boolean = true,
    /** How many days ahead the chart shows (1, 2, 3 or 7). */
    val chartDays: Int = 2,
    /** Price below which charging is considered worth it (öre/kWh, as displayed). Drawn as a line. */
    val chargeLimitOre: Int = 100,
)

object Settings {
    private val VAT = booleanPreferencesKey("vat")
    private val NOTIFY = booleanPreferencesKey("notify")
    private val CHEAP = intPreferencesKey("cheap")
    private val EXPENSIVE = intPreferencesKey("expensive")
    private val LAST_NOTIFIED = intPreferencesKey("last_notified_epoch_day")
    private val CHARGE_HOURS = intPreferencesKey("charge_hours")
    private val NIGHTS_ONLY = booleanPreferencesKey("nights_only")
    private val CHART_DAYS = intPreferencesKey("chart_days")
    private val CHARGE_LIMIT = intPreferencesKey("charge_limit")

    fun flow(context: Context): Flow<UserSettings> = context.store.data.map {
        val d = UserSettings()
        UserSettings(
            includeVat = it[VAT] ?: d.includeVat,
            notify = it[NOTIFY] ?: d.notify,
            cheapOre = it[CHEAP] ?: d.cheapOre,
            expensiveOre = it[EXPENSIVE] ?: d.expensiveOre,
            chargeHours = it[CHARGE_HOURS] ?: d.chargeHours,
            nightsOnly = it[NIGHTS_ONLY] ?: d.nightsOnly,
            chartDays = it[CHART_DAYS] ?: d.chartDays,
            chargeLimitOre = it[CHARGE_LIMIT] ?: d.chargeLimitOre,
        )
    }

    suspend fun get(context: Context) = flow(context).first()

    suspend fun update(context: Context, s: UserSettings) {
        context.store.edit {
            it[VAT] = s.includeVat
            it[NOTIFY] = s.notify
            it[CHEAP] = s.cheapOre
            it[EXPENSIVE] = s.expensiveOre
            it[CHARGE_HOURS] = s.chargeHours
            it[NIGHTS_ONLY] = s.nightsOnly
            it[CHART_DAYS] = s.chartDays
            it[CHARGE_LIMIT] = s.chargeLimitOre
        }
    }

    suspend fun lastNotifiedDay(context: Context): Int = context.store.data.first()[LAST_NOTIFIED] ?: 0

    suspend fun setLastNotifiedDay(context: Context, day: Int) {
        context.store.edit { it[LAST_NOTIFIED] = day }
    }
}

/** Displayed price: optionally with 25 % VAT. */
fun Double.shown(s: UserSettings): Double = if (s.includeVat) this * 1.25 else this
