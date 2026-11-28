package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class SkyTrajectoryTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final double LAT = 41.9;
    private static final double LON = 12.5;

    @Test public void sunPathOnTheSummerSolstice() {
        SkyTrajectory sun = SkyTrajectory.today(Instant.parse("2024-06-21T10:00:00Z"), ROME, LAT, LON, null);
        assertNotNull(sun.rise);
        assertNotNull(sun.set);
        assertTrue(sun.continuous);
        assertTrue(sun.rise.time < sun.peak.time && sun.peak.time < sun.set.time);
        // Geometric horizon crossings (no refraction): published times are 05:36 and 20:48 CEST.
        assertClose(LocalTime.of(5, 36), sun.rise.time, 15);
        assertClose(LocalTime.of(20, 48), sun.set.time, 15);
        assertEquals(71.5, sun.peak.elevation, .5);
        assertEquals(0, sun.rise.elevation, .05);
        assertEquals(0, sun.set.elevation, .05);
        assertNonDecreasing(sun.points);
    }

    @Test public void lunarPassAcrossMidnightIsContinuous() {
        // Shortly after the full moon of 25 January 2024 the Moon is high at local midnight:
        // it rose on the 25th and sets on the morning of the 26th.
        Instant now = Instant.parse("2024-01-25T23:30:00Z");
        SkyTrajectory moon = SkyTrajectory.lunarPass(now, ROME, LAT, LON, null, null);
        assertNotNull(moon.rise);
        assertNotNull(moon.set);
        assertEquals(LocalDate.of(2024, 1, 25), localDate(moon.rise.time));
        assertEquals(LocalDate.of(2024, 1, 26), localDate(moon.set.time));
        assertTrue(moon.rise.time < now.getEpochSecond() && now.getEpochSecond() < moon.set.time);

        List<SkyTrajectory.Point> points = moon.points;
        assertSame(moon.rise, points.get(0));
        assertSame(moon.set, points.get(points.size() - 1));
        assertNonDecreasing(points);
        for (SkyTrajectory.Point point : points) {
            assertTrue("Below horizon at " + point.time, point.elevation >= 0);
        }
        assertTrue(moon.peak.elevation > 50);
        assertTrue(moon.continuous);
    }

    @Test public void providedLunarHorizonTimesAreUsedAsEndpoints() {
        Instant now = Instant.parse("2024-01-25T23:30:00Z");
        SkyTrajectory searched = SkyTrajectory.lunarPass(now, ROME, LAT, LON, null, null);
        // Published rise and set times include refraction, so they fall a few minutes outside
        // the geometric crossings of the low-precision model.
        Instant rise = Instant.ofEpochSecond(searched.rise.time - 600);
        Instant set = Instant.ofEpochSecond(searched.set.time + 600);
        SkyTrajectory given = SkyTrajectory.lunarPass(now, ROME, LAT, LON, rise, set);
        assertEquals(rise.getEpochSecond(), given.points.get(0).time);
        assertEquals(set.getEpochSecond(), given.points.get(given.points.size() - 1).time);
        assertEquals(0, given.rise.elevation, 0);
        assertEquals(0, given.set.elevation, 0);
        for (SkyTrajectory.Point point : given.points) {
            assertTrue("Path dips below the horizon at " + point.time, point.elevation >= 0);
        }
        assertNonDecreasing(given.points);
    }

    @Test public void moonBelowHorizonWithoutTimesDrawsTheCalendarDay() {
        // New moon: the Moon is below the horizon at local midnight.
        Instant now = Instant.parse("2024-01-10T23:30:00Z");
        SkyTrajectory moon = SkyTrajectory.lunarPass(now, ROME, LAT, LON, null, null);
        assertEquals(LocalDate.of(2024, 1, 11), localDate(moon.points.get(0).time));
        assertEquals(0, moon.now.elevation, 0);
        assertNonDecreasing(moon.points);
    }

    // Longyearbyen, Svalbard: in late November 2026 the Moon stays up from the 26th until it sets
    // on the 30th at 14:20 UTC.
    private static final double SVALBARD_LAT = 78.2232;
    private static final double SVALBARD_LON = 15.6267;
    private static final ZoneId SVALBARD = ZoneId.of("Arctic/Longyearbyen");

    @Test public void circumpolarMoonIsDrawnForOneLunarDay() {
        Instant now = Instant.parse("2026-11-28T12:00:00Z");
        Instant set = Instant.parse("2026-11-30T14:20:00Z");
        SkyTrajectory moon = SkyTrajectory.lunarPass(now, SVALBARD, SVALBARD_LAT, SVALBARD_LON, null, set);
        List<SkyTrajectory.Point> points = moon.points;
        long first = points.get(0).time;
        long last = points.get(points.size() - 1).time;
        assertEquals(SkyTrajectory.LUNAR_DAY_SECONDS, last - first);
        assertTrue(first < now.getEpochSecond() && now.getEpochSecond() < last);
        // Neither end of the window is a real rise or set.
        assertNull(moon.rise);
        assertNull(moon.set);
        assertTrue("Circuits: " + circuits(points), circuits(points) <= 1.02);
        assertNonDecreasing(points);
        for (SkyTrajectory.Point point : points) assertTrue(point.elevation >= 0);
    }

    @Test public void longPassKeepsARealSetInsideTheWindow() {
        // Up for nine days; at 05:00 the set at 11:20 falls inside the lunar-day window.
        Instant rise = Instant.parse("2026-12-19T06:37:00Z");
        Instant set = Instant.parse("2026-12-28T11:20:00Z");
        Instant now = Instant.parse("2026-12-28T05:00:00Z");
        SkyTrajectory moon = SkyTrajectory.lunarPass(now, SVALBARD, SVALBARD_LAT, SVALBARD_LON, rise, set);
        assertNull(moon.rise);
        assertNotNull(moon.set);
        assertEquals(set.getEpochSecond(), moon.set.time);
        assertSame(moon.set, moon.points.get(moon.points.size() - 1));
        assertEquals(set.getEpochSecond() - SkyTrajectory.LUNAR_DAY_SECONDS, moon.points.get(0).time);
    }

    /** Net turns of azimuth along the track. */
    private static double circuits(List<SkyTrajectory.Point> points) {
        double sweep = 0;
        for (int i = 1; i < points.size(); i++) {
            double step = points.get(i).azimuth - points.get(i - 1).azimuth;
            sweep += ((step + 540) % 360) - 180;
        }
        return Math.abs(sweep) / 360;
    }

    private static void assertNonDecreasing(List<SkyTrajectory.Point> points) {
        for (int i = 1; i < points.size(); i++) {
            assertTrue("Time goes backwards at " + i, points.get(i).time >= points.get(i - 1).time);
        }
    }

    private static LocalDate localDate(long second) {
        return Instant.ofEpochSecond(second).atZone(ROME).toLocalDate();
    }

    private static void assertClose(LocalTime expected, long second, int minutes) {
        LocalTime actual = Instant.ofEpochSecond(second).atZone(ROME).toLocalTime();
        long difference = Math.abs(actual.toSecondOfDay() - expected.toSecondOfDay());
        assertTrue("Expected about " + expected + " but was " + actual, difference <= minutes * 60L);
    }
}
