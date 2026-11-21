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
        for (long time = start + STEP; time <= end; time += STEP) {
            Sample current = observer.at(time);
            if (current.visible) {
                if (rise == null) {
                    rise = observer.boundary(previous, current);
                    peak = rise;
                }
                if (current.elevation > peak.elevation) peak = current;
            } else if (rise != null) {
                Sample set = observer.boundary(previous, current);
                long low = Math.max(rise.time, peak.time - STEP);
                long high = Math.min(set.time, peak.time + STEP);
                for (long second = low; second <= high; second++) {
                    Sample candidate = observer.at(second);
                    if (candidate.visible && candidate.elevation > peak.elevation) peak = candidate;
                }
                if (set.time > rise.time) passes.add(new Pass(rise, peak, set));
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

        Pass(Sample rise, Sample peak, Sample set) {
            this.rise = rise;
            this.peak = peak;
            this.set = set;
        }
    }

    static final class Sample {
        final long time;
        final double azimuth, elevation;
        final boolean visible;

        Sample(long time, double azimuth, double elevation, boolean visible) {
            this.time = time;
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.visible = visible;
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
            boolean visible = elevation >= 10 && SkyPosition.at(time, latitude, longitude, false).zenith >= 96
                    && sunlit(time, sx, sy, sz);
            return new Sample(second, azimuth, elevation, visible);
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

    private static boolean sunlit(Instant time, double x, double y, double z) {
        // At the equator on Greenwich, local up/east/north are the Earth-fixed X/Y/Z axes.
        SkyPosition sun = SkyPosition.at(time, 0, 0, false);
        double zenith = Math.toRadians(sun.zenith);
        double azimuth = Math.toRadians(sun.azimuth);
        double alongSun = x * Math.cos(zenith) + Math.sin(zenith) * (y * Math.sin(azimuth) + z * Math.cos(azimuth));
        return alongSun >= 0 || x * x + y * y + z * z - alongSun * alongSun >= EARTH_RADIUS_KM * EARTH_RADIUS_KM;
    }

    private IssPasses() {}
}
