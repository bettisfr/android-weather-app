package it.skynow.app.astronomy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

final class SolarEclipses {
    static final int LAST_YEAR = 2100;
    private static final double EARTH_ECCENTRICITY_SQUARED = .00669437999014;
    private static final double HORIZON = Math.sin(Math.toRadians(-.833));
    private static NavigableMap<LocalDate, Elements> catalog;

    private SolarEclipses() {}

    static synchronized NavigableMap<LocalDate, Elements> catalog(Context context)
            throws IOException, JSONException {
        if (catalog != null) return catalog;
        Map<String, Integer> hours = new HashMap<>();
        try (BufferedReader reader = reader(context, "solar-eclipse-epochs.txt")) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split(" ");
                hours.put(fields[0], Integer.parseInt(fields[1]));
            }
        }
        NavigableMap<LocalDate, Elements> loaded = new TreeMap<>();
        try (BufferedReader reader = reader(context, "radar/eclipse-elements.js")) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("    \"")) continue;
                String date = line.substring(5, 15);
                Integer hour = hours.get(date);
                if (hour == null) continue;
                LocalDate day = LocalDate.parse(date);
                JSONArray row = new JSONArray(line.substring(line.indexOf('['), line.lastIndexOf(']') + 1));
                loaded.put(day, new Elements(day, hour, row));
            }
        }
        if (loaded.size() != hours.size()) throw new IOException("Incomplete solar eclipse catalog");
        catalog = loaded;
        return catalog;
    }

    private static BufferedReader reader(Context context, String asset) throws IOException {
        return new BufferedReader(new InputStreamReader(context.getAssets().open(asset), StandardCharsets.UTF_8));
    }

    static final class Elements {
        private final LocalDate date;
        private final double epochUtc, first, last;
        private final double[] x, y, declination, hourAngle, l1, l2;
        private final double partialSlope, centralSlope, deltaT;

        Elements(LocalDate date, int hour, JSONArray row) throws JSONException {
            this.date = date;
            deltaT = row.getDouble(3);
            epochUtc = date.toEpochDay() * 86400.0 + hour * 3600 - deltaT;
            first = row.getDouble(4) * 3600;
            last = row.getDouble(5) * 3600;
            x = coefficients(row, 6);
            y = coefficients(row, 7);
            declination = coefficients(row, 8);
            hourAngle = coefficients(row, 9);
            l1 = coefficients(row, 10);
            l2 = coefficients(row, 11);
            partialSlope = row.getDouble(12);
            centralSlope = row.getDouble(13);
        }

        EclipseCatalog.Event at(double latitude, double longitude) {
            Observer observer = new Observer(latitude, longitude);
            DoubleUnaryOperator partial = time -> sample(time, observer).partialMargin();
            double closest = minimum(partial, first, last);
            if (partial.applyAsDouble(closest) >= 0) return null;
            double start = contact(partial, first, closest);
            double end = contact(partial, last, closest);

            DoubleUnaryOperator separation = time -> sample(time, observer).relativeSeparation();
            double peak = minimum(separation, start, end);
            DoubleUnaryOperator horizon = time -> sample(time, observer).horizon;
            double visibleStart = Double.NaN;
            double visibleEnd = Double.NaN;
            double best = Double.NaN;
            double bestCoverage = -1;
            double segmentStart = horizon.applyAsDouble(start) >= 0 ? start : Double.NaN;
            double previous = start;
            boolean previousAbove = Double.isFinite(segmentStart);
            for (double time = Math.min(end, start + 30); time > previous; time = Math.min(end, time + 30)) {
                boolean above = horizon.applyAsDouble(time) >= 0;
                if (above && !previousAbove) segmentStart = contact(horizon, previous, time);
                if ((!above && previousAbove) || (above && time == end)) {
                    double segmentEnd = above ? end : contact(horizon, time, previous);
                    double maximum = Math.max(segmentStart, Math.min(peak, segmentEnd));
                    double coverage = sample(maximum, observer).coverage();
                    if (coverage > bestCoverage) {
                        visibleStart = segmentStart;
                        visibleEnd = segmentEnd;
                        best = maximum;
                        bestCoverage = coverage;
                    }
                }
                previous = time;
                previousAbove = above;
            }
            if (!Double.isFinite(best) || Math.round(visibleEnd - visibleStart) < 1) return null;

            Sample maximum = sample(best, observer);
            long totality = 0;
            long annularity = 0;
            String type = "Partial solar eclipse";
            DoubleUnaryOperator central = time -> sample(time, observer).centralMargin();
            double nearAxis = minimum(central, visibleStart, visibleEnd);
            if (central.applyAsDouble(nearAxis) < 0) {
                long duration = Math.round(contact(central, visibleEnd, nearAxis)
                        - contact(central, visibleStart, nearAxis));
                if (sample(nearAxis, observer).difference < 0) {
                    type = "Total solar eclipse";
                    totality = duration;
                } else {
                    type = "Annular solar eclipse";
                    annularity = duration;
                }
            }
            boolean clipped = visibleStart - start > .5 || end - visibleEnd > .5;
            return new EclipseCatalog.Event(type, utc(visibleStart), utc(best), utc(visibleEnd), clipped,
                    false, false, Math.abs(best - peak) > .5, bestCoverage, totality, annularity,
                    maximum.horizontal / maximum.sunRadius(), maximum.vertical / maximum.sunRadius(),
                    maximum.moonRadius() / maximum.sunRadius(), date, java.util.Collections.emptyList());
        }

        private long utc(double seconds) {
            return Math.round(epochUtc + seconds);
        }

        private Sample sample(double seconds, Observer observer) {
            double hour = seconds / 3600;
            double dec = Math.toRadians(polynomial(declination, hour));
            double angle = Math.toRadians(polynomial(hourAngle, hour) - deltaT * 360 / 86164) + observer.longitude;
            double sinD = Math.sin(dec), cosD = Math.cos(dec);
            double sinH = Math.sin(angle), cosH = Math.cos(angle);
            double meridian = observer.horizontal * cosH;
            double zeta = observer.vertical * sinD + meridian * cosD;
            double dx = polynomial(x, hour) - observer.horizontal * sinH;
            double dy = polynomial(y, hour) - (observer.vertical * cosD - meridian * sinD);
            double sum = polynomial(l1, hour) - zeta * partialSlope;
            double difference = polynomial(l2, hour) - zeta * centralSlope;
            double altitude = observer.sinLatitude * sinD + observer.cosLatitude * cosH * cosD;
            // Rotate the offsets from the fundamental plane into the observer's local sky.
            double rotation = Math.atan2(observer.cosLatitude * sinH,
                    observer.sinLatitude * cosD - observer.cosLatitude * sinD * cosH);
            return new Sample(dx * Math.cos(rotation) - dy * Math.sin(rotation),
                    dx * Math.sin(rotation) + dy * Math.cos(rotation), sum, difference, altitude - HORIZON);
        }
    }

    private static final class Observer {
        final double horizontal, vertical, longitude, sinLatitude, cosLatitude;

        Observer(double latitude, double longitude) {
            double phi = Math.toRadians(latitude);
            this.longitude = Math.toRadians(longitude);
            sinLatitude = Math.sin(phi);
            cosLatitude = Math.cos(phi);
            double normal = 1 / Math.sqrt(1 - EARTH_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
            horizontal = cosLatitude * normal;
            vertical = (1 - EARTH_ECCENTRICITY_SQUARED) * sinLatitude * normal;
        }
    }

    private static final class Sample {
        final double horizontal, vertical, sum, difference, horizon, separation;

        Sample(double horizontal, double vertical, double sum, double difference, double horizon) {
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.sum = sum;
            this.difference = difference;
            this.horizon = horizon;
            separation = Math.hypot(horizontal, vertical);
        }

        double sunRadius() { return (sum + difference) / 2; }
        double moonRadius() { return (sum - difference) / 2; }
        double relativeSeparation() { return separation / sunRadius(); }
        double partialMargin() { return separation - sum; }
        double centralMargin() { return separation - Math.abs(difference); }
        double coverage() { return EclipseCatalog.diskCoverage(sunRadius(), moonRadius(), separation); }
    }

    private static double[] coefficients(JSONArray row, int index) throws JSONException {
        JSONArray values = row.getJSONArray(index);
        double[] result = new double[values.length()];
        for (int i = 0; i < result.length; i++) result[i] = values.getDouble(i);
        return result;
    }

    private static double polynomial(double[] coefficients, double hour) {
        double result = 0;
        for (int i = coefficients.length - 1; i >= 0; i--) result = result * hour + coefficients[i];
        return result;
    }

    private static double minimum(DoubleUnaryOperator value, double start, double end) {
        double left = start;
        double right = end;
        while (right - left > .05) {
            double third = (right - left) / 3;
            double a = left + third, b = right - third;
            if (value.applyAsDouble(a) <= value.applyAsDouble(b)) right = b;
            else left = a;
        }
        double middle = (left + right) / 2;
        if (value.applyAsDouble(start) <= value.applyAsDouble(middle)) return start;
        return value.applyAsDouble(end) <= value.applyAsDouble(middle) ? end : middle;
    }

    // Root between an outside sample and an inside sample (in either time order).
    private static double contact(DoubleUnaryOperator value, double outside, double inside) {
        boolean insideNegative = value.applyAsDouble(inside) < 0;
        if ((value.applyAsDouble(outside) < 0) == insideNegative) return outside;
        while (Math.abs(inside - outside) > .05) {
            double middle = (outside + inside) / 2;
            if ((value.applyAsDouble(middle) < 0) == insideNegative) inside = middle;
            else outside = middle;
        }
        return (inside + outside) / 2;
    }
}
