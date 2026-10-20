package net.osmand.plus.plugins.weather.enums

import androidx.annotation.StringRes
import net.osmand.plus.R

enum class WeatherSource(
	@StringRes val titleId: Int,
	@StringRes val descriptionId: Int,
	val settingValue: String) {
	GFS(R.string.weather_source_GFS_title, R.string.weather_source_GFS_description, "gfs"),
	ECMWF(R.string.weather_source_ecmwf_title, R.string.weather_source_ecmwf_description, "ecmwf"),
	ICON(R.string.open_weather_icon, R.string.open_weather_icon_description, "icon_global"),
	GEM(R.string.open_weather_gem, R.string.open_weather_gem_description, "gem_global"),
	IFS(R.string.open_weather_ifs, R.string.open_weather_ifs_description, "ecmwf_ifs");

    // Calendar-day request limits; the last day may contain unavailable model hours.
    val pointForecastDays: Int get() = when (this) {
        GFS -> 16
        ECMWF, IFS -> 15
        ICON -> 8
        GEM -> 10
    }

	val isExternal: Boolean get() = this == ICON || this == GEM || this == IFS

	companion object {
		fun getDefaultSource(): WeatherSource {
			return GFS
		}

		fun getWeatherSourceBySettingsValue(settingsValue: String): WeatherSource {
			for (source in entries) {
				if (source.settingValue == settingsValue) {
					return source
				}
			}
			return getDefaultSource()
		}
	}
}