package net.osmand.plus.plugins.weather

import android.content.Context
import net.osmand.plus.plugins.weather.enums.WeatherSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ForecastPlace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val source: WeatherSource
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 80)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** Separate from notification rules: saving a place does not schedule alerts. */
object SavedForecastPlaces {
    private fun prefs(context: Context) = context.getSharedPreferences("saved_forecast_places", Context.MODE_PRIVATE)

    fun encode(places: List<ForecastPlace>): String = JSONArray(places.map {
        JSONObject().put("id", it.id).put("name", it.name).put("lat", it.latitude)
            .put("lon", it.longitude).put("source", it.source.settingValue)
    }).toString()

    fun decode(value: String): List<ForecastPlace> {
        val array = JSONArray(value)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val source = WeatherSource.entries.first { it.settingValue == item.getString("source") }
            ForecastPlace(item.getString("id"), item.getString("name"), item.getDouble("lat"), item.getDouble("lon"), source)
        }
    }

    @Synchronized fun all(context: Context): List<ForecastPlace> = decode(prefs(context).getString("places", "[]") ?: "[]")

    fun replace(places: List<ForecastPlace>, place: ForecastPlace): List<ForecastPlace> {
        require(places.size < 100 || places.any { it.id == place.id })
        return if (places.any { it.id == place.id }) places.map { if (it.id == place.id) place else it } else places + place
    }

    @Synchronized fun save(context: Context, place: ForecastPlace) {
        check(prefs(context).edit().putString("places", encode(replace(all(context), place))).commit())
    }

    @Synchronized fun remove(context: Context, id: String) {
        check(prefs(context).edit().putString("places", encode(all(context).filterNot { it.id == id })).commit())
    }
}
