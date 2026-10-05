package net.osmand.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherAlertEvaluatorTest {
    private static final long H = WeatherAlertEvaluator.HOUR;

    @Test public void evaluatesInclusiveThresholdsAndUtcWindow() {
        long[] times = {H, 2 * H, 3 * H, 4 * H};
        double[] values = {-5, 0, 10, 20};
        assertEquals(1, WeatherAlertEvaluator.firstMatch(times, values, 2 * H, 3 * H, 0, 0, true, false));
        assertEquals(2, WeatherAlertEvaluator.firstMatch(times, values, 2 * H, 3 * H, 0, 10, false, false));
        assertEquals(-1, WeatherAlertEvaluator.firstMatch(times, values, 2 * H, 3 * H, 0, 20, false, false));
    }

    @Test public void doesNotAlertForPastHoursOrOldRainfall() {
        long[] times = {H, 2 * H, 3 * H};
        double[] values = {2, 0, 1};
        assertEquals(-1, WeatherAlertEvaluator.firstMatch(times, values, H, 2 * H, 0, 1, false, true));
        assertEquals(2, WeatherAlertEvaluator.firstMatch(times, values, H, 3 * H, 2 * H, 1, false, true));
        assertEquals(-1, WeatherAlertEvaluator.firstMatch(times, values, H, 3 * H, 4 * H, 1, false, false));
    }

    @Test public void missingDataCannotProduceAnAllClear() {
        long[] times = {H, 2 * H, 3 * H};
        double[] missing = {1, Double.NaN, 1};
        assertFalse(WeatherAlertEvaluator.covers(times, missing, H, 3 * H, 0));
        assertFalse(WeatherAlertEvaluator.covers(times, new double[]{1, 1, 1}, H, 4 * H, 0));
        assertFalse(WeatherAlertEvaluator.covers(new long[]{H, 3 * H}, new double[]{1, 1}, H, 3 * H, 0));
        assertEquals(-1, WeatherAlertEvaluator.firstMatch(times, missing, 2 * H, 2 * H, 0, 0, false, false));
        assertTrue(WeatherAlertEvaluator.covers(times, new double[]{1, 1, 1}, H + H / 2, 3 * H, 0));
    }

    @Test public void forecastWindowCanCrossMidnight() {
        long[] times = {23 * H, 24 * H, 25 * H};
        double[] values = {1, 2, 3};
        assertEquals(1, WeatherAlertEvaluator.firstMatch(times, values, 23 * H, 25 * H, 23 * H, 2, false, false));
        assertTrue(WeatherAlertEvaluator.covers(times, values, 23 * H, 25 * H, 23 * H));
    }
}
