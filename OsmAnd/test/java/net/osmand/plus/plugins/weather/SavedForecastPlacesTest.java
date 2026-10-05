package net.osmand.plus.plugins.weather;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import net.osmand.plus.plugins.weather.enums.WeatherSource;

public class SavedForecastPlacesTest {
    private ForecastPlace place(String id, String name, WeatherSource source) {
        return new ForecastPlace(id, name, 39.9, 116.4, source);
    }

    @Test public void roundTripRetainsNamesCoordinatesAndIndependentSources() {
        List<ForecastPlace> original = Arrays.asList(place("a", "小五台\n\"营地\"", WeatherSource.ICON),
                place("b", "北京 & 山口", WeatherSource.GEM));
        assertEquals(original, SavedForecastPlaces.INSTANCE.decode(SavedForecastPlaces.INSTANCE.encode(original)));
    }

    @Test public void renamingUpdatesOnlyTheMatchingIdWithoutDuplicatingOrReordering() {
        ForecastPlace first = place("a", "Camp", WeatherSource.ICON);
        ForecastPlace second = place("b", "Pass", WeatherSource.GEM);
        List<ForecastPlace> original = Arrays.asList(first, second);
        ForecastPlace renamed = place("a", "New camp", WeatherSource.GEM);
        List<ForecastPlace> updated = SavedForecastPlaces.INSTANCE.replace(original, renamed);
        assertEquals(Arrays.asList(renamed, second), updated);
        assertEquals(Arrays.asList(first, second), original);
    }

    @Test public void sameCoordinateCanHaveSeparateNamesAndModels() {
        ForecastPlace a = place("a", "Camp ICON", WeatherSource.ICON);
        ForecastPlace b = place("b", "Camp GEM", WeatherSource.GEM);
        assertEquals(2, SavedForecastPlaces.INSTANCE.replace(Collections.singletonList(a), b).size());
    }

    @Test public void nativeModelsCanBeSavedForPointForecasts() {
        List<ForecastPlace> original = Arrays.asList(place("g", "GFS camp", WeatherSource.GFS), place("e", "ECMWF camp", WeatherSource.ECMWF));
        assertEquals(original, SavedForecastPlaces.INSTANCE.decode(SavedForecastPlaces.INSTANCE.encode(original)));
    }

    @Test public void invalidCoordinateIsRejected() {
        try {
            new ForecastPlace("a", "Camp", Double.NaN, 116, WeatherSource.ICON);
            fail("Invalid coordinate accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
