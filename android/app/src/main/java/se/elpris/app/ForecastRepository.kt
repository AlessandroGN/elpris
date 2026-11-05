package se.elpris.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads forecast.json and keeps the last good copy on disk. */
class ForecastRepository(private val context: Context) {

    private val cache = File(context.filesDir, "forecast.json")

    fun cached(): Forecast? = runCatching { Forecast.parse(cache.readText()) }.getOrNull()

    suspend fun refresh(): Result<Forecast> = withContext(Dispatchers.IO) {
        runCatching {
            // Cache-buster: GitHub Pages caches for ~10 minutes.
            val url = URL(BuildConfig.FORECAST_URL + "?t=" + System.currentTimeMillis() / 600_000)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            val text = try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
            val forecast = Forecast.parse(text)
            cache.writeText(text)
            forecast
        }
    }
}
