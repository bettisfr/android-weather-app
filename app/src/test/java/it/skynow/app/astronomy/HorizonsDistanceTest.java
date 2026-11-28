package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.Test;

public class HorizonsDistanceTest {
    private static final double AU_KM = 149597870.7;

    /** Hourly Horizons ranges around the perigee of 24 December 2026, 07:00–10:00 UTC. */
    private static HorizonsDistance fixture() throws Exception {
        return fixture("/horizons-moon-range.json");
    }

    private static HorizonsDistance fixture(String name) throws Exception {
        try (InputStream stream = HorizonsDistanceTest.class.getResourceAsStream(name)) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return HorizonsDistance.parse(new JSONObject(json).getString("result"));
        }
    }

    @Test public void readsHourlyRows() throws Exception {
        HorizonsDistance table = fixture();
        assertEquals(0.00238407368406 * AU_KM, table.kmAt(Instant.parse("2026-12-24T07:00:00Z")), 1e-6);
        assertEquals(0.00238406448996 * AU_KM, table.kmAt(Instant.parse("2026-12-24T10:00:00Z")), 1e-6);
    }

    @Test public void interpolationIsWithinAKilometreAtPerigee() throws Exception {
        // Horizons queried directly for 08:31 gives 0.00238404247767 au; the DE440 apsides table
        // lists the perigee at 08:31 UTC as 356,650 km.
        double km = fixture().kmAt(Instant.parse("2026-12-24T08:31:00Z"));
        assertEquals(0.00238404247767 * AU_KM, km, 1);
        assertEquals(356_650, km, 2);
    }

    @Test public void sunDistanceMatchesThePerihelionTable() throws Exception {
        // The DE440 apsides table lists perihelion at 2027-01-03 02:32:43 UTC, 147,104,592 km.
        HorizonsDistance sun = fixture("/horizons-sun-range.json");
        assertEquals(147_104_592, sun.kmAt(Instant.parse("2027-01-03T02:32:43Z")), 50);
    }

    @Test public void outsideTheTableIsUnknown() throws Exception {
        HorizonsDistance table = fixture();
        assertFalse(table.covers(Instant.parse("2026-12-24T06:59:00Z")));
        assertTrue(table.covers(Instant.parse("2026-12-24T09:59:00Z")));
        assertTrue(Double.isNaN(table.kmAt(Instant.parse("2026-12-24T10:01:00Z"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void errorResponsesAreRejected() {
        HorizonsDistance.parse("Cannot interpret date. Type \"?!\" or try YYYY-MM-DD.");
    }
}
