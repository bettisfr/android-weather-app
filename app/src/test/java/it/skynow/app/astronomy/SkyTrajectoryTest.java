package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
