package it.skynow.app.astronomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

public final class SkyTrajectory {
    private static final int STEP_SECONDS = 120;
    private static final int MOON_SEARCH_STEPS = 26 * 60 * 60 / STEP_SECONDS;

    public static class Point {
        public final long time;
        public final double azimuth, elevation;

        Point(long time, double azimuth, double elevation) {
            this.time = time;
            this.azimuth = azimuth;
            this.elevation = elevation;
        }
    }

    public final List<Point> points;
    public final Point rise, peak, set, now;

    private SkyTrajectory(List<Point> points, Point rise, Point peak, Point set, Point now) {
        this.points = points;
        this.rise = rise;
        this.peak = peak;
        this.set = set;
        this.now = now;
    }

    public static SkyTrajectory today(Instant now, ZoneId zone, double latitude, double longitude,
                                      boolean moon, Planets.Body planet) {
        LongFunction<Point> sample = time -> {
            Instant instant = Instant.ofEpochSecond(time);
            if (planet != null) {
                Planets.Reading reading = Planets.at(planet, instant, latitude, longitude);
                return new Point(time, reading.azimuth, reading.elevation);
            }
            SkyPosition position = SkyPosition.at(instant, latitude, longitude, moon);
            return new Point(time, position.azimuth, 90 - position.zenith);
        };
        LocalDate date = now.atZone(zone).toLocalDate();
        long start = date.atStartOfDay(zone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
        Point current = sample.apply(now.getEpochSecond());
        Point rise = null;
        Point set = null;
        if (moon && current.elevation >= 0) {
            rise = findHorizon(sample, current, -STEP_SECONDS);
            set = findHorizon(sample, current, STEP_SECONDS);
            if (rise != null) start = rise.time;
            if (set != null) end = set.time;
        }

        List<Point> points = new ArrayList<>();
        Point previous = rise == null ? sample.apply(start) : rise;
        Point peak = previous;
        points.add(previous);
        while (previous.time < end) {
            long time = Math.min(end, previous.time + STEP_SECONDS);
            Point next = set != null && time == set.time ? set : sample.apply(time);
            if ((previous.elevation >= 0) != (next.elevation >= 0)) {
                Point crossing = horizon(sample, previous, next);
                points.add(crossing);
                if (next.elevation >= 0) rise = crossing;
                else set = crossing;
            }
            points.add(next);
            if (next.elevation > peak.elevation) peak = next;
            previous = next;
        }
        long peakTime = peak.time;
        for (long t = Math.max(start, peakTime - 119); t <= Math.min(end, peakTime + 119); t += 5) {
            Point candidate = sample.apply(t);
            if (candidate.elevation > peak.elevation) peak = candidate;
        }
        return new SkyTrajectory(points, rise, peak, set, current);
    }

    private static Point findHorizon(LongFunction<Point> sample, Point current, int step) {
        Point above = current;
        for (int i = 0; i < MOON_SEARCH_STEPS; i++) {
            Point next = sample.apply(above.time + step);
            if (next.elevation < 0) {
                return step < 0 ? horizon(sample, next, above) : horizon(sample, above, next);
            }
            above = next;
        }
        return null;
    }

    private static Point horizon(LongFunction<Point> sample, Point low, Point high) {
        boolean descending = low.elevation >= 0;
        while (high.time - low.time > 1) {
            Point mid = sample.apply((low.time + high.time) / 2);
            if ((mid.elevation >= 0) == descending) low = mid;
            else high = mid;
        }
        Point closest = descending ? low : high;
        return new Point(closest.time, closest.azimuth, 0);
    }
}
