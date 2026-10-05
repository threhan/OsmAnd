package net.osmand.plus.plugins.weather.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.osmand.PlatformUtil
import net.osmand.plus.R
import net.osmand.plus.activities.search.GeoIntentActivity
import net.osmand.util.WeatherAlertEvaluator
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

enum class AlertMetric(val key: String, val label: Int, val defaultValue: Double, val below: Boolean = false, val accumulation: Boolean = false) {
    COLD("temperature_2m", R.string.weather_alert_cold, 0.0, true),
    WIND("wind_speed_10m", R.string.weather_alert_wind, 10.0),
    RAIN("rain", R.string.weather_alert_rain, 0.5, accumulation = true),
    SNOW("snowfall", R.string.weather_alert_snow, 0.1, accumulation = true)
}

class WeatherAlertJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val tasks = mutableMapOf<JobParameters, FutureTask<Unit>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val task = FutureTask<Unit> {
            try {
                for (rule in WeatherAlertStore.all(this).filter { it.enabled && it.end > System.currentTimeMillis() }) {
                    if (Thread.currentThread().isInterrupted) break
                    checkRule(rule)
                }
            } catch (e: Exception) {
                log.warn("Weather alert job failed", e)
            } finally {
                Handler(Looper.getMainLooper()).post {
                    if (tasks.remove(params) != null) {
                        jobFinished(params, false)
                        schedule(this)
                    }
                }
            }
        }
        tasks[params] = task
        executor.execute(task)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params)?.cancel(true)
        return true
    }

    override fun onDestroy() {
        tasks.values.forEach { it.cancel(true) }
        tasks.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun checkRule(rule: WeatherAlertRule) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${rule.latitude}&longitude=${rule.longitude}" +
                "&hourly=temperature_2m,wind_speed_10m,rain,showers,snowfall&wind_speed_unit=ms" +
                "&temperature_unit=celsius&precipitation_unit=mm&timeformat=unixtime&timezone=GMT&forecast_days=8"
            val connection = URL(url).openConnection() as HttpURLConnection
            val forecast = try {
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                check(connection.responseCode == 200) { "Forecast HTTP ${connection.responseCode}" }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                check(text.length < 1000000) { "Forecast response too large" }
                JSONObject(text)
            } finally { connection.disconnect() }
            if (Thread.currentThread().isInterrupted) return
            val fetched = System.currentTimeMillis()
            val hourly = forecast.getJSONObject("hourly")
            val units = forecast.getJSONObject("hourly_units")
            check(units.getString("time") == "unixtime" && units.getString("wind_speed_10m") == "m/s"
                && units.getString("rain") == "mm" && units.getString("snowfall") == "cm"
                && units.getString("temperature_2m") == "\u00b0C") { "Unexpected forecast units" }
            val rawTimes = hourly.getJSONArray("time")
            val times = LongArray(rawTimes.length()) { rawTimes.getLong(it) * 1000 }
            check(times.indices.drop(1).all { times[it] > times[it - 1] }) { "Unordered forecast" }
            val matches = mutableListOf<Triple<AlertMetric, Long, Double>>()
            var complete = true
            for (metric in AlertMetric.values()) {
                val threshold = rule.thresholds[metric.key] ?: continue
                val raw = hourly.getJSONArray(metric.key)
                check(raw.length() == times.size)
                val values = DoubleArray(raw.length()) { raw.optDouble(it, Double.NaN) }
                if (metric == AlertMetric.RAIN) {
                    val showers = hourly.getJSONArray("showers")
                    check(showers.length() == values.size && units.getString("showers") == "mm")
                    for (i in values.indices) values[i] += showers.optDouble(i, Double.NaN)
                }
                complete = complete && WeatherAlertEvaluator.covers(times, values, rule.start, rule.end, fetched)
                val i = WeatherAlertEvaluator.firstMatch(times, values, rule.start, rule.end, fetched, threshold, metric.below, metric.accumulation)
                if (i >= 0) matches.add(Triple(metric, times[i], values[i]))
            }
            if (Thread.currentThread().isInterrupted) return
            WeatherAlertStore.finish(this, rule) { current ->
                val pending = matches.filterNot { it.first.key in current.notified }
                val sent = pending.isNotEmpty() && notify(current, pending, fetched)
                current.copy(checked = fetched, fetched = fetched,
                    notified = if (sent) current.notified + pending.map { it.first.key } else current.notified,
                    status = when {
                        pending.isNotEmpty() && !sent -> "notifications_blocked"
                        matches.isNotEmpty() -> "matched"
                        !complete -> "incomplete"
                        else -> "no_match"
                    })
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) {
                log.warn("Unable to check weather alert", e)
                WeatherAlertStore.finish(this, rule) { it.copy(checked = System.currentTimeMillis(), status = "failed") }
            }
        }
    }

    private fun notify(rule: WeatherAlertRule, hits: List<Triple<AlertMetric, Long, Double>>, fetched: Long): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.weather_alert_title), NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        val lines = hits.map { (metric, time, value) ->
            getString(R.string.weather_alert_hit, getString(metric.label), rule.thresholds.getValue(metric.key), value, formatTime(time))
        }.joinToString("\n")
        val text = lines + "\n" + getString(R.string.weather_alert_notification_source, formatTime(fetched))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${rule.latitude},${rule.longitude}?z=13"), this, GeoIntentActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, rule.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_umbrella).setContentTitle(getString(R.string.weather_alert_notification_title, rule.name))
            .setContentText(lines).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(pending).build()
        manager.notify(rule.id, 1, notification)
        return true
    }

    companion object {
        private const val PERIODIC_ID = 842701
        private const val IMMEDIATE_ID = 842702
        private const val CHANNEL = "weather_point_alerts"
        private val log = PlatformUtil.getLog(WeatherAlertJobService::class.java)

        @JvmStatic fun schedule(context: Context, immediate: Boolean = false): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val any = WeatherAlertStore.all(context).any { it.enabled && it.end > System.currentTimeMillis() }
            if (!any) {
                scheduler.cancel(PERIODIC_ID)
                scheduler.cancel(IMMEDIATE_ID)
                return true
            }
            val component = ComponentName(context, WeatherAlertJobService::class.java)
            if (scheduler.getPendingJob(PERIODIC_ID) == null) {
                if (scheduler.schedule(JobInfo.Builder(PERIODIC_ID, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                        .setPeriodic(WeatherAlertEvaluator.HOUR).build()) != JobScheduler.RESULT_SUCCESS) return false
            }
            return !immediate || scheduler.schedule(JobInfo.Builder(IMMEDIATE_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build()) == JobScheduler.RESULT_SUCCESS
        }

        fun formatTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    }
}
