package net.osmand.plus.plugins.weather.alerts

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.util.WeatherAlertEvaluator
import java.util.Calendar
import java.util.Locale
import java.util.UUID

object WeatherAlertsUi {
    @JvmStatic fun show(activity: MapActivity, latitude: Double, longitude: Double) {
        val rules = WeatherAlertStore.all(activity)
        val labels = rules.map { rule ->
            val state = when {
                !rule.enabled -> activity.getString(R.string.weather_alert_disabled)
                rule.end < System.currentTimeMillis() -> activity.getString(R.string.weather_alert_expired)
                else -> activity.getString(statusLabel(rule.status))
            }
            "${rule.name}\n${WeatherAlertJobService.formatTime(rule.start)} – ${WeatherAlertJobService.formatTime(rule.end)}\n$state"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.weather_alert_title)
            .setItems(labels) { _, which -> details(activity, rules[which], latitude, longitude) }
            .setPositiveButton(R.string.weather_alert_add) { _, _ -> edit(activity, latitude, longitude, null) }
            .setNeutralButton(R.string.weather_alert_check) { _, _ ->
                val ok = WeatherAlertJobService.schedule(activity, true)
                Toast.makeText(activity, if (ok) R.string.weather_alert_queued else R.string.weather_alert_schedule_failed, Toast.LENGTH_LONG).show()
            }.setNegativeButton(R.string.shared_string_close, null)
        if (rules.isEmpty()) dialog.setMessage(R.string.weather_alert_intro)
        dialog.show()
    }

    private fun statusLabel(status: String): Int = when (status) {
        "matched" -> R.string.weather_alert_matched
        "no_match" -> R.string.weather_alert_no_match
        "failed" -> R.string.weather_alert_failed
        "incomplete" -> R.string.weather_alert_incomplete
        "notifications_blocked" -> R.string.weather_alert_notifications_blocked
        else -> R.string.weather_alert_never
    }

    private fun details(activity: MapActivity, rule: WeatherAlertRule, lat: Double, lon: Double) {
        val padding = activity.resources.getDimensionPixelSize(R.dimen.weather_alert_padding)
        val rowPadding = activity.resources.getDimensionPixelSize(R.dimen.weather_alert_row_padding)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, rowPadding, padding, rowPadding) }
        fun text(value: String) { body.addView(TextView(activity).apply { text = value; setPadding(0, rowPadding, 0, rowPadding) }) }
        fun button(label: Int, action: () -> Unit) { body.addView(Button(activity).apply { setText(label); setOnClickListener { action() } }) }
        text(String.format(Locale.getDefault(), "%.5f, %.5f", rule.latitude, rule.longitude))
        text("${WeatherAlertJobService.formatTime(rule.start)} – ${WeatherAlertJobService.formatTime(rule.end)}")
        for (metric in AlertMetric.values()) rule.thresholds[metric.key]?.let {
            text(activity.getString(R.string.weather_alert_threshold, activity.getString(metric.label), it))
        }
        text(activity.getString(statusLabel(rule.status)))
        text(activity.getString(R.string.weather_alert_last_check, if (rule.checked == 0L) activity.getString(R.string.weather_alert_never) else WeatherAlertJobService.formatTime(rule.checked)))
        if (rule.fetched > 0) text(activity.getString(R.string.weather_alert_notification_source, WeatherAlertJobService.formatTime(rule.fetched)))
        if (rule.fetched > 0 && System.currentTimeMillis() - rule.fetched > 6 * WeatherAlertEvaluator.HOUR) text(activity.getString(R.string.weather_alert_stale))
        text(activity.getString(R.string.weather_alert_intro))
        val dialog = AlertDialog.Builder(activity).setTitle(rule.name).setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton(R.string.shared_string_close, null).create()
        button(R.string.shared_string_edit) { dialog.dismiss(); edit(activity, rule.latitude, rule.longitude, rule) }
        button(if (rule.enabled) R.string.weather_alert_disable else R.string.weather_alert_enable) {
            WeatherAlertStore.put(activity, rule.copy(enabled = !rule.enabled, revision = UUID.randomUUID().toString(), notified = emptySet(), status = "never"))
            WeatherAlertJobService.schedule(activity, !rule.enabled)
            dialog.dismiss(); show(activity, lat, lon)
        }
        button(R.string.shared_string_delete) {
            AlertDialog.Builder(activity).setMessage(R.string.weather_alert_delete_confirm)
                .setPositiveButton(R.string.shared_string_delete) { _, _ ->
                    WeatherAlertStore.remove(activity, rule.id)
                    WeatherAlertJobService.schedule(activity)
                    dialog.dismiss(); show(activity, lat, lon)
                }.setNegativeButton(R.string.shared_string_cancel, null).show()
        }
        button(R.string.weather_alert_notification_settings) {
            val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        }
        dialog.show()
    }

    private fun edit(activity: MapActivity, latitude: Double, longitude: Double, old: WeatherAlertRule?) {
        if (old == null && WeatherAlertStore.all(activity).size >= 20) {
            Toast.makeText(activity, R.string.weather_alert_limit, Toast.LENGTH_LONG).show(); return
        }
        val hour = WeatherAlertEvaluator.HOUR
        val now = System.currentTimeMillis()
        var start = old?.start ?: ((now / hour + 1) * hour)
        var end = old?.end ?: (start + 24 * hour)
        val padding = activity.resources.getDimensionPixelSize(R.dimen.weather_alert_padding)
        val rowPadding = activity.resources.getDimensionPixelSize(R.dimen.weather_alert_row_padding)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, rowPadding, padding, rowPadding) }
        val label = EditText(activity).apply {
            setHint(R.string.weather_alert_location_name)
            setText(old?.name ?: String.format(Locale.getDefault(), "%.5f, %.5f", latitude, longitude))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        body.addView(label)
        body.addView(TextView(activity).apply { setText(R.string.weather_alert_hourly) })
        fun timeButton(labelId: Int, initial: Long, change: (Long) -> Unit): Button {
            val b = Button(activity)
            var selected = initial
            fun render() { b.text = activity.getString(labelId, WeatherAlertJobService.formatTime(selected)) }
            render()
            b.setOnClickListener {
                val c = Calendar.getInstance().apply { timeInMillis = selected }
                DatePickerDialog(activity, { _, y, m, d ->
                    TimePickerDialog(activity, { _, h, _ ->
                        c.set(y, m, d, h, 0, 0); c.set(Calendar.MILLISECOND, 0)
                        selected = c.timeInMillis; change(selected); render()
                    }, c.get(Calendar.HOUR_OF_DAY), 0, true).show()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                    datePicker.minDate = now
                    datePicker.maxDate = now + 7 * 24 * hour
                }.show()
            }
            body.addView(b)
            return b
        }
        timeButton(R.string.weather_alert_start, start) { start = it }
        timeButton(R.string.weather_alert_end, end) { end = it }
        val inputs = AlertMetric.values().associateWith { metric ->
            val checkbox = CheckBox(activity).apply {
                setText(metric.label)
                isChecked = old?.thresholds?.containsKey(metric.key) ?: (metric == AlertMetric.RAIN || metric == AlertMetric.WIND)
            }
            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText((old?.thresholds?.get(metric.key) ?: metric.defaultValue).toString())
                isEnabled = checkbox.isChecked
            }
            checkbox.setOnCheckedChangeListener { _, checked -> input.isEnabled = checked }
            body.addView(checkbox); body.addView(input)
            checkbox to input
        }
        body.addView(TextView(activity).apply { setText(R.string.weather_alert_intro) })
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.weather_alert_add)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(R.string.shared_string_save, null).setNegativeButton(R.string.shared_string_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val thresholds = mutableMapOf<String, Double>()
                var valid = label.text.toString().trim().isNotEmpty() && label.text.length <= 80 && latitude.isFinite() && longitude.isFinite()
                    && latitude in -90.0..90.0 && longitude in -180.0..180.0
                    && start < end && end > System.currentTimeMillis() && end <= System.currentTimeMillis() + 7 * 24 * hour
                for ((metric, pair) in inputs) if (pair.first.isChecked) {
                    val value = pair.second.text.toString().replace(',', '.').toDoubleOrNull()
                    if (value == null || !value.isFinite() || (metric == AlertMetric.COLD && value !in -100.0..70.0) || (metric != AlertMetric.COLD && value <= 0)) valid = false
                    else thresholds[metric.key] = value
                }
                if (!valid || thresholds.isEmpty()) {
                    Toast.makeText(activity, R.string.weather_alert_invalid, Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                val rule = WeatherAlertRule(id = old?.id ?: UUID.randomUUID().toString(), name = label.text.toString().trim(),
                    latitude = latitude, longitude = longitude, start = start, end = end, thresholds = thresholds)
                WeatherAlertStore.put(activity, rule)
                val scheduled = WeatherAlertJobService.schedule(activity, true)
                dialog.dismiss()
                Toast.makeText(activity, if (scheduled) R.string.weather_alert_queued else R.string.weather_alert_schedule_failed, Toast.LENGTH_LONG).show()
                if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 39185)
                }
            }
        }
        dialog.show()
    }
}
