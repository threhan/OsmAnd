package net.osmand.plus.plugins.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs on the JVM with org.json, without initializing the Android network client. */
public class OpenForecastDataTest {
    @Test public void comparisonUsesExplicitModelsWithoutChangingMapSettings() {
        assertEquals("gfs_global", OpenForecastData.model(net.osmand.plus.plugins.weather.enums.WeatherSource.GFS));
        assertEquals("ecmwf_ifs025", OpenForecastData.model(net.osmand.plus.plugins.weather.enums.WeatherSource.ECMWF));
        assertEquals("icon_global", OpenForecastData.model(net.osmand.plus.plugins.weather.enums.WeatherSource.ICON));
        assertEquals("gem_global", OpenForecastData.model(net.osmand.plus.plugins.weather.enums.WeatherSource.GEM));
        assertEquals("gfs", net.osmand.plus.plugins.weather.enums.WeatherSource.GFS.getSettingValue());
        assertEquals("ecmwf", net.osmand.plus.plugins.weather.enums.WeatherSource.ECMWF.getSettingValue());
    }
    private JSONObject fixture() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject hourly = new JSONObject().put("time", new JSONArray().put(3600).put(7200));
        JSONObject units = new JSONObject();
        String[] expected = {"°C", "hPa", "m/s", "%", "mm", "°"};
        int i = 0;
        for (String name : OpenForecastData.Companion.getVARIABLES()) {
            hourly.put(name, new JSONArray().put(0).put(JSONObject.NULL));
            units.put(name, expected[i++]);
        }
        return root.put("hourly", hourly).put("hourly_units", units);
    }

    @Test public void distinguishesDryWeatherFromMissingDataAndDoesNotExtrapolate() throws Exception {
        OpenForecastData.Forecast f = OpenForecastData.Companion.parse(fixture(), 123);
        assertEquals(0.0, f.value("precipitation", 3600000), 0.0);
        assertEquals(0.0, f.value("precipitation", 5400000), 0.0);
        assertNull(f.value("precipitation", 7200000));
        assertNull(f.value("precipitation", 0));
        assertNull(f.value("precipitation", 10800000));
        assertEquals(123, f.getFetched());
    }

    @Test public void rejectsMisalignedHoursAndTruncatedVariables() throws Exception {
        JSONObject f = fixture();
        f.getJSONObject("hourly").put("time", new JSONArray().put(3600).put(10800));
        expectInvalid(f);
        JSONObject g = fixture();
        g.getJSONObject("hourly").put("precipitation", new JSONArray().put(0));
        expectInvalid(g);
    }

    private void expectInvalid(JSONObject data) throws Exception {
        try { OpenForecastData.Companion.parse(data, 123); fail("Invalid forecast accepted"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void rejectsUnexpectedWindUnits() throws Exception {
        JSONObject f = fixture();
        f.getJSONObject("hourly_units").put("wind_speed_10m", "km/h");
        expectInvalid(f);
    }

    @Test public void resolvesHourlyGridWithQuarterHourOffsetWithoutExtrapolation() throws Exception {
        JSONObject data = fixture();
        data.getJSONObject("hourly").put("time", new JSONArray().put(4500).put(8100));
        OpenForecastData.Forecast f = OpenForecastData.Companion.parse(data, 123);
        assertNull(f.value("temperature_2m", 4499999));
        assertEquals(0.0, f.value("temperature_2m", 4500000), 0.0);
        assertEquals(0.0, f.value("temperature_2m", 8099999), 0.0);
        assertNull(f.value("temperature_2m", 8100000));
        assertNull(f.value("temperature_2m", 11700000));
    }
}
