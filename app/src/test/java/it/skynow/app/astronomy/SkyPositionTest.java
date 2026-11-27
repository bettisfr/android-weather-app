package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public class SkyPositionTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Test public void solarDeclinationAtSolsticeAndEquinox() {
        assertEquals(23.44, SkyPosition.solarDeclinationDegrees(Instant.parse("2024-06-20T20:51:00Z")), .05);
        assertEquals(0, SkyPosition.solarDeclinationDegrees(Instant.parse("2024-03-20T03:06:00Z")), .05);
        assertEquals(-23.44, SkyPosition.solarDeclinationDegrees(Instant.parse("2024-12-21T09:20:00Z")), .05);
    }

    @Test public void equatorialDaylightIsJustOverTwelveHours() {
        // Refraction and the solar radius add about seven minutes at the equinox.
        long minutes = SkyPosition.daylightMinutes(LocalDate.of(2024, 3, 20), ZoneOffset.UTC, 0);
        assertEquals(727, minutes, 3);
    }

    @Test public void polarDayAndNight() {
        assertEquals(1440, SkyPosition.daylightMinutes(LocalDate.of(2024, 6, 21), ZoneOffset.UTC, 80));
        assertEquals(0, SkyPosition.daylightMinutes(LocalDate.of(2024, 12, 21), ZoneOffset.UTC, 80));
    }

    @Test public void romeSummerSolsticeNoonElevation() {
        // 90° − 41.9° + 23.44°, without atmospheric refraction.
        double elevation = SkyPosition.maximumSolarElevation(LocalDate.of(2024, 6, 21), ROME, 41.9, 12.5);
        assertEquals(71.54, elevation, .3);
    }

    @Test public void sunIsSouthAtLocalNoonInRome() {
        // Solar noon in Rome on 21 June 2024 is about 13:13 CEST.
        SkyPosition sun = SkyPosition.at(Instant.parse("2024-06-21T11:13:00Z"), 41.9, 12.5, false);
        assertEquals(180, sun.azimuth, 3);
        assertEquals(1, sun.distanceAu, .02);
    }

    @Test public void moonDistanceIsPlausible() {
        SkyPosition moon = SkyPosition.at(Instant.parse("2024-01-25T18:00:00Z"), 41.9, 12.5, true);
        double km = moon.distanceAu * SkyPosition.AU_KM;
        assertTrue("Moon distance " + km, km > 350_000 && km < 410_000);
    }

    @Test public void rotationSpeedAtEquatorAndPole() {
        assertEquals(1674.4, SkyPosition.rotationSpeedKmh(0), .5);
        assertEquals(0, SkyPosition.rotationSpeedKmh(90), 1e-6);
    }
}
