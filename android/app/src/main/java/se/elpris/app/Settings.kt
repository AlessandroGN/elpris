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
)

object Settings {
    private val VAT = booleanPreferencesKey("vat")
    private val NOTIFY = booleanPreferencesKey("notify")
    private val CHEAP = intPreferencesKey("cheap")
    private val EXPENSIVE = intPreferencesKey("expensive")
    private val LAST_NOTIFIED = intPreferencesKey("last_notified_epoch_day")

    fun flow(context: Context): Flow<UserSettings> = context.store.data.map {
        val d = UserSettings()
        UserSettings(
            includeVat = it[VAT] ?: d.includeVat,
            notify = it[NOTIFY] ?: d.notify,
            cheapOre = it[CHEAP] ?: d.cheapOre,
            expensiveOre = it[EXPENSIVE] ?: d.expensiveOre,
        )
    }

    suspend fun get(context: Context) = flow(context).first()

    suspend fun update(context: Context, s: UserSettings) {
        context.store.edit {
            it[VAT] = s.includeVat
            it[NOTIFY] = s.notify
            it[CHEAP] = s.cheapOre
            it[EXPENSIVE] = s.expensiveOre
        }
    }

    suspend fun lastNotifiedDay(context: Context): Int = context.store.data.first()[LAST_NOTIFIED] ?: 0

    suspend fun setLastNotifiedDay(context: Context, day: Int) {
        context.store.edit { it[LAST_NOTIFIED] = day }
    }
}

/** Displayed price: optionally with 25 % VAT. */
fun Double.shown(s: UserSettings): Double = if (s.includeVat) this * 1.25 else this
