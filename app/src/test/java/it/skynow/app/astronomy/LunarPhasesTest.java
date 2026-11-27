package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public class LunarPhasesTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    // Reference instants (UTC) from published lunar phase tables, chosen several hours away from midnight.
    @Test public void findsEachQuarterOfJanuary2024() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        assertEquals(LocalDate.of(2024, 1, 11), LunarPhases.nextDate(from, UTC, 0)); // 11:57
        assertEquals(LocalDate.of(2024, 1, 18), LunarPhases.nextDate(from, UTC, 1)); // 03:53
        assertEquals(LocalDate.of(2024, 1, 25), LunarPhases.nextDate(from, UTC, 2)); // 17:54
        assertEquals(LocalDate.of(2024, 1, 4), LunarPhases.nextDate(from, UTC, 3));  // 03:30
    }

    @Test public void fullMoonFlagMatchesQuarterTwo() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        assertEquals(LunarPhases.nextDate(from, ROME, 2), LunarPhases.nextDate(from, ROME, true));
        assertEquals(LunarPhases.nextDate(from, ROME, 0), LunarPhases.nextDate(from, ROME, false));
    }

    @Test public void includesThePhaseOnTheStartDate() {
        LocalDate fullMoon = LocalDate.of(2024, 1, 25);
        assertEquals(fullMoon, LunarPhases.nextDate(fullMoon, UTC, 2));
        assertEquals(LocalDate.of(2024, 2, 24), LunarPhases.nextDate(fullMoon.plusDays(1), UTC, 2));
    }

    @Test public void reportsTheLocalCalendarDate() {
        // Full moon 2024-08-19 18:26 UTC is already 20 August in New Zealand.
        LocalDate from = LocalDate.of(2024, 8, 10);
        assertEquals(LocalDate.of(2024, 8, 19), LunarPhases.nextDate(from, UTC, 2));
        assertEquals(LocalDate.of(2024, 8, 20), LunarPhases.nextDate(from, ZoneId.of("Pacific/Auckland"), 2));
    }

    @Test public void eclipseNewMoonOfApril2024() {
        // Total solar eclipse, new moon at 18:21 UTC.
        assertEquals(LocalDate.of(2024, 4, 8), LunarPhases.nextDate(LocalDate.of(2024, 3, 30), UTC, 0));
    }
}
