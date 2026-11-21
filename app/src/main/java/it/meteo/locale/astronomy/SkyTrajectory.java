package it.meteo.locale.astronomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

public final class SkyTrajectory {
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
        List<Point> points = new ArrayList<>();
        Point previous = sample.apply(start);
        Point peak = previous;
        Point rise = null;
        Point set = null;
        points.add(previous);
        while (previous.time < end) {
            Point next = sample.apply(Math.min(end, previous.time + 120));
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
        return new SkyTrajectory(points, rise, peak, set, sample.apply(now.getEpochSecond()));
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
