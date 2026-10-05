package net.osmand.plus.plugins.weather

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Provider daily aggregates use the destination's calendar, not the device's. */
data class DailyForecast(val timezone: String, val days: List<Day>, val fetched: Long, val hasSnowfallData: Boolean = false) {
    data class Day(val date: String, val high: Double?, val low: Double?, val rain: Double?,
                   val showers: Double?, val precipitation: Double?, val gust: Double?, val meanWind: Double?,
                   val snowfall: Double? = null) {
        fun hasSnow(): Boolean? = snowfall?.let { it > 0 }
        fun hasRain(): Boolean? = when {
            (rain ?: 0.0) > 0 || (showers ?: 0.0) > 0 -> true
            precipitation == 0.0 || (rain == 0.0 && showers == 0.0) -> false
            else -> null
        }
    }

    fun upcoming(now: Long): List<Day> {
        val zone = TimeZone.getTimeZone(timezone)
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = now }
        return (0 until 7).map {
            val date = format.format(calendar.time)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            days.firstOrNull { it.date == date } ?: Day(date, null, null, null, null, null, null, null)
        }
    }

    companion object {
        val VARIABLES = listOf("temperature_2m_max", "temperature_2m_min", "rain_sum", "showers_sum",
            "precipitation_sum", "wind_gusts_10m_max", "wind_speed_10m_mean", "snowfall_sum")

        @JvmStatic fun beaufort(speed: Double): Int {
            require(speed.isFinite() && speed >= 0)
            return listOf(.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7).count { speed >= it }
        }

        @JvmStatic fun parse(json: JSONObject, fetched: Long): DailyForecast {
            check(!json.optBoolean("error"))
            val timezone = json.getString("timezone")
            check(timezone in TimeZone.getAvailableIDs())
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            check(dates.length() == 7)
            val units = json.getJSONObject("daily_units")
            val hasSnowfall = daily.has("snowfall_sum")
            val expected = listOf("°C", "°C", "mm", "mm", "mm", "m/s", "m/s", "cm")
            VARIABLES.forEachIndexed { i, name ->
                // Preserve old offline snapshots, but never interpret absent snow data as no snow.
                if (name == "snowfall_sum" && !hasSnowfall) return@forEachIndexed
                check(units.getString(name) == expected[i])
                check(daily.getJSONArray(name).length() == dates.length())
            }
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false; timeZone = TimeZone.getTimeZone(timezone)
            }
            var previous: String? = null
            val days = (0 until dates.length()).map { index ->
                val date = dates.getString(index)
                check(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
                val parsed = checkNotNull(format.parse(date))
                previous?.let {
                    val c = Calendar.getInstance(format.timeZone).apply { time = checkNotNull(format.parse(it)) }
                    c.add(Calendar.DAY_OF_MONTH, 1)
                    check(format.format(c.time) == date)
                }
                check(format.format(parsed) == date); previous = date
                fun value(name: String): Double? {
                    val a = daily.getJSONArray(name)
                    return if (a.isNull(index)) null else a.getDouble(index).takeIf {
                        it.isFinite() && (name.startsWith("temperature") || it >= 0)
                    }
                }
                val high = value(VARIABLES[0]); val low = value(VARIABLES[1])
                check(high == null || low == null || high >= low)
                Day(date, high, low, value(VARIABLES[2]), value(VARIABLES[3]), value(VARIABLES[4]),
                    value(VARIABLES[5]), value(VARIABLES[6]), if (hasSnowfall) value("snowfall_sum") else null)
            }
            return DailyForecast(timezone, days, fetched, hasSnowfall)
        }
    }
}
