package it.meteo.locale;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import sgp4.SGP4;
import sgp4.TLE;

// SGP4 from aholinch/sgp4 (552cb148), with the license in assets/licenses/sgp4.txt.
final class IssPasses {
    static final String SOURCE = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE";
    private static final double EARTH_RADIUS_KM = 6378.137;
    private static final double EARTH_ECCENTRICITY_SQUARED = 0.00669437999014;
    private static final int STEP = 20;
    // Estimated visual magnitude at a range of 1,000 km and a phase angle of 90 degrees.
    private static final double STANDARD_MAGNITUDE = -1.3;
    private static String[] cachedLines;
    private static Instant fetchedAt = Instant.EPOCH;

    static List<Pass> upcoming(double latitude, double longitude, double elevationMeters, Instant now) throws IOException {
        TLE orbit = orbit(now);
        Observer observer = new Observer(latitude, longitude, elevationMeters, orbit);
        List<Pass> passes = new ArrayList<>();
        long start = now.getEpochSecond();
        long end = start + Duration.ofDays(7).getSeconds();
        Sample previous = observer.at(start);
        Sample rise = previous.visible ? previous : null;
        Sample peak = rise;
        Sample brightest = rise;
        for (long time = start + STEP; time <= end; time += STEP) {
            Sample current = observer.at(time);
            if (current.visible) {
                if (rise == null) {
                    rise = observer.boundary(previous, current);
                    peak = rise;
                    brightest = rise;
                }
                if (current.elevation > peak.elevation) peak = current;
                if (current.magnitude < brightest.magnitude) brightest = current;
            } else if (rise != null) {
                Sample set = observer.boundary(previous, current);
                long low = Math.max(rise.time, peak.time - STEP);
                long high = Math.min(set.time, peak.time + STEP);
                for (long second = low; second <= high; second++) {
                    Sample candidate = observer.at(second);
                    if (candidate.visible && candidate.elevation > peak.elevation) peak = candidate;
                }
                low = Math.max(rise.time, brightest.time - STEP);
                high = Math.min(set.time, brightest.time + STEP);
                for (long second = low; second <= high; second++) {
                    Sample candidate = observer.at(second);
                    if (candidate.magnitude < brightest.magnitude) brightest = candidate;
                }
                if (set.time > rise.time) passes.add(new Pass(rise, peak, set, brightest.magnitude));
                rise = null;
            }
            previous = current;
        }
        return passes;
    }

    private static synchronized TLE orbit(Instant now) throws IOException {
        if (cachedLines == null || Duration.between(fetchedAt, now).compareTo(Duration.ofMinutes(30)) >= 0) {
            String[] lines = OpenMeteo.readBody(SOURCE).trim().split("\\r?\\n");
            if (lines.length < 3 || !lines[1].startsWith("1 25544") || !lines[2].startsWith("2 25544")
                    || lines[1].length() < 69 || lines[2].length() < 69) throw new IOException("Invalid ISS orbital elements");
            cachedLines = lines;
            fetchedAt = now;
        }
        TLE orbit = new TLE(cachedLines[1], cachedLines[2]);
        if (orbit.getParseErrors() != null || orbit.getEpoch() == null
                || Duration.between(orbit.getEpoch().toInstant(), now).abs().compareTo(Duration.ofDays(3)) > 0) {
            throw new IOException("ISS orbital elements are stale or invalid");
        }
        return orbit;
    }

    static final class Pass {
        final Sample rise, peak, set;
        final double magnitude;

        Pass(Sample rise, Sample peak, Sample set, double magnitude) {
            this.rise = rise;
            this.peak = peak;
            this.set = set;
            this.magnitude = magnitude;
        }
    }

    static final class Sample {
        final long time;
        final double azimuth, elevation, magnitude;
        final boolean visible;

        Sample(long time, double azimuth, double elevation, double magnitude) {
            this.time = time;
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.magnitude = magnitude;
            this.visible = Double.isFinite(magnitude);
        }
    }

    private static final class Observer {
        final double latitude, longitude, sinLat, cosLat, sinLon, cosLon;
        final double x, y, z;
        final TLE orbit;

        Observer(double latitude, double longitude, double elevationMeters, TLE orbit) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.orbit = orbit;
            sinLat = Math.sin(Math.toRadians(latitude));
            cosLat = Math.cos(Math.toRadians(latitude));
            sinLon = Math.sin(Math.toRadians(longitude));
            cosLon = Math.cos(Math.toRadians(longitude));
            double radius = EARTH_RADIUS_KM / Math.sqrt(1 - EARTH_ECCENTRICITY_SQUARED * sinLat * sinLat);
            double height = Double.isFinite(elevationMeters) ? elevationMeters / 1000 : 0;
            x = (radius + height) * cosLat * cosLon;
            y = (radius + height) * cosLat * sinLon;
            z = (radius * (1 - EARTH_ECCENTRICITY_SQUARED) + height) * sinLat;
        }

        Sample at(long second) throws IOException {
            double[] position = orbit.getRV((second * 1000.0 - orbit.getEpoch().getTime()) / 60000)[0];
            if (orbit.getSgp4Error() != 0) throw new IOException("Could not propagate ISS orbit");
            // Rotate TEME positions into Earth-fixed coordinates with sidereal time (UTC approximates UT1).
            double angle = SGP4.gstime(2440587.5 + second / 86400.0);
            double sx = position[0] * Math.cos(angle) + position[1] * Math.sin(angle);
            double sy = -position[0] * Math.sin(angle) + position[1] * Math.cos(angle);
            double sz = position[2];
            double dx = sx - x, dy = sy - y, dz = sz - z;
            double meridian = dx * cosLon + dy * sinLon;
            double east = -dx * sinLon + dy * cosLon;
            double north = dz * cosLat - meridian * sinLat;
            double up = meridian * cosLat + dz * sinLat;
            double elevation = Math.toDegrees(Math.atan2(up, Math.hypot(east, north)));
            double azimuth = (Math.toDegrees(Math.atan2(east, north)) + 360) % 360;
            Instant time = Instant.ofEpochSecond(second);
            double magnitude = elevation >= 10 && SkyPosition.at(time, latitude, longitude, false).zenith >= 96
                    ? apparentMagnitude(time, sx, sy, sz, dx, dy, dz) : Double.POSITIVE_INFINITY;
            return new Sample(second, azimuth, elevation, magnitude);
        }

        Sample boundary(Sample before, Sample after) throws IOException {
            while (after.time - before.time > 1) {
                Sample midpoint = at((before.time + after.time) / 2);
                if (midpoint.visible == before.visible) before = midpoint;
                else after = midpoint;
            }
            return before.visible ? before : after;
        }
    }

    private static double apparentMagnitude(Instant time, double x, double y, double z,
                                            double dx, double dy, double dz) {
        // At the equator on Greenwich, local up/east/north are the Earth-fixed X/Y/Z axes.
        SkyPosition sun = SkyPosition.at(time, 0, 0, false);
        double zenith = Math.toRadians(sun.zenith);
        double azimuth = Math.toRadians(sun.azimuth);
        double sunX = Math.cos(zenith);
        double sunY = Math.sin(zenith) * Math.sin(azimuth);
        double sunZ = Math.sin(zenith) * Math.cos(azimuth);
        double alongSun = x * sunX + y * sunY + z * sunZ;
        if (alongSun < 0 && x * x + y * y + z * z - alongSun * alongSun < EARTH_RADIUS_KM * EARTH_RADIUS_KM) {
            return Double.POSITIVE_INFINITY;
        }
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cosine = Math.max(-1, Math.min(1, -(dx * sunX + dy * sunY + dz * sunZ) / range));
        double phase = Math.acos(cosine);
        // Lambert-sphere phase function, normalized to unity at the reference 90-degree phase.
        double phaseFactor = Math.sin(phase) + (Math.PI - phase) * cosine;
        return STANDARD_MAGNITUDE + 5 * Math.log10(range / 1000) - 2.5 * Math.log10(Math.max(1e-6, phaseFactor));
    }

    private IssPasses() {}
}
