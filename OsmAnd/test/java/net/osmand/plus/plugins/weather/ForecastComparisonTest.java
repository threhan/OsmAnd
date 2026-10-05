package net.osmand.plus.plugins.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ForecastComparisonTest {
    @Test public void aggregatesThreeHoursEndingAtColumnAndPreservesMissing() {
        Map<String,double[]> values = new HashMap<>();
        values.put("precipitation", new double[]{0, .5, 1.5, Double.NaN});
        values.put("wind_gusts_10m", new double[]{3, 8, 4, 1});
        OpenForecastData.Forecast f = new OpenForecastData.Forecast(new long[]{0,3600000,7200000,10800000},values,0);
        assertEquals(2, ForecastComparisonValues.sum(f,"precipitation",7200000),0);
        assertEquals(8, ForecastComparisonValues.maximum(f,"wind_gusts_10m",7200000),0);
        assertNull(ForecastComparisonValues.sum(f,"precipitation",10800000));
        assertNull(ForecastComparisonValues.sum(f,"precipitation",0));
        assertNull(ForecastComparisonValues.sum(f,"snowfall",7200000));
        assertNull(ForecastComparisonValues.sum(null,"precipitation",7200000));
    }

    @Test public void smallSnowIsNotFormattedAsZero() {
        assertEquals("<0.1", ForecastComparisonValues.snowText(.01));
        assertEquals("—", ForecastComparisonValues.snowText(null));
        assertNotEquals("<0.1", ForecastComparisonValues.snowText(0.0));
    }

    @Test public void hourlyAndDailyIntervalsAggregateIndependently() {
        java.util.TimeZone previous=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            long[] times=new long[24];double[] numbers=new double[24];
            for(int i=0;i<24;i++){times[i]=i*3600000L;numbers[i]=i;}
            Map<String,double[]> data=new HashMap<>();
            for(String key:new String[]{"temperature_2m","precipitation","snowfall","wind_speed_10m","wind_gusts_10m"})data.put(key,numbers);
            OpenForecastData.Forecast f=new OpenForecastData.Forecast(times,data,0);
            assertEquals(2,ForecastComparisonValues.sample(f,"precipitation",7200000L,1),0);
            assertEquals(3,ForecastComparisonValues.sample(f,"precipitation",7200000L,3),0);
            assertEquals(276,ForecastComparisonValues.sample(f,"precipitation",0,24),0);
            assertEquals(11.5,ForecastComparisonValues.sample(f,"wind_speed_10m",0,24),0);
            assertEquals(23,ForecastComparisonValues.sample(f,"wind_gusts_10m",0,24),0);
            assertTrue(ForecastComparisonValues.temperatureRange(f,0).contains("23"));
            numbers[10]=Double.NaN;
            assertNull(ForecastComparisonValues.sample(f,"snowfall",0,24));
            assertEquals(168,ForecastComparisonValues.timeline(0,1).size());
            assertEquals(56,ForecastComparisonValues.timeline(0,3).size());
            assertEquals(7,ForecastComparisonValues.timeline(0,24).size());
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            long dst=java.time.Instant.parse("2026-03-08T05:00:00Z").toEpochMilli();
            java.util.List<Long> days=ForecastComparisonValues.timeline(dst,24);
            assertEquals(23*3600000L,days.get(1)-days.get(0));
            assertEquals(7,days.size());
        } finally { java.util.TimeZone.setDefault(previous); }
    }

    private JSONObject forecast() throws Exception {
        JSONObject root=new JSONObject(),hourly=new JSONObject(),units=new JSONObject();
        JSONArray times=new JSONArray();for(int i=0;i<384;i++)times.put(i*3600);
        hourly.put("time",times);
        String[] expected={"°C","hPa","m/s","%","mm","°"};int i=0;
        Map<String,String> variables=new java.util.LinkedHashMap<>();
        for(String name:OpenForecastData.Companion.getVARIABLES())variables.put(name,expected[i++]);
        variables.putAll(OpenForecastData.Companion.getCOMPARISON_UNITS());
        for(Map.Entry<String,String> entry:variables.entrySet()){
            JSONArray array=new JSONArray();for(int n=0;n<384;n++)array.put(n<240?0:JSONObject.NULL);
            hourly.put(entry.getKey(),array);units.put(entry.getKey(),entry.getValue());
        }
        return root.put("hourly",hourly).put("hourly_units",units);
    }

    @Test public void extendedTimelineKeepsUnsupportedHorizonMissing() throws Exception {
        OpenForecastData.Forecast f=OpenForecastData.Companion.parse(forecast(),123);
        assertEquals(384,f.getHours().length);
        assertEquals(0,f.value("snowfall",239*3600000L),0);
        assertNull(f.value("snowfall",240*3600000L));
        assertNull(f.value("temperature_2m",384*3600000L));
        assertEquals(13,f.getValues().size());
    }

    @Test public void optionalComparisonFieldsStillRequireCorrectUnitsAndAlignment() throws Exception {
        JSONObject j=forecast();j.getJSONObject("hourly_units").put("snowfall","mm");invalid(j);
        j=forecast();j.getJSONObject("hourly").put("is_day",new JSONArray().put(1));invalid(j);
    }
    private void invalid(JSONObject j) {
        try {OpenForecastData.Companion.parse(j,0);fail("Malformed comparison accepted");}
        catch (Exception expected) { }
    }
}
