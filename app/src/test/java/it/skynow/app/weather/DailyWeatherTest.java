package it.skynow.app.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DailyWeatherTest {
    private static final String DAY = "2024-06-21";

    /** 24 hours of {@link #DAY} with daylight from 06:00 to 20:00. */
    private static JSONObject day(Object... codes) throws Exception {
        JSONArray time = new JSONArray();
        JSONArray weather = new JSONArray();
        JSONArray isDay = new JSONArray();
        for (int hour = 0; hour < codes.length; hour++) {
            time.put(String.format("%sT%02d:00", DAY, hour));
            weather.put(codes[hour]);
            isDay.put(hour >= 6 && hour <= 20 ? 1 : 0);
        }
        return new JSONObject().put("time", time).put("weather_code", weather).put("is_day", isDay);
    }

    private static Object[] repeat(int code) {
        Object[] codes = new Object[24];
        java.util.Arrays.fill(codes, code);
        return codes;
    }

    @Test public void predominantDaylightCodeWins() throws Exception {
        Object[] codes = repeat(0);         // clear at night (10 hours)
        for (int h = 6; h <= 20; h++) codes[h] = 2; // partly cloudy all day (15 hours)
        DailyWeather summary = DailyWeather.summarize(day(codes), 0, DAY, -1);
        assertEquals(2, summary.code);
        assertFalse(summary.night);
        assertEquals(0, summary.eventCode);
        assertNull(summary.eventTime);
    }

    @Test public void daylightOutweighsMoreFrequentNightCodes() throws Exception {
        Object[] codes = repeat(3);          // overcast night: 9 hours
        for (int h = 6; h <= 20; h++) codes[h] = h < 14 ? 1 : 0; // 8 h mostly clear, 7 h clear
        assertEquals(1, DailyWeather.summarize(day(codes), 0, DAY, -1).code);
    }

    @Test public void mostSevereOtherEventIsReported() throws Exception {
        Object[] codes = repeat(2);
        codes[9] = 61;   // rain
        codes[15] = 95;  // thunderstorm
        codes[17] = 73;  // snow
        DailyWeather summary = DailyWeather.summarize(day(codes), 0, DAY, -1);
        assertEquals(95, summary.eventCode);
        assertEquals(DAY + "T15:00", summary.eventTime);
    }

    @Test public void eventsBeforeTheStartHourAreIgnored() throws Exception {
        Object[] codes = repeat(2);
        codes[3] = 95;
        codes[18] = 61;
        DailyWeather summary = DailyWeather.summarize(day(codes), 12, DAY, -1);
        assertEquals(61, summary.eventCode);
        assertEquals(DAY + "T18:00", summary.eventTime);
    }

    @Test public void eventOfTheSameKindAsTheDayIsNotNoteworthy() throws Exception {
        Object[] codes = repeat(61);
        codes[10] = 63;
        assertEquals(0, DailyWeather.summarize(day(codes), 0, DAY, -1).eventCode);
    }

    @Test public void polarNightIsMarkedAsNight() throws Exception {
        JSONObject hourly = day(repeat(1));
        JSONArray dark = new JSONArray();
        for (int i = 0; i < 24; i++) dark.put(0);
        hourly.put("is_day", dark);
        DailyWeather summary = DailyWeather.summarize(hourly, 0, DAY, -1);
        assertEquals(1, summary.code);
        assertTrue(summary.night);
    }

    @Test public void missingCodesFallBack() throws Exception {
        Object[] codes = new Object[24];
        java.util.Arrays.fill(codes, JSONObject.NULL);
        DailyWeather summary = DailyWeather.summarize(day(codes), 0, DAY, 45);
        assertEquals(45, summary.code);
        assertFalse(summary.night);
        assertEquals(7, DailyWeather.summarize(day(repeat(0)), 0, "2024-06-22", 7).code);
    }

    @Test public void eventGroupsAreOrderedBySeverity() {
        assertEquals(0, DailyWeather.eventGroup(3));
        assertEquals(1, DailyWeather.eventGroup(45));
        assertEquals(2, DailyWeather.eventGroup(61));
        assertEquals(2, DailyWeather.eventGroup(81));
        assertEquals(3, DailyWeather.eventGroup(75));
        assertEquals(3, DailyWeather.eventGroup(86));
        assertEquals(4, DailyWeather.eventGroup(66));
        assertEquals(5, DailyWeather.eventGroup(99));
    }

    @Test public void hourlySummaryIgnoresMissingReadings() throws Exception {
        JSONObject hourly = day(repeat(0));
        JSONArray wind = new JSONArray();
        for (int i = 0; i < 24; i++) wind.put(i == 5 ? JSONObject.NULL : (Object) (double) i);
        hourly.put("wind_speed_10m", wind);
        assertEquals(23, DailyWeather.hourlySummary(hourly, "wind_speed_10m", DAY, true), 0);
        assertEquals((276 - 5) / 23.0, DailyWeather.hourlySummary(hourly, "wind_speed_10m", DAY, false), 1e-9);
        assertTrue(Double.isNaN(DailyWeather.hourlySummary(hourly, "missing", DAY, true)));
        assertTrue(Double.isNaN(DailyWeather.hourlySummary(hourly, "wind_speed_10m", "2024-06-22", false)));
    }

    @Test public void remainingMaximumStartsAtTheGivenHour() throws Exception {
        JSONObject hourly = day(repeat(0));
        JSONArray chance = new JSONArray();
        for (int i = 0; i < 24; i++) chance.put(i == 4 ? 90 : i == 20 ? 40 : 10);
        hourly.put("precipitation_probability", chance);
        assertEquals(90, DailyWeather.remainingMaximum(hourly, "precipitation_probability", 0, DAY), 0);
        assertEquals(40, DailyWeather.remainingMaximum(hourly, "precipitation_probability", 12, DAY), 0);
        assertEquals(Double.NEGATIVE_INFINITY,
                DailyWeather.remainingMaximum(hourly, "precipitation_probability", 24, DAY), 0);
    }
}
