package net.osmand.plus.plugins.weather;

import org.junit.Test;
import static org.junit.Assert.*;

public class CachedWeatherTimesTest {
    private static final long HOUR = 3600000L;

    @Test public void bridgesOnlyWithinKnownForecastIntervals() {
        long[] times = {0, HOUR, 2 * HOUR, 5 * HOUR, 11 * HOUR};
        assertEquals(Long.valueOf(2 * HOUR), CachedWeatherTimes.previousSample(times, 4 * HOUR));
        assertEquals(Long.valueOf(5 * HOUR), CachedWeatherTimes.previousSample(times, 10 * HOUR));
        assertEquals(Long.valueOf(11 * HOUR), CachedWeatherTimes.previousSample(times, 11 * HOUR));
    }

    @Test public void refusesExtrapolationAndMissingLargeIntervals() {
        assertNull(CachedWeatherTimes.previousSample(new long[0], HOUR));
        assertNull(CachedWeatherTimes.previousSample(new long[]{HOUR}, 0));
        assertNull(CachedWeatherTimes.previousSample(new long[]{HOUR}, 2 * HOUR));
        assertNull(CachedWeatherTimes.previousSample(new long[]{0, 7 * HOUR}, HOUR));
    }

    @Test public void usesActualUtcGridIncludingFractionalTimezoneOffsets() {
        long start = 1791159300000L;
        assertEquals(Long.valueOf(start), CachedWeatherTimes.previousSample(
                new long[]{start, start + 3 * HOUR}, start + 2 * HOUR));
        assertNull(CachedWeatherTimes.previousSample(new long[]{start, start + 3 * HOUR}, start - 1));
    }
}
