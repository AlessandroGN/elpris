package se.elpris.app

import java.time.LocalDate
import java.time.OffsetDateTime

/** A block of consecutive hours, e.g. 01:00–05:00, with its average price (öre/kWh excl. VAT). */
data class ChargeWindow(
    val start: OffsetDateTime,
    val hours: Int,
    val avg: Double,
    val low: Double,
    val high: Double,
    /** True when every hour in the window has a published (not forecast) price. */
    val known: Boolean,
) {
    val end: OffsetDateTime get() = start.plusHours(hours.toLong())
}

object ChargingPlanner {

    private fun allowed(t: OffsetDateTime, nightsOnly: Boolean): Boolean =
        !nightsOnly || t.hour >= 18 || t.hour < 8

    /** All windows of `length` consecutive hours that start in the future. */
    private fun windows(
        hours: List<HourPrice>, length: Int, nightsOnly: Boolean, now: OffsetDateTime,
    ): List<ChargeWindow> {
        val out = mutableListOf<ChargeWindow>()
        val currentHour = now.withMinute(0).withSecond(0).withNano(0)
        for (i in 0..hours.size - length) {
            val block = hours.subList(i, i + length)
            if (block.first().time.isBefore(currentHour)) continue
            // Hours must be back to back (no gaps in the data).
            if (block.zipWithNext().any { (a, b) -> a.time.plusHours(1) != b.time }) continue
            if (!block.all { allowed(it.time, nightsOnly) }) continue
            out += ChargeWindow(
                start = block.first().time,
                hours = length,
                avg = block.map { it.p50 }.average(),
                low = block.map { it.p10 }.average(),
                high = block.map { it.p90 }.average(),
                known = block.all { it.known },
            )
        }
        return out
    }

    /** Cheapest window in the whole horizon. */
    fun best(f: Forecast, length: Int, nightsOnly: Boolean, now: OffsetDateTime = OffsetDateTime.now()) =
        windows(f.hours, length, nightsOnly, now).minByOrNull { it.avg }

    /**
     * Cheapest window per "charging night": windows starting from noon on a day until
     * noon the next day count for that day, so a 23:00–03:00 slot stays together.
     */
    fun perDay(f: Forecast, length: Int, nightsOnly: Boolean, now: OffsetDateTime = OffsetDateTime.now()):
        Map<LocalDate, ChargeWindow> =
        windows(f.hours, length, nightsOnly, now)
            .groupBy { maxOf(it.start.minusHours(12).toLocalDate(), now.toLocalDate()) }
            .mapValues { (_, w) -> w.minBy { it.avg } }

    /** How much cheaper than the plain average over the same horizon. */
    fun savingVsAverage(f: Forecast, w: ChargeWindow, now: OffsetDateTime = OffsetDateTime.now()): Double {
        val future = f.hours.filter { !it.time.isBefore(now.withMinute(0)) }
        return if (future.isEmpty()) 0.0 else future.map { it.p50 }.average() - w.avg
    }
}
