package com.peaknav.utils;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class MountainIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void downloadsStayInTheirOwnRoot() throws Exception {
        File root = temporary.newFolder("terrain");
        MountainStorage.initialize(root);
        assertEquals(new File(root, "elev_tiles/a.png").getCanonicalFile(), MountainStorage.external("elev_tiles/a.png").file());
        assertEquals(root.getCanonicalFile(), MountainStorage.external(".").file());
    }
    @Test public void traversalCannotReachHostMaps() throws Exception {
        MountainStorage.initialize(temporary.newFolder("terrain"));
        assertThrows(IllegalArgumentException.class, () -> MountainStorage.external("../maps/beijing.obf"));
    }
    @Test public void aFileCannotBecomeTheDataRoot() throws Exception {
        assertThrows(IllegalStateException.class, () -> MountainStorage.initialize(temporary.newFile()));
    }
    @Test public void selectedPointPreservesLatitudeLongitudeAndZero() {
        assertArrayEquals(new double[]{39.95, 115.05}, CoordinateLinks.parseGeoUri("geo:0,0?q=39.95,115.05"), 0);
        assertArrayEquals(new double[]{0, 0}, CoordinateLinks.parseGeoUri("geo:0,0?q=0,0"), 0);
    }
    @Test public void invalidCoordinatesAreRejected() {
        assertNull(CoordinateLinks.parseGeoUri("geo:NaN,115"));
        assertNull(CoordinateLinks.parseGeoUri("geo:91,115"));
        assertNull(CoordinateLinks.parseGeoUri("geo:39,181"));
    }
}
