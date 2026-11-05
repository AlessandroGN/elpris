package se.elpris.app

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/** Prices are öre/kWh excluding VAT, as published by the backend. */
data class HourPrice(
    val time: OffsetDateTime,
    val p50: Double,
    val p10: Double,
    val p90: Double,
    val known: Boolean,
)

data class DayPrice(
    val date: LocalDate,
    val known: Boolean,
    val lead: Int,
    val mean: Double,
    val low: Double,
    val high: Double,
    val min: Double,
    val max: Double,
    val cheapestHours: List<String>,
)

data class LeadAccuracy(val lead: Int, val maeOre: Double, val coverage: Double)

data class Drivers(
    val nuclearOutMw: Map<LocalDate, Int>,
    val windMs: Map<LocalDate, Double>,
    val tempC: Map<LocalDate, Double>,
    val hydroFill: Double,
    val hydroDev: Double,
)

data class Forecast(
    val generatedAt: OffsetDateTime,
    val zone: String,
    val days: List<DayPrice>,
    val hours: List<HourPrice>,
    val drivers: Drivers?,
    val accuracy: List<LeadAccuracy>,
) {
    companion object {
        fun parse(text: String): Forecast {
            val o = JSONObject(text)
            val days = o.getJSONArray("days").let { a ->
                (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    DayPrice(
                        date = LocalDate.parse(d.getString("date")),
                        known = d.getBoolean("known"),
                        lead = d.getInt("lead"),
                        mean = d.getDouble("mean"),
                        low = d.getDouble("low"),
                        high = d.getDouble("high"),
                        min = d.getDouble("min"),
                        max = d.getDouble("max"),
                        cheapestHours = d.getJSONArray("cheapest_hours").let { h ->
                            (0 until h.length()).map { h.getString(it) }
                        },
                    )
                }
            }
            val hours = o.getJSONArray("hours").let { a ->
                (0 until a.length()).map { i ->
                    val h = a.getJSONObject(i)
                    HourPrice(
                        time = OffsetDateTime.parse(h.getString("t")),
                        p50 = h.getDouble("p50"),
                        p10 = h.getDouble("p10"),
                        p90 = h.getDouble("p90"),
                        known = h.getBoolean("known"),
                    )
                }
            }
            val drivers = o.optJSONObject("drivers")?.let { d ->
                fun <T> dateMap(key: String, f: (JSONObject, String) -> T): Map<LocalDate, T> =
                    d.optJSONObject(key)?.let { m ->
                        m.keys().asSequence().associate { LocalDate.parse(it) to f(m, it) }
                    } ?: emptyMap()
                Drivers(
                    nuclearOutMw = dateMap("nuclear_out_se_mw") { m, k -> m.getInt(k) },
                    windMs = dateMap("wind_nordic_ms") { m, k -> m.getDouble(k) },
                    tempC = dateMap("temp_nordic_c") { m, k -> m.getDouble(k) },
                    hydroFill = d.optDouble("hydro_fill_no"),
                    hydroDev = d.optDouble("hydro_dev_no"),
                )
            }
            val accuracy = o.optJSONObject("accuracy")?.optJSONArray("per_lead")?.let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    LeadAccuracy(x.getInt("lead"), x.getDouble("mae_ore"), x.getDouble("coverage"))
                }
            } ?: emptyList()
            return Forecast(
                generatedAt = OffsetDateTime.parse(o.getString("generated_at")),
                zone = o.getString("zone"),
                days = days,
                hours = hours,
                drivers = drivers,
                accuracy = accuracy,
            )
        }
    }
}
