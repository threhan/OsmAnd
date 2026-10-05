package net.osmand.plus.plugins.weather

import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import android.text.InputFilter
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import net.osmand.data.LatLon
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.weather.enums.WeatherSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OpenForecastUi {
    @JvmStatic fun show(activity: MapActivity, lat: Double, lon: Double, source: WeatherSource) {
        showForecast(activity, lat, lon, source, null)
    }

    @JvmStatic fun showPlaces(activity: MapActivity) {
        val places = try { SavedForecastPlaces.all(activity) } catch (_: Exception) {
            Toast.makeText(activity, R.string.forecast_places_storage_error, Toast.LENGTH_LONG).show(); return
        }
        val builder = AlertDialog.Builder(activity).setTitle(R.string.forecast_places_title)
            .setPositiveButton(R.string.forecast_places_add) { _, _ ->
                val source = net.osmand.plus.plugins.PluginsHelper.getPlugin(WeatherPlugin::class.java)?.weatherSource
                edit(activity, null, activity.mapView.latitude, activity.mapView.longitude,
                    source ?: WeatherSource.GFS) { showPlaces(activity) }
            }.setNegativeButton(R.string.shared_string_close, null)
        if (places.isEmpty()) builder.setMessage(R.string.forecast_places_empty)
        else builder.setItems(places.map { "${it.name}\n${activity.getString(it.source.titleId)} · ${coordinates(it.latitude, it.longitude)}" }.toTypedArray()) { _, index ->
            val place = places[index]
            DailyForecastUi.show(activity, place,
                edit = { edit(activity, place, place.latitude, place.longitude, place.source) { showPlaces(activity) } },
                delete = {
                    AlertDialog.Builder(activity).setTitle(R.string.forecast_places_delete)
                        .setMessage(activity.getString(R.string.forecast_places_delete_confirm, place.name))
                        .setNegativeButton(R.string.shared_string_cancel) { _, _ -> showPlaces(activity) }
                        .setPositiveButton(R.string.forecast_places_delete) { _, _ ->
                            try { SavedForecastPlaces.remove(activity, place.id); showPlaces(activity) }
                            catch (_: Exception) { Toast.makeText(activity, R.string.forecast_places_storage_error, Toast.LENGTH_LONG).show() }
                        }.show()
                },
                hourly = { showForecast(activity, place.latitude, place.longitude, place.source, place) })
        }
        builder.show()
    }

    private fun coordinates(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    private fun edit(activity: MapActivity, old: ForecastPlace?, lat: Double, lon: Double, source: WeatherSource, done: () -> Unit) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt(); setPadding(padding,padding,padding,padding)
        }
        fun field(label: Int, value: String, numeric: Boolean = false): EditText {
            form.addView(TextView(activity).apply { setText(label) })
            return EditText(activity).apply {
                setSingleLine(true)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else filters = arrayOf(InputFilter.LengthFilter(80))
                setText(value); form.addView(this)
            }
        }
        val name = field(R.string.forecast_places_name, old?.name ?: "")
        val initialLatitude = String.format(Locale.US, "%.5f", lat)
        val initialLongitude = String.format(Locale.US, "%.5f", lon)
        val latitude = field(R.string.forecast_places_latitude, initialLatitude, true)
        val longitude = field(R.string.forecast_places_longitude, initialLongitude, true)
        form.addView(TextView(activity).apply { setText(R.string.forecast_places_source) })
        val sources = WeatherSource.entries.toList()
        val selector = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sources.map { activity.getString(it.titleId) })
            setSelection(sources.indexOf(source).coerceAtLeast(0)); form.addView(this)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(if (old == null) R.string.forecast_places_add else R.string.forecast_places_edit)
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton(R.string.shared_string_cancel, null).setPositiveButton(R.string.shared_string_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                val a = if (latitude.text.toString() == initialLatitude) lat else latitude.text.toString().replace(',', '.').toDoubleOrNull()
                val b = if (longitude.text.toString() == initialLongitude) lon else longitude.text.toString().replace(',', '.').toDoubleOrNull()
                if (title.isEmpty()) { name.error = activity.getString(R.string.forecast_places_name_required); return@setOnClickListener }
                if (a == null || !a.isFinite() || a !in -90.0..90.0) { latitude.error = activity.getString(R.string.forecast_places_invalid_coordinate); return@setOnClickListener }
                if (b == null || !b.isFinite() || b !in -180.0..180.0) { longitude.error = activity.getString(R.string.forecast_places_invalid_coordinate); return@setOnClickListener }
                try {
                    val place = ForecastPlace(old?.id ?: java.util.UUID.randomUUID().toString(), title, a, b, sources[selector.selectedItemPosition])
                    SavedForecastPlaces.save(activity, place)
                    dialog.dismiss(); done()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(activity, R.string.forecast_places_limit, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.forecast_places_storage_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showForecast(activity: MapActivity, lat: Double, lon: Double, source: WeatherSource, place: ForecastPlace?) {
        val app = activity.application as net.osmand.plus.OsmandApplication
        val text = TextView(activity).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad,pad,pad,pad); setTextIsSelectable(true)
            setText(R.string.open_weather_loading)
        }
        val title = activity.getString(R.string.open_weather_point_title, activity.getString(source.titleId))
        val dialog = AlertDialog.Builder(activity).setTitle(if (place == null) title else "${place.name}\n$title")
            .setView(ScrollView(activity).apply { addView(text) }).setPositiveButton(R.string.shared_string_close,null)
            .setNeutralButton(R.string.forecast_places_title) { _, _ -> showPlaces(activity) }
            .setNegativeButton(if (place == null) R.string.shared_string_save else R.string.forecast_places_edit) { _, _ ->
                edit(activity, place, lat, lon, source) { showPlaces(activity) }
            }.create()
        dialog.show()
        app.weatherHelper.openForecastData.request(source,listOf(LatLon(lat,lon))) { result,error ->
            if (!dialog.isShowing || activity.isDestroyed || activity.isFinishing) return@request
            val f = result?.firstOrNull()
            if (f == null) { text.text = error; return@request }
            val date = SimpleDateFormat("MM-dd HH:mm",Locale.getDefault())
            fun value(name: String,time: Long) = f.value(name,time)?.let { String.format(Locale.getDefault(),"%.1f",it) } ?: "—"
            val builder = StringBuilder(activity.getString(R.string.open_weather_attribution)).append("\n")
                .append(String.format(Locale.US,"%.5f, %.5f",lat,lon)).append("\n")
                .append(activity.getString(R.string.open_weather_fetched,date.format(Date(f.fetched)))).append("\n\n")
                .append(activity.getString(R.string.open_weather_columns)).append("\n")
            for (time in f.hours) if (time >= System.currentTimeMillis()/3600000*3600000) {
                builder.append(date.format(Date(time))).append("  ")
                    .append(value("temperature_2m",time)).append(" °C  ")
                    .append(value("wind_speed_10m",time)).append(" m/s  ")
                    .append(value("precipitation",time)).append(" mm\n")
            }
            text.text=builder.toString()
        }
    }
}
