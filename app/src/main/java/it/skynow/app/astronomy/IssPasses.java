package it.skynow.app.astronomy;

import android.content.Context;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import sgp4.SGP4;
import sgp4.TLE;

// SGP4 from aholinch/sgp4 (552cb148), with the license in assets/licenses/sgp4.txt.
public final class IssPasses {
    private static final double EARTH_RADIUS_KM = 6378.137;
    private static final double EARTH_ECCENTRICITY_SQUARED = 0.00669437999014;
    private static final int STEP = 20;
    // Estimated visual magnitude at a range of 1,000 km and a phase angle of 90 degrees.
    private static final double STANDARD_MAGNITUDE = -1.3;

    public static List<Pass> upcoming(Context context, double latitude, double longitude,
                                      double elevationMeters, Instant now) throws IOException {
        TLE orbit = IssOrbitalElements.get(context, now);
        Observer observer = new Observer(latitude, longitude, elevationMeters, orbit);
        List<Pass> passes = new ArrayList<>();
        long start = now.getEpochSecond();
        long end = start + Duration.ofDays(7).getSeconds();
        Sample previous = observer.at(start);
        // Recover the start and the full track when the app opens mid-pass.
        if (previous.visible) {
            for (long time = start - STEP; time >= start - 20 * 60; time -= STEP) {
                Sample before = observer.at(time);
                if (!before.visible) {
                    previous = observer.boundary(before, previous);
                    break;
                }
                previous = before;
            }
        }
        Sample rise = previous.visible ? previous : null;
        Sample peak = rise;
        Sample brightest = rise;
        for (long time = previous.time + STEP; time <= end; time += STEP) {
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
                if (set.time > rise.time) {
                    List<Sample> track = new ArrayList<>();
                    track.add(rise);
                    for (long second = rise.time + 5; second < set.time; second += 5) track.add(observer.at(second));
                    track.add(set);
                    passes.add(new Pass(rise, peak, set, brightest, track));
                }
                rise = null;
            }
            previous = current;
        }
        return passes;
    }

    public static final class Pass {
        public final Sample rise, peak, set, brightest;
        public final double magnitude;
        public final List<Sample> track;

        Pass(Sample rise, Sample peak, Sample set, Sample brightest, List<Sample> track) {
            this.rise = rise;
            this.peak = peak;
            this.set = set;
            this.brightest = brightest;
            this.magnitude = brightest.magnitude;
            this.track = track;
        }

        public double[] notificationTrack() {
            double[] encoded = new double[(track.size() + 4) * 4];
            List<Sample> points = new ArrayList<>();
            java.util.Collections.addAll(points, rise, peak, set, brightest);
            points.addAll(track);
            int index = 0;
            for (Sample point : points) {
                encoded[index++] = point.time;
                encoded[index++] = point.azimuth;
                encoded[index++] = point.elevation;
                encoded[index++] = point.magnitude;
            }
            return encoded;
        }

        public static Pass fromNotificationTrack(double[] encoded) {
            if (encoded == null || encoded.length < 24 || encoded.length > 2000 || encoded.length % 4 != 0) return null;
            List<Sample> points = new ArrayList<>();
            for (int i = 0; i < encoded.length; i += 4) {
                points.add(new Sample((long) encoded[i], encoded[i + 1], encoded[i + 2], encoded[i + 3]));
            }
            return new Pass(points.get(0), points.get(1), points.get(2), points.get(3),
                    new ArrayList<>(points.subList(4, points.size())));
        }

        public static Pass simulated(long start) {
            Sample rise = new Sample(start, 285, 10, -0.2);
            Sample peak = new Sample(start + 90, 345, 57, -2.6);
            Sample brightest = new Sample(start + 105, 15, 51, -3.1);
            Sample set = new Sample(start + 190, 80, 12, 0.0);
            return new Pass(rise, peak, set, brightest, java.util.Arrays.asList(rise,
                    new Sample(start + 40, 310, 28, -1.2), peak, brightest,
                    new Sample(start + 150, 52, 30, -1.4), set));
        }

        public boolean visibleAt(long second) {
            return second >= rise.time && second <= set.time;
        }

        public SkyTrajectory.Point positionAt(long second) {
            if (!visibleAt(second)) return null;
            for (int i = 1; i < track.size(); i++) {
                Sample after = track.get(i);
                if (after.time < second) continue;
                Sample before = track.get(i - 1);
                double fraction = (double) (second - before.time) / (after.time - before.time);
                double azimuth = before.azimuth + Math.IEEEremainder(after.azimuth - before.azimuth, 360) * fraction;
                double elevation = before.elevation + (after.elevation - before.elevation) * fraction;
                return new SkyTrajectory.Point(second, (azimuth + 360) % 360, elevation);
            }
            return null;
        }
    }

    public static final class Sample extends SkyTrajectory.Point {
        final double magnitude;
        final boolean visible;

        Sample(long time, double azimuth, double elevation, double magnitude) {
            super(time, azimuth, elevation);
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
