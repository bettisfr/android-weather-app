package it.skynow.app.astronomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.Predicate;

public final class SkyTrajectory {
    private static final int STEP_SECONDS = 120;
    private static final int HORIZON_SEARCH_STEPS = 26 * 60 * 60 / STEP_SECONDS;

    public static class Point {
        public final long time;
        public final double azimuth, elevation;
        final boolean plotted;

        public Point(long time, double azimuth, double elevation) {
            this(time, azimuth, elevation, elevation >= 0);
        }

        private Point(long time, double azimuth, double elevation, boolean plotted) {
            this.time = time;
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.plotted = plotted;
        }
    }

    public final List<Point> points;
    public final Point rise, peak, set, now;
    public final boolean continuous;

    private SkyTrajectory(List<Point> points, Point rise, Point peak, Point set, Point now, boolean continuous) {
        this.points = points;
        this.rise = rise;
        this.peak = peak;
        this.set = set;
        this.now = now;
        this.continuous = continuous;
    }

    public static SkyTrajectory today(Instant now, ZoneId zone, double latitude, double longitude,
                                      boolean moon, Planets.Body planet) {
        LongFunction<Point> sample = time -> positionAt(time, latitude, longitude, moon, planet);
        LocalDate date = now.atZone(zone).toLocalDate();
        long start = date.atStartOfDay(zone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
        Point current = sample.apply(now.getEpochSecond());
        Point rise = null;
        Point set = null;
        boolean nightOnly = moon || planet != null;
        if (planet != null && current.elevation >= 0) {
            rise = findHorizon(sample, current, -STEP_SECONDS);
            set = findHorizon(sample, current, STEP_SECONDS);
            if (rise != null) start = rise.time;
            if (set != null) end = set.time;
        }

        Predicate<Point> visible = point -> point.elevation >= 0 && (!nightOnly
                || SkyPosition.at(Instant.ofEpochSecond(point.time), latitude, longitude, false).zenith > 90);
        List<Point> points = new ArrayList<>();
        Point previous = rise == null ? sample.apply(start) : rise;
        boolean previousVisible = visible.test(previous);
        Point firstVisible = previousVisible ? previous : null;
        Point lastVisible = firstVisible;
        Point peak = firstVisible;
        int segments = previousVisible ? 1 : 0;
        points.add(rendered(previous, previousVisible));
        while (previous.time < end) {
            long time = Math.min(end, previous.time + STEP_SECONDS);
            Point next = set != null && time == set.time ? set : sample.apply(time);
            boolean nextVisible = visible.test(next);
            if (previousVisible != nextVisible) {
                Point crossing = boundary(sample, visible, previous, next, previousVisible);
                points.add(crossing);
                if (peak == null || crossing.elevation > peak.elevation) peak = crossing;
                if (nextVisible) {
                    if (firstVisible == null) firstVisible = crossing;
                    segments++;
                    if (!nightOnly) rise = crossing;
                } else {
                    lastVisible = crossing;
                    if (!nightOnly) set = crossing;
                }
            }
            points.add(rendered(next, nextVisible));
            if (nextVisible) {
                lastVisible = next;
                if (peak == null || next.elevation > peak.elevation) peak = next;
            }
            previous = next;
            previousVisible = nextVisible;
        }
        if (peak != null) {
            long peakTime = peak.time;
            for (long t = Math.max(start, peakTime - 119); t <= Math.min(end, peakTime + 119); t += 5) {
                Point candidate = sample.apply(t);
                if (candidate.elevation > peak.elevation && visible.test(candidate)) peak = candidate;
            }
        }
        return new SkyTrajectory(points, nightOnly ? firstVisible : rise, peak, nightOnly ? lastVisible : set,
                visible.test(current) ? current : null, segments == 1);
    }

    public static SkyTrajectory lunarPass(Instant now, ZoneId zone, double latitude, double longitude,
                                          Instant moonrise, Instant moonset) {
        LongFunction<Point> sample = second -> positionAt(second, latitude, longitude, true, null);
        Point current = sample.apply(now.getEpochSecond());
        Point rise = moonrise == null ? null : atHorizon(sample.apply(moonrise.getEpochSecond()));
        Point set = moonset == null ? null : atHorizon(sample.apply(moonset.getEpochSecond()));
        if (current.elevation >= 0) {
            if (rise == null) rise = findHorizon(sample, current, -STEP_SECONDS);
            if (set == null) set = findHorizon(sample, current, STEP_SECONDS);
        }
        LocalDate date = now.atZone(zone).toLocalDate();
        long start = rise == null ? date.atStartOfDay(zone).toEpochSecond() : rise.time;
        long end = set == null ? date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1 : set.time;
        long step = Math.max(STEP_SECONDS, (end - start) / 480);
        List<Point> points = new ArrayList<>();
        Point peak = null;
        for (long time = start; ; time = Math.min(end, time + step)) {
            Point point = time == start && rise != null ? rise
                    : time == end && set != null ? set : sample.apply(time);
            points.add(point);
            if (point.elevation >= 0 && (peak == null || point.elevation > peak.elevation)) peak = point;
            if (time == end) break;
        }
        return new SkyTrajectory(points, rise, peak, set, current.elevation >= 0 ? current : atHorizon(current), true);
    }

    private static Point atHorizon(Point point) {
        return new Point(point.time, point.azimuth, 0);
    }

    public static Point positionAt(long second, double latitude, double longitude,
                                   boolean moon, Planets.Body planet) {
        Instant time = Instant.ofEpochSecond(second);
        if (planet != null) {
            Planets.Reading reading = Planets.at(planet, time, latitude, longitude);
            return new Point(second, reading.azimuth, reading.elevation);
        }
        SkyPosition position = SkyPosition.at(time, latitude, longitude, moon);
        return new Point(second, position.azimuth, 90 - position.zenith);
    }

    private static Point rendered(Point point, boolean visible) {
        return visible == point.plotted ? point : new Point(point.time, point.azimuth, point.elevation, visible);
    }

    private static Point boundary(LongFunction<Point> sample, Predicate<Point> visible,
                                  Point low, Point high, boolean lowVisible) {
        while (high.time - low.time > 1) {
            Point mid = sample.apply((low.time + high.time) / 2);
            if (visible.test(mid) == lowVisible) low = mid;
            else high = mid;
        }
        return lowVisible ? low : high;
    }

    private static Point findHorizon(LongFunction<Point> sample, Point current, int step) {
        Point above = current;
        for (int i = 0; i < HORIZON_SEARCH_STEPS; i++) {
            Point next = sample.apply(above.time + step);
            if (next.elevation < 0) {
                Predicate<Point> aboveHorizon = p -> p.elevation >= 0;
                return step < 0 ? boundary(sample, aboveHorizon, next, above, false)
                        : boundary(sample, aboveHorizon, above, next, true);
            }
            above = next;
        }
        return null;
    }

}
