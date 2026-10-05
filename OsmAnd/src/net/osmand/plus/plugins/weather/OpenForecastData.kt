package net.osmand.plus.plugins.weather

import android.os.Handler
import android.os.Looper
import net.osmand.data.LatLon
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.weather.enums.WeatherSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A bounded, application-owned client. All I/O and cache mutation belong to its worker. */
class OpenForecastData(private val app: OsmandApplication) {
    data class Forecast(val hours: LongArray, val values: Map<String, DoubleArray>, val fetched: Long) {
        fun value(variable: String, time: Long): Double? {
            val match = hours.binarySearch(time)
            val index = if (match >= 0) match else -match - 2
            val start = hours.getOrNull(index) ?: return null
            if (time - start !in 0 until 3600000L) return null
            return values[variable]?.getOrNull(index)?.takeIf { it.isFinite() }
        }
    }
    fun interface Callback { fun complete(values: List<Forecast>?, error: String?) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12))
    private val memory = object : LinkedHashMap<String, Forecast>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Forecast>?) = size > 256
    }
    private val cache = File(app.cacheDir, "open-weather-v1")
    private val budget = app.getSharedPreferences("open_weather_budget", 0)
    private var retryAfter = 0L

    fun request(source: WeatherSource, points: List<LatLon>, callback: Callback) = requestInternal(source, points, false, callback)

    fun requestComparison(source: WeatherSource, point: LatLon, callback: Callback) = requestInternal(source, listOf(point), true, callback)

    private fun requestInternal(source: WeatherSource, points: List<LatLon>, comparison: Boolean, callback: Callback) {
        if (points.isEmpty() || points.size > 49) {
            main.post { callback.complete(null, app.getString(net.osmand.plus.R.string.open_weather_error)) }; return
        }
        try {
            worker.execute {
                try {
                    val result = load(source, points, comparison)
                    main.post { callback.complete(result, null) }
                } catch (e: Exception) {
                    net.osmand.PlatformUtil.getLog(OpenForecastData::class.java).warn("Open forecast request failed for $source", e)
                    main.post { callback.complete(null, app.getString(net.osmand.plus.R.string.open_weather_error)) }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            main.post { callback.complete(null, app.getString(net.osmand.plus.R.string.open_weather_busy)) }
        }
    }

    private fun key(source: WeatherSource, p: LatLon) = String.format(Locale.US, "%s_%.4f_%.4f", source.settingValue, p.latitude, p.longitude)
    private fun load(source: WeatherSource, points: List<LatLon>, comparison: Boolean): List<Forecast> {
        val zone = if (comparison) java.util.TimeZone.getDefault().id else "GMT"
        val encodedZone = java.net.URLEncoder.encode(zone, "UTF-8")
        fun cacheKey(point: LatLon) = (if (comparison) "compare7_${encodedZone}_" else "") + key(source, point)
        val now = System.currentTimeMillis()
        cache.mkdirs()
        val found = HashMap<String, Forecast>()
        val missing = points.distinctBy { cacheKey(it) }.filter { p ->
            val k = cacheKey(p)
            var f = memory[k]
            if (f == null) {
                val file = File(cache, "$k.json")
                if (file.exists() && now - file.lastModified() in 0..TTL) {
                    f = try { parse(JSONObject(file.readText()), file.lastModified()) } catch (_: Exception) { null }
                }
            }
            if (f != null && now - f.fetched in 0..TTL) { found[k] = f; memory[k] = f; false } else true
        }
        if (missing.isNotEmpty()) {
            reserve(now, missing.size * if (comparison) 4 else 1)
            val lat = missing.joinToString(",") { String.format(Locale.US, "%.4f", it.latitude) }
            val lon = missing.joinToString(",") { String.format(Locale.US, "%.4f", it.longitude) }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&models=${model(source)}" +
                "&hourly=${(VARIABLES + if (comparison) COMPARISON_UNITS.keys else emptySet()).joinToString(",")}&forecast_days=7&timeformat=unixtime&timezone=$encodedZone&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            try {
                val status = conn.responseCode
                if (status == 429) retryAfter = now + 60000 * 5
                check(status == 200)
                val text = conn.inputStream.bufferedReader().use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(8192)
                    while (true) { val n = reader.read(buffer); if (n < 0) break; result.append(buffer, 0, n); check(result.length <= 6000000) }
                    result.toString()
                }
                val array = if (missing.size == 1) JSONArray().put(JSONObject(text)) else JSONArray(text)
                check(array.length() == missing.size)
                val loaded = missing.mapIndexed { i, p -> Triple(cacheKey(p), array.getJSONObject(i), parse(array.getJSONObject(i), now)) }
                for ((k, json, value) in loaded) {
                    found[k] = value; memory[k] = value
                    val target = File(cache, "$k.json"); val temp = File(cache, "$k.tmp")
                    temp.writeText(json.toString()); if (!temp.renameTo(target)) temp.delete()
                }
                // Bound storage independently of the memory cache.
                cache.listFiles()?.sortedByDescending { it.lastModified() }?.drop(256)?.forEach { it.delete() }
            } finally { conn.disconnect() }
        }
        return points.map { found.getValue(cacheKey(it)) }
    }

    // The hourly map client and daily point client share one executor and API budget.
    private fun reserve(now: Long, count: Int) {
            check(now >= retryAfter)
            val day = now / 86400000
            val minute = now / 60000
            val used = if (budget.getLong("day", -1) == day) budget.getInt("used", 0) else 0
            val recent = if (budget.getLong("minute", -1) == minute) budget.getInt("recent", 0) else 0
            // Count each coordinate conservatively, including retries and failures.
            check(used + count <= 3000 && recent + count <= 150)
            check(budget.edit().putLong("day", day).putInt("used", used + count)
                .putLong("minute", minute).putInt("recent", recent + count).commit())
    }

    fun interface DailyCallback { fun complete(value: DailyForecast?, cached: Boolean, error: String?) }

    fun requestDaily(source: WeatherSource, point: LatLon, force: Boolean, callback: DailyCallback) {
        fun error() = app.getString(net.osmand.plus.R.string.forecast_daily_error)
        try {
            worker.execute {
                var snapshot: DailyForecast? = null
                try {
                    require(point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                        point.longitude.isFinite() && point.longitude in -180.0..180.0)
                    val now = System.currentTimeMillis()
                    val folder = File(app.cacheDir, "daily-weather-v1").apply { mkdirs() }
                    val file = File(folder, key(source, point) + ".json")
                    if (file.exists() && now - file.lastModified() in 0..7 * 86400000L) {
                        snapshot = try { DailyForecast.parse(JSONObject(file.readText()), file.lastModified()) } catch (_: Exception) { null }
                    }
                    val saved = snapshot
                    if (!force && saved != null && saved.hasSnowfallData && now - saved.fetched in 0..TTL &&
                        saved.upcoming(now).first().date == saved.days.first().date) {
                        main.post { callback.complete(saved, true, null) }
                    } else {
                        reserve(now, 1)
                        val coordinates = String.format(Locale.US, "latitude=%.5f&longitude=%.5f", point.latitude, point.longitude)
                        val url = "https://api.open-meteo.com/v1/forecast?$coordinates&models=${model(source)}" +
                            "&daily=${DailyForecast.VARIABLES.joinToString(",")}&forecast_days=7&timezone=auto" +
                            "&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm"
                        val connection = URL(url).openConnection() as HttpURLConnection
                        connection.connectTimeout = 15000; connection.readTimeout = 20000
                        try {
                            val status = connection.responseCode
                            if (status == 429) retryAfter = now + 300000
                            check(status == 200)
                            val body = connection.inputStream.bufferedReader().use { reader ->
                                val text = StringBuilder(); val buffer = CharArray(4096)
                                while (true) {
                                    val n = reader.read(buffer); if (n < 0) break
                                    text.append(buffer, 0, n); check(text.length <= 100000)
                                }
                                text.toString()
                            }
                            val forecast = DailyForecast.parse(JSONObject(body), now)
                            val temp = File(folder, file.name + ".tmp")
                            try {
                                temp.writeText(body); check(temp.renameTo(file)); file.setLastModified(now)
                            } finally { temp.delete() }
                            folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
                            main.post { callback.complete(forecast, false, null) }
                        } finally { connection.disconnect() }
                    }
                } catch (e: Exception) {
                    net.osmand.PlatformUtil.getLog(OpenForecastData::class.java).warn("Daily forecast request failed for $source", e)
                    val saved = snapshot
                    main.post { callback.complete(saved, saved != null, error()) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            main.post { callback.complete(null, false, app.getString(net.osmand.plus.R.string.open_weather_busy)) }
        }
    }

    companion object {
        const val TTL = 3600000L
        @JvmStatic fun model(source: WeatherSource): String = when (source) {
            WeatherSource.GFS -> "gfs_global"
            WeatherSource.ECMWF -> "ecmwf_ifs025"
            else -> source.settingValue
        }
        val VARIABLES = listOf("temperature_2m", "pressure_msl", "wind_speed_10m", "cloud_cover", "precipitation", "wind_direction_10m")
        val COMPARISON_UNITS = linkedMapOf("wind_gusts_10m" to "m/s", "snowfall" to "cm", "weather_code" to "wmo code",
            "is_day" to "", "cloud_cover_low" to "%", "cloud_cover_mid" to "%", "cloud_cover_high" to "%")
        @JvmStatic fun variable(band: Short) = when (band.toInt()) {
            0, 4 -> "wind_speed_10m"; 1 -> "cloud_cover"; 2 -> "temperature_2m"; 3 -> "pressure_msl"; else -> "precipitation"
        }
        fun parse(json: JSONObject, fetched: Long): Forecast {
            check(!json.optBoolean("error"))
            val hourly = json.getJSONObject("hourly"); val times = hourly.getJSONArray("time")
            val units = json.getJSONObject("hourly_units")
            val expected = listOf("°C", "hPa", "m/s", "%", "mm", "°")
            VARIABLES.forEachIndexed { i, name -> check(units.getString(name) == expected[i]) }
            check(times.length() in 1..384)
            val hours = LongArray(times.length()) { times.getLong(it) * 1000 }
            check(hours.indices.drop(1).all { hours[it] - hours[it - 1] == 3600000L })
            val extra = COMPARISON_UNITS.filterKeys { hourly.has(it) }
            extra.forEach { (name, unit) -> check(units.getString(name) == unit) }
            val values = (VARIABLES + extra.keys).associateWith { name ->
                val array = hourly.getJSONArray(name); check(array.length() == hours.size)
                DoubleArray(hours.size) { if (array.isNull(it)) Double.NaN else array.getDouble(it) }
            }
            return Forecast(hours, values, fetched)
        }
    }
}
