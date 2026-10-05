package net.osmand.util;

/** Evaluates hourly forecasts in UTC. Accumulations represent the preceding hour. */
public final class WeatherAlertEvaluator {
    public static final long HOUR = 3600000L;

    private WeatherAlertEvaluator() { }

    public static int firstMatch(long[] times, double[] values, long start, long end,
                                 long now, double threshold, boolean below, boolean accumulation) {
        if (times.length != values.length || start > end || !Double.isFinite(threshold)) {
            throw new IllegalArgumentException("Invalid forecast or interval");
        }
        long from = Math.max(start, now);
        for (int i = 0; i < times.length; i++) {
            if (times[i] < from || times[i] > end || !Double.isFinite(values[i])) {
                continue;
            }
            if (accumulation && times[i] <= from) {
                continue;
            }
            if (below ? values[i] <= threshold : values[i] >= threshold) {
                return i;
            }
        }
        return -1;
    }

    public static boolean covers(long[] times, double[] values, long start, long end, long now) {
        if (times.length != values.length || times.length == 0) {
            return false;
        }
        long from = Math.max(start, now);
        long first = ((from + HOUR - 1) / HOUR) * HOUR;
        long last = (end / HOUR) * HOUR;
        if (first > last) {
            return false;
        }
        long expected = first;
        for (int i = 0; i < times.length && expected <= last; i++) {
            if (times[i] < expected) {
                continue;
            }
            if (times[i] != expected || !Double.isFinite(values[i])) {
                return false;
            }
            expected += HOUR;
        }
        return expected > last;
    }
}
