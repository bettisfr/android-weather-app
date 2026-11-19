package it.meteo.locale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class Planets {
    // Orbital elements, perturbations and photometry from Paul Schlyter's low-precision model.
    // https://www.stjarnhimlen.se/comp/ppcomp.html
    enum Body {
        MERCURY(48.3313, 3.24587e-5, 7.0047, 5e-8, 29.1241, 1.01444e-5, .387098, .205635, 5.59e-10, 168.6562, 4.0923344368),
        VENUS(76.6799, 2.46590e-5, 3.3946, 2.75e-8, 54.8910, 1.38374e-5, .723330, .006773, -1.302e-9, 48.0052, 1.6021302244),
        MARS(49.5574, 2.11081e-5, 1.8497, -1.78e-8, 286.5016, 2.92961e-5, 1.523688, .093405, 2.516e-9, 18.6021, .5240207766),
        JUPITER(100.4542, 2.76854e-5, 1.3030, -1.557e-7, 273.8777, 1.64505e-5, 5.20256, .048498, 4.469e-9, 19.8950, .0830853001),
        SATURN(113.6634, 2.38980e-5, 2.4886, -1.081e-7, 339.3939, 2.97661e-5, 9.55475, .055546, -9.499e-9, 316.9670, .0334442282);

        private final double node, nodeRate, inclination, inclinationRate, perihelion, perihelionRate;
        private final double semiMajor, eccentricity, eccentricityRate, anomaly, anomalyRate;

        Body(double node, double nodeRate, double inclination, double inclinationRate, double perihelion,
                double perihelionRate, double semiMajor, double eccentricity, double eccentricityRate,
                double anomaly, double anomalyRate) {
            this.node = node;
            this.nodeRate = nodeRate;
            this.inclination = inclination;
            this.inclinationRate = inclinationRate;
            this.perihelion = perihelion;
            this.perihelionRate = perihelionRate;
            this.semiMajor = semiMajor;
            this.eccentricity = eccentricity;
            this.eccentricityRate = eccentricityRate;
            this.anomaly = anomaly;
            this.anomalyRate = anomalyRate;
        }

        private double meanAnomaly(double days) {
            return Math.toRadians((anomaly + anomalyRate * days) % 360);
        }
    }

    static final class Reading {
        final double azimuth, elevation, magnitude;
        final boolean favorable;

        Reading(SkyPosition sky, double magnitude, double elongation, double sunElevation) {
            azimuth = sky.azimuth;
            elevation = 90 - sky.zenith;
            this.magnitude = magnitude;
            // Clear-sky rule of thumb. Bright planets can be seen earlier in twilight.
            favorable = elevation >= 5 && elongation >= 10 && magnitude <= 5
                    && sunElevation <= (magnitude <= -2 ? -3 : -6);
        }
    }

    static final class Window {
        final Instant start, end;

        Window(long start, long end) {
            this.start = Instant.ofEpochSecond(start);
            this.end = Instant.ofEpochSecond(end);
        }
    }

    private Planets() { }

    static Reading at(Body body, Instant now, double latitude, double longitude) {
        double days = SkyPosition.daysSinceEpoch(now);
        double sunAnomaly = Math.toRadians((356.0470 + .9856002585 * days) % 360);
        double sunPerihelion = Math.toRadians(282.9404 + 4.70935e-5 * days);
        double sunMeanLongitude = sunAnomaly + sunPerihelion;
        double sunEccentricity = .016709 - 1.151e-9 * days;
        double sunEccentric = SkyPosition.eccentricAnomaly(sunAnomaly, sunEccentricity);
        double solarX = Math.cos(sunEccentric) - sunEccentricity;
        double solarY = Math.sqrt(1 - sunEccentricity * sunEccentricity) * Math.sin(sunEccentric);
        double sunX = solarX * Math.cos(sunPerihelion) - solarY * Math.sin(sunPerihelion);
        double sunY = solarX * Math.sin(sunPerihelion) + solarY * Math.cos(sunPerihelion);

        double eccentricity = body.eccentricity + body.eccentricityRate * days;
        double eccentric = SkyPosition.eccentricAnomaly(body.meanAnomaly(days), eccentricity);
        double orbitX = Math.cos(eccentric) - eccentricity;
        double orbitY = Math.sqrt(1 - eccentricity * eccentricity) * Math.sin(eccentric);
        double distance = body.semiMajor * Math.hypot(orbitX, orbitY);
        double argument = Math.atan2(orbitY, orbitX) + Math.toRadians(body.perihelion + body.perihelionRate * days);
        double node = Math.toRadians(body.node + body.nodeRate * days);
        double inclination = Math.toRadians(body.inclination + body.inclinationRate * days);
        double planetLongitude = node + Math.atan2(Math.sin(argument) * Math.cos(inclination), Math.cos(argument));
        double planetLatitude = Math.asin(Math.sin(argument) * Math.sin(inclination));
        if (body == Body.JUPITER || body == Body.SATURN) {
            double jupiter = Body.JUPITER.meanAnomaly(days);
            double saturn = Body.SATURN.meanAnomaly(days);
            double mainTerm = Math.sin(2 * jupiter - 5 * saturn - Math.toRadians(67.6));
            if (body == Body.JUPITER) {
                planetLongitude += Math.toRadians(-.332 * mainTerm
                        - .056 * Math.sin(2 * jupiter - 2 * saturn + Math.toRadians(21))
                        + .042 * Math.sin(3 * jupiter - 5 * saturn + Math.toRadians(21))
                        - .036 * Math.sin(jupiter - 2 * saturn) + .022 * Math.cos(jupiter - saturn)
                        + .023 * Math.sin(2 * jupiter - 3 * saturn + Math.toRadians(52))
                        - .016 * Math.sin(jupiter - 5 * saturn - Math.toRadians(69)));
            } else {
                double secondaryTerm = 2 * jupiter - 4 * saturn - Math.toRadians(2);
                planetLongitude += Math.toRadians(.812 * mainTerm - .229 * Math.cos(secondaryTerm)
                        + .119 * Math.sin(jupiter - 2 * saturn - Math.toRadians(3))
                        + .046 * Math.sin(2 * jupiter - 6 * saturn - Math.toRadians(69))
                        + .014 * Math.sin(jupiter - 3 * saturn + Math.toRadians(32)));
                planetLatitude += Math.toRadians(-.020 * Math.cos(secondaryTerm)
                        + .018 * Math.sin(2 * jupiter - 6 * saturn - Math.toRadians(49)));
            }
        }

        double x = distance * Math.cos(planetLatitude) * Math.cos(planetLongitude) + sunX;
        double y = distance * Math.cos(planetLatitude) * Math.sin(planetLongitude) + sunY;
        double z = distance * Math.sin(planetLatitude);
        double range = Math.hypot(Math.hypot(x, y), z);
        double sunRange = Math.hypot(sunX, sunY);
        double phase = angleBetween(distance, range, sunRange);
        double elongation = angleBetween(sunRange, range, distance);
        double magnitude = magnitude(body, phase, distance, range, days, x, y, z);
        SkyPosition sky = SkyPosition.fromEcliptic(x, y, z, days, sunMeanLongitude, latitude, longitude, 4.2635e-5);
        SkyPosition sun = SkyPosition.fromEcliptic(sunX, sunY, 0, days, sunMeanLongitude, latitude, longitude, 0);
        return new Reading(sky, magnitude, elongation, 90 - sun.zenith);
    }

    static List<Window> windows(Body body, LocalDate date, ZoneId zone, double latitude, double longitude) {
        long start = date.atStartOfDay(zone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        List<Window> windows = new ArrayList<>();
        boolean favorable = at(body, Instant.ofEpochSecond(start), latitude, longitude).favorable;
        long open = start;
        for (long previous = start; previous < end; ) {
            long next = Math.min(previous + 300, end);
            boolean upcoming = at(body, Instant.ofEpochSecond(next), latitude, longitude).favorable;
            if (upcoming != favorable) {
                long lower = previous;
                long upper = next;
                while (upper - lower > 30) {
                    long midpoint = (lower + upper) / 2;
                    if (at(body, Instant.ofEpochSecond(midpoint), latitude, longitude).favorable == favorable) lower = midpoint;
                    else upper = midpoint;
                }
                long boundary = Math.min(end, ((upper + 30) / 60) * 60);
                if (upcoming) open = boundary;
                else if (boundary > open) windows.add(new Window(open, boundary));
                favorable = upcoming;
            }
            previous = next;
        }
        if (favorable && end > open) windows.add(new Window(open, end));
        return windows;
    }

    private static double angleBetween(double a, double b, double opposite) {
        double cosine = (a * a + b * b - opposite * opposite) / (2 * a * b);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
    }

    private static double magnitude(Body body, double phase, double distance, double range, double days,
            double x, double y, double z) {
        double correction = 5 * Math.log10(distance * range);
        switch (body) {
            case MERCURY: return -.36 + correction + .027 * phase + 2.2e-13 * Math.pow(phase, 6);
            case VENUS: return -4.34 + correction + .013 * phase + 4.2e-7 * Math.pow(phase, 3);
            case MARS: return -1.51 + correction + .016 * phase;
            case JUPITER: return -9.25 + correction + .014 * phase;
            case SATURN:
                double ringInclination = Math.toRadians(28.06);
                double ringNode = Math.toRadians(169.51 + 3.82e-5 * days);
                double tilt = (z * Math.cos(ringInclination)
                        - (y * Math.cos(ringNode) - x * Math.sin(ringNode)) * Math.sin(ringInclination)) / range;
                return -9 + correction + .044 * phase - 2.6 * Math.abs(tilt) + 1.2 * tilt * tilt;
            default: throw new AssertionError(body);
        }
    }
}
