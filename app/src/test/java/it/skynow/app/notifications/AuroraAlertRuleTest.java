package it.skynow.app.notifications;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public class AuroraAlertRuleTest {
    private static final double REYKJAVIK_LAT = 64.1466;
    private static final double REYKJAVIK_LON = -21.9426;
    private static final long HOUR = 60 * 60 * 1000L;

    @Test public void alertsWhenThePlaceComesUnderTheOval() {
        long now = 100 * HOUR;
        assertTrue(AuroraAlertRule.shouldNotify(true, false, now, 0));
        // Still covered at the next check: no repeat.
        assertFalse(AuroraAlertRule.shouldNotify(true, true, now, 0));
        assertFalse(AuroraAlertRule.shouldNotify(false, false, now, 0));
        assertFalse(AuroraAlertRule.shouldNotify(false, true, now, 0));
    }

    @Test public void staysQuietForSixHoursAfterAnAlert() {
        long alerted = 100 * HOUR;
        assertFalse(AuroraAlertRule.shouldNotify(true, false, alerted + 5 * HOUR, alerted));
        assertTrue(AuroraAlertRule.shouldNotify(true, false, alerted + 6 * HOUR, alerted));
    }

    @Test public void needsANauticalTwilightSky() {
        // Reykjavik in December: dark at midnight, daylight around solar noon (~13:30 UTC).
        assertTrue(AuroraAlertRule.darkEnough(Instant.parse("2026-12-15T00:00:00Z"), REYKJAVIK_LAT, REYKJAVIK_LON));
        assertFalse(AuroraAlertRule.darkEnough(Instant.parse("2026-12-15T13:30:00Z"), REYKJAVIK_LAT, REYKJAVIK_LON));
        // Midsummer: the Sun never gets 6° below the horizon.
        assertFalse(AuroraAlertRule.darkEnough(Instant.parse("2026-06-21T01:30:00Z"), REYKJAVIK_LAT, REYKJAVIK_LON));
    }
}
