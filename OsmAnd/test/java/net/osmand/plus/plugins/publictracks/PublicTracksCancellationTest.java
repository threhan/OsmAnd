package net.osmand.plus.plugins.publictracks;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class PublicTracksCancellationTest {
    @Test public void cancelledQueryDoesNotReportAnEmptySuccessfulResult() {
        PublicTracksStore store = new PublicTracksStore(new File("missing-cancelled-track-fixture.sqlite"));
        Thread.currentThread().interrupt();
        try {
            store.query(null, "route", null);
            fail("Cancelled work must not publish an empty success");
        } catch (Exception expected) {
            assertTrue(expected instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
