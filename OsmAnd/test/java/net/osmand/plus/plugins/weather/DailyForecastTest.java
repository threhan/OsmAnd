package net.osmand.plus.plugins.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public class DailyForecastTest {
    private JSONObject fixture() throws Exception {
        JSONObject daily = new JSONObject(), units = new JSONObject();
        JSONArray dates = new JSONArray();
        for (int d = 1; d <= 7; d++) dates.put("2026-11-0" + d);
        daily.put("time", dates);
        String[] names = {"temperature_2m_max", "temperature_2m_min", "rain_sum", "showers_sum",
                "precipitation_sum", "wind_gusts_10m_max", "wind_speed_10m_mean"};
        String[] labels = {"°C", "°C", "mm", "mm", "mm", "m/s", "m/s"};
        for (int i = 0; i < names.length; i++) {
            JSONArray values = new JSONArray();
            for (int d = 0; d < 7; d++) values.put(i == 0 ? 15 : i == 1 ? -5 : 0);
            daily.put(names[i], values); units.put(names[i], labels[i]);
        }
        return new JSONObject().put("timezone", "America/New_York").put("daily", daily).put("daily_units", units);
    }

    @Test public void destinationDatesSurviveDstAndExpiredDaysAreNotRepeated() throws Exception {
        DailyForecast f = DailyForecast.parse(fixture(), 123);
        assertEquals(7, f.getDays().size());
        assertEquals(123, f.getFetched());
        List<DailyForecast.Day> days = f.upcoming(Instant.parse("2026-11-02T02:00:00Z").toEpochMilli());
        assertEquals("2026-11-01", days.get(0).getDate());
        assertEquals("2026-11-07", days.get(6).getDate());
        days = f.upcoming(Instant.parse("2026-11-03T12:00:00Z").toEpochMilli());
        assertEquals("2026-11-03", days.get(0).getDate());
        assertEquals("2026-11-09", days.get(6).getDate());
        assertNull(days.get(6).getHigh());
        assertNull(days.get(6).hasRain());
    }

    @Test public void rainShowersSnowAndMissingDataStayDistinct() throws Exception {
        JSONObject j = fixture(), d = j.getJSONObject("daily");
        d.getJSONArray("rain_sum").put(0, JSONObject.NULL);
        d.getJSONArray("showers_sum").put(0, JSONObject.NULL);
        d.getJSONArray("precipitation_sum").put(1, 2);
        d.getJSONArray("showers_sum").put(1, .5);
        d.getJSONArray("precipitation_sum").put(2, 5);
        d.getJSONArray("showers_sum").put(3, JSONObject.NULL);
        d.getJSONArray("precipitation_sum").put(3, 5);
        d.getJSONArray("wind_gusts_10m_max").put(4, JSONObject.NULL);
        List<DailyForecast.Day> days = DailyForecast.parse(j, 0).getDays();
        assertEquals(Boolean.FALSE, days.get(0).hasRain());
        assertEquals(Boolean.TRUE, days.get(1).hasRain());
        assertEquals(Boolean.FALSE, days.get(2).hasRain()); // Snow does not imply rain.
        assertNull(days.get(3).hasRain());
        assertNull(days.get(4).getGust());
        assertEquals(-5, days.get(0).getLow(), 0);
    }

    @Test public void rejectsTruncatedArraysWrongUnitsAndDiscontinuousDates() throws Exception {
        JSONObject j = fixture(); j.getJSONObject("daily").put("rain_sum", new JSONArray()); invalid(j);
        j = fixture(); j.getJSONObject("daily_units").put("wind_speed_10m_mean", "km/h"); invalid(j);
        j = fixture(); j.getJSONObject("daily").getJSONArray("time").put(2, "2026-11-05"); invalid(j);
        j = fixture(); j.put("timezone", "Not/AZone"); invalid(j);
        j = fixture(); j.getJSONObject("daily").getJSONArray("temperature_2m_min").put(0, 20); invalid(j);
    }

    private void invalid(JSONObject j) {
        try { DailyForecast.parse(j, 0); fail("Invalid daily forecast accepted"); }
        catch (Exception expected) { }
    }

    @Test public void beaufortUsesMeanSpeedBoundaries() {
        assertEquals(0, DailyForecast.beaufort(0));
        assertEquals(0, DailyForecast.beaufort(.29));
        assertEquals(1, DailyForecast.beaufort(.3));
        assertEquals(4, DailyForecast.beaufort(5.5));
        assertEquals(11, DailyForecast.beaufort(32.6));
        assertEquals(12, DailyForecast.beaufort(32.7));
    }

    @Test public void snowIsIndependentOfRainAndMissingIsNotDry() throws Exception {
        JSONObject j = fixture();
        JSONArray snow = new JSONArray().put(0).put(1.2).put(.01).put(JSONObject.NULL).put(0).put(0).put(0);
        j.getJSONObject("daily").put("snowfall_sum", snow);
        j.getJSONObject("daily_units").put("snowfall_sum", "cm");
        j.getJSONObject("daily").getJSONArray("rain_sum").put(2, .5);
        DailyForecast f = DailyForecast.parse(j, 0);
        assertTrue(f.getHasSnowfallData());
        assertEquals(Boolean.FALSE, f.getDays().get(0).hasSnow());
        assertEquals(Boolean.TRUE, f.getDays().get(1).hasSnow());
        assertEquals(1.2, f.getDays().get(1).getSnowfall(), 0);
        assertEquals(Boolean.TRUE, f.getDays().get(2).hasSnow());
        assertEquals(Boolean.TRUE, f.getDays().get(2).hasRain());
        assertNull(f.getDays().get(3).hasSnow());
        assertNull(f.upcoming(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()).get(0).hasSnow());
    }

    @Test public void legacySnapshotRetainsTemperatureButSnowIsUnknown() throws Exception {
        DailyForecast f = DailyForecast.parse(fixture(), 0);
        assertFalse(f.getHasSnowfallData());
        assertEquals(15, f.getDays().get(0).getHigh(), 0);
        assertNull(f.getDays().get(0).hasSnow());
    }

    @Test public void snowRequiresCentimetersAndAlignedArray() throws Exception {
        JSONObject j = fixture();
        j.getJSONObject("daily").put("snowfall_sum", new JSONArray().put(1));
        j.getJSONObject("daily_units").put("snowfall_sum", "cm");
        invalid(j);
        j.getJSONObject("daily").put("snowfall_sum", new JSONArray().put(1).put(0).put(0).put(0).put(0).put(0).put(0));
        j.getJSONObject("daily_units").put("snowfall_sum", "mm");
        invalid(j);
    }
}
