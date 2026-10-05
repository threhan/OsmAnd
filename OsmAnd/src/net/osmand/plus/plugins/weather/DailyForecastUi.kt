package net.osmand.plus.plugins.weather

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import net.osmand.data.LatLon
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DailyForecastUi {
    fun show(activity: MapActivity, place: ForecastPlace, edit: () -> Unit, delete: () -> Unit, hourly: () -> Unit) {
        ForecastComparisonUi.showPlace(activity, place, edit, delete, hourly)
    }

    fun showDetails(activity: MapActivity, place: ForecastPlace, edit: () -> Unit, delete: () -> Unit, hourly: () -> Unit) {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        fun text(value: String, bold: Boolean = false) = TextView(activity).apply {
            text = value; textSize = 15f; setPadding(dp(4), dp(5), dp(4), dp(5))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(text(String.format(Locale.US, "%s · %.5f, %.5f", activity.getString(place.source.titleId), place.latitude, place.longitude)))
        val actions = LinearLayout(activity)
        root.addView(actions)
        val dialog = AlertDialog.Builder(activity).setTitle(place.name)
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(R.string.shared_string_close, null)
            .setNeutralButton(R.string.forecast_daily_refresh, null).create()
        fun action(parent: LinearLayout, label: Int, callback: () -> Unit) {
            parent.addView(Button(activity).apply {
                setText(label); isAllCaps = false
                setOnClickListener { dialog.dismiss(); callback() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        action(actions, R.string.forecast_places_edit, edit)
        action(actions, R.string.forecast_places_delete, delete)
        val forecasts = LinearLayout(activity)
        root.addView(forecasts)
        action(forecasts, R.string.forecast_compare_title) { ForecastComparisonUi.show(activity, place.latitude, place.longitude, place.name) }
        action(forecasts, R.string.open_weather_point_menu, hourly)
        root.addView(text(activity.getString(R.string.forecast_daily_title), true))
        val status = text(""); root.addView(status)
        val days = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }; root.addView(days)
        root.addView(text(activity.getString(R.string.forecast_daily_note)))
        var generation = 0
        fun request(force: Boolean) {
            val token = ++generation
            status.setText(R.string.open_weather_loading)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            (activity.application as OsmandApplication).weatherHelper.openForecastData.requestDaily(
                place.source, LatLon(place.latitude, place.longitude), force) callback@{ forecast, cached, error ->
                if (!dialog.isShowing || activity.isDestroyed || activity.isFinishing || token != generation) return@callback
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                days.removeAllViews()
                if (forecast == null) { status.text = error ?: activity.getString(R.string.forecast_daily_error); return@callback }
                val fetched = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(forecast.fetched))
                status.text = activity.getString(if (cached) R.string.forecast_daily_cached else R.string.forecast_daily_fetched, fetched, forecast.timezone) +
                    (if (error != null) "\n$error" else "")
                fun value(v: Double?) = v?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: activity.getString(R.string.forecast_daily_unknown)
                for (day in forecast.upcoming(System.currentTimeMillis())) {
                    val rain = activity.getString(when (day.hasRain()) {
                        true -> R.string.forecast_daily_rain_yes
                        false -> R.string.forecast_daily_rain_no
                        null -> R.string.forecast_daily_unknown
                    })
                    val wind = day.meanWind?.let {
                        // Apply the scale to the displayed precision so a boundary cannot look contradictory.
                        val displayedSpeed = String.format(Locale.US, "%.1f", it).toDouble()
                        activity.getString(R.string.forecast_daily_wind_value, DailyForecast.beaufort(displayedSpeed), value(it))
                    }
                        ?: activity.getString(R.string.forecast_daily_unknown)
                    days.addView(text(day.date, true))
                    days.addView(text(activity.getString(R.string.forecast_daily_temperatures, value(day.high), value(day.low))))
                    days.addView(text(activity.getString(R.string.forecast_daily_rain, rain, value(day.precipitation))))
                    val snow = when (day.hasSnow()) {
                        true -> activity.getString(R.string.forecast_daily_snow_yes,
                            if (day.snowfall!! < .1) activity.getString(R.string.forecast_daily_trace_snow) else value(day.snowfall))
                        false -> activity.getString(R.string.forecast_daily_snow_no)
                        null -> activity.getString(R.string.forecast_daily_snow_unknown)
                    }
                    days.addView(text(snow))
                    days.addView(text(activity.getString(R.string.forecast_daily_wind, value(day.gust), wind)))
                }
            }
        }
        dialog.setOnDismissListener { generation++ }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { request(true) }
        request(false)
    }
}
