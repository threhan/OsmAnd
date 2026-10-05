package net.osmand.plus.plugins.weather.alerts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WeatherAlertRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val start: Long,
    val end: Long,
    val thresholds: Map<String, Double>,
    val enabled: Boolean = true,
    val revision: String = UUID.randomUUID().toString(),
    val notified: Set<String> = emptySet(),
    val checked: Long = 0,
    val fetched: Long = 0,
    val status: String = "never"
) {
    fun json() = JSONObject().put("id", id).put("name", name)
        .put("lat", latitude).put("lon", longitude).put("start", start).put("end", end)
        .put("thresholds", JSONObject(thresholds)).put("enabled", enabled).put("revision", revision)
        .put("notified", JSONArray(notified.toList())).put("checked", checked).put("fetched", fetched).put("status", status)

    companion object {
        fun parse(j: JSONObject): WeatherAlertRule {
            val thresholds = j.getJSONObject("thresholds")
            val notified = j.optJSONArray("notified") ?: JSONArray()
            return WeatherAlertRule(j.getString("id"), j.getString("name"), j.getDouble("lat"), j.getDouble("lon"),
                j.getLong("start"), j.getLong("end"), thresholds.keys().asSequence().associateWith { thresholds.getDouble(it) },
                j.getBoolean("enabled"), j.getString("revision"), (0 until notified.length()).map { notified.getString(it) }.toSet(),
                j.optLong("checked"), j.optLong("fetched"), j.optString("status", "never"))
        }
    }
}

object WeatherAlertStore {
    private fun prefs(c: Context) = c.getSharedPreferences("weather_point_alerts", Context.MODE_PRIVATE)

    @Synchronized fun all(c: Context): List<WeatherAlertRule> {
        val array = JSONArray(prefs(c).getString("rules", "[]"))
        return (0 until array.length()).map { WeatherAlertRule.parse(array.getJSONObject(it)) }
    }

    private fun write(c: Context, rules: List<WeatherAlertRule>) {
        check(prefs(c).edit().putString("rules", JSONArray(rules.map { it.json() }).toString()).commit())
    }

    @Synchronized fun put(c: Context, rule: WeatherAlertRule) {
        write(c, all(c).filterNot { it.id == rule.id } + rule)
    }

    @Synchronized fun remove(c: Context, id: String) { write(c, all(c).filterNot { it.id == id }) }

    // Editing/deleting a rule while an HTTP request is running invalidates that result.
    @Synchronized fun finish(c: Context, original: WeatherAlertRule, update: (WeatherAlertRule) -> WeatherAlertRule) {
        val current = all(c).find { it.id == original.id && it.revision == original.revision && it.enabled } ?: return
        put(c, update(current))
    }
}
