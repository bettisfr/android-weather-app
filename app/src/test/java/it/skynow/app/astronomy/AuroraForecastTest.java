package it.skynow.app.astronomy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AuroraForecastTest {
    private static final String OBSERVED = "2026-11-27T21:00:00Z";
    private static final String FORECAST = "2026-11-27T21:45:00Z";

    /** An OVATION response whose points are [longitude 0–359, latitude, activity]. */
    private static AuroraForecast ovation(int[]... points) throws Exception {
        JSONArray grid = new JSONArray();
        for (int[] point : points) grid.put(new JSONArray().put(point[0]).put(point[1]).put(point[2]));
        return AuroraForecast.parse(new JSONObject()
                .put("Observation Time", OBSERVED)
                .put("Forecast Time", FORECAST)
                .put("coordinates", grid));
    }

    @Test public void readsTimes() throws Exception {
        AuroraForecast forecast = ovation();
        assertEquals(Instant.parse(OBSERVED), forecast.observationTime);
        assertEquals(Instant.parse(FORECAST), forecast.forecastTime);
    }

    @Test public void staleForecastsAreNotCurrent() throws Exception {
        AuroraForecast forecast = ovation();
        assertTrue(forecast.isCurrentAt(Instant.parse("2026-11-27T22:10:00Z")));
        // More than 30 minutes past the forecast time.
        assertFalse(forecast.isCurrentAt(Instant.parse("2026-11-27T22:20:00Z")));
    }

    @Test public void mapCoverageUsesSignedLongitudes() throws Exception {
        // 340° east is 20° west, over the Atlantic near Iceland.
        AuroraForecast forecast = ovation(new int[]{340, 65, 30}, new int[]{18, 69, 4});
        assertTrue(forecast.coversOnMap(65, -20));
        assertFalse(forecast.coversOnMap(65, 20));
        // Activity below the map threshold is not drawn.
        assertFalse(forecast.coversOnMap(69, 18));
        assertFalse(forecast.coversOnMap(89, 0));
        assertFalse(forecast.coversOnMap(65, Double.NaN));
    }

    @Test public void invalidPointsAreIgnored() throws Exception {
        AuroraForecast forecast = ovation(new int[]{400, 65, 50}, new int[]{10, 95, 50}, new int[]{10, 60, 120});
        assertFalse(forecast.coversOnMap(60, 10));
    }

    @Test public void directionPointsTowardTheNearestBrightOval() throws Exception {
        // Observer in Abisko; a bright patch due north and a dimmer one to the east.
        AuroraForecast forecast = ovation(new int[]{19, 72, 60}, new int[]{30, 68, 15});
        assertEquals(0, forecast.estimatedDirection(68.35, 18.83), 5);
        // Overhead activity reports -1 instead of a bearing.
        assertEquals(-1, ovation(new int[]{19, 68, 60}).estimatedDirection(68.35, 18.83), 0);
        assertTrue(Double.isNaN(ovation(new int[]{19, 50, 60}).estimatedDirection(68.35, 18.83)));
    }

    /** Rows of SWPC's noaa-planetary-k-index-forecast.json as published on 28 November 2026. */
    private static JSONArray forecastRows() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("time_tag", "2026-11-28T15:00:00").put("kp", 3.67).put("observed", "observed"))
                .put(new JSONObject().put("time_tag", "2026-11-28T18:00:00").put("kp", 2.67).put("observed", "estimated"))
                .put(new JSONObject().put("time_tag", "2026-11-28T21:00:00").put("kp", 3.67).put("observed", "estimated"))
                .put(new JSONObject().put("time_tag", "2026-11-29T00:00:00").put("kp", 3.67).put("observed", "predicted"))
                .put(new JSONObject().put("time_tag", "2026-11-29T03:00:00").put("kp", 10).put("observed", "predicted"));
    }

    @Test public void currentKpIsTheIntervalInProgress() throws Exception {
        AuroraForecast.KpReading reading = AuroraForecast.parseCurrentKp(forecastRows(), Instant.parse("2026-11-28T18:27:00Z"));
        assertEquals(Instant.parse("2026-11-28T18:00:00Z"), reading.time);
        assertEquals(Instant.parse("2026-11-28T21:00:00Z"), reading.end());
        assertEquals(2.67, reading.kp, 0);
        // At the boundary the new interval takes over.
        assertEquals(3.67, AuroraForecast.parseCurrentKp(forecastRows(), Instant.parse("2026-11-28T21:00:00Z")).kp, 0);
        assertNull(AuroraForecast.parseCurrentKp(forecastRows(), Instant.parse("2026-12-05T00:00:00Z")));
    }

    @Test public void forecastSkipsObservedAndInvalidPeriods() throws Exception {
        List<AuroraForecast.KpPeriod> periods = AuroraForecast.parseKp(forecastRows());
        assertEquals(3, periods.size());
        assertEquals(Instant.parse("2026-11-28T18:00:00Z"), periods.get(0).start);
        assertEquals(Instant.parse("2026-11-28T21:00:00Z"), periods.get(0).end());
        assertEquals(3.67, periods.get(2).kp, 0);
    }
}
