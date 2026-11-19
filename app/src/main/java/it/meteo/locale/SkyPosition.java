package it.meteo.locale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class SkyPosition {
    private static final double EPOCH_SECONDS = Instant.parse("1999-12-31T00:00:00Z").getEpochSecond();
    final double azimuth;
    final double zenith;

    private SkyPosition(double azimuth, double zenith) {
        this.azimuth = azimuth;
        this.zenith = zenith;
    }

    // Low-precision orbital elements and lunar perturbations from Paul Schlyter.
    // https://www.stjarnhimlen.se/comp/ppcomp.html
    static SkyPosition at(Instant now, double latitude, double longitude, boolean moon) {
        double days = (now.getEpochSecond() - EPOCH_SECONDS) / 86400;
        double sunAnomaly = Math.toRadians(normalize(356.0470 + 0.9856002585 * days));
        double sunPerihelion = Math.toRadians(282.9404 + 4.70935e-5 * days);
        double sunMeanLongitude = sunAnomaly + sunPerihelion;
        double eclipticLongitude;
        double eclipticLatitude = 0;
        double distance = 1;
        if (moon) {
            double node = Math.toRadians(normalize(125.1228 - 0.0529538083 * days));
            double inclination = Math.toRadians(5.1454);
            double periapsis = Math.toRadians(normalize(318.0634 + 0.1643573223 * days));
            double anomaly = Math.toRadians(normalize(115.3654 + 13.0649929509 * days));
            double eccentricity = 0.054900;
            double eccentric = eccentricAnomaly(anomaly, eccentricity);
            double orbitalX = Math.cos(eccentric) - eccentricity;
            double orbitalY = Math.sqrt(1 - eccentricity * eccentricity) * Math.sin(eccentric);
            double argument = Math.atan2(orbitalY, orbitalX) + periapsis;
            double argumentOfLatitude = anomaly + periapsis;
            double elongation = argumentOfLatitude + node - sunMeanLongitude;
            double evection = anomaly - 2 * elongation;
            eclipticLongitude = node + Math.atan2(Math.sin(argument) * Math.cos(inclination), Math.cos(argument))
                    + Math.toRadians(-1.274 * Math.sin(evection) + 0.658 * Math.sin(2 * elongation)
                    - 0.186 * Math.sin(sunAnomaly));
            eclipticLatitude = Math.asin(Math.sin(argument) * Math.sin(inclination))
                    + Math.toRadians(-0.173 * Math.sin(argumentOfLatitude - 2 * elongation)
                    - 0.055 * Math.sin(anomaly - argumentOfLatitude - 2 * elongation)
                    - 0.046 * Math.sin(anomaly + argumentOfLatitude - 2 * elongation));
            distance = 60.2666 * Math.hypot(orbitalX, orbitalY)
                    - 0.58 * Math.cos(evection) - 0.46 * Math.cos(2 * elongation);
        } else {
            double eccentricity = 0.016709 - 1.151e-9 * days;
            double eccentric = eccentricAnomaly(sunAnomaly, eccentricity);
            eclipticLongitude = Math.atan2(Math.sqrt(1 - eccentricity * eccentricity) * Math.sin(eccentric),
                    Math.cos(eccentric) - eccentricity) + sunPerihelion;
        }

        double obliquity = Math.toRadians(23.4393 - 3.563e-7 * days);
        double x = Math.cos(eclipticLongitude) * Math.cos(eclipticLatitude);
        double y = Math.sin(eclipticLongitude) * Math.cos(eclipticLatitude);
        double z = Math.sin(eclipticLatitude);
        double equatorialY = y * Math.cos(obliquity) - z * Math.sin(obliquity);
        double equatorialZ = y * Math.sin(obliquity) + z * Math.cos(obliquity);
        double sidereal = sunMeanLongitude + Math.toRadians(180 + 360 * (days - Math.floor(days)) + longitude);
        double meridian = x * Math.cos(sidereal) + equatorialY * Math.sin(sidereal);
        double lat = Math.toRadians(latitude);
        double east = -x * Math.sin(sidereal) + equatorialY * Math.cos(sidereal);
        double north = equatorialZ * Math.cos(lat) - meridian * Math.sin(lat);
        double up = meridian * Math.cos(lat) + equatorialZ * Math.sin(lat);
        if (moon) up -= 1 / distance; // Observer is one Earth radius above the geocenter.
        return new SkyPosition(normalize(Math.toDegrees(Math.atan2(east, north))),
                Math.toDegrees(Math.atan2(Math.hypot(east, north), up)));
    }

    private static double eccentricAnomaly(double mean, double eccentricity) {
        double eccentric = mean;
        for (int i = 0; i < 3; i++) {
            eccentric -= (eccentric - eccentricity * Math.sin(eccentric) - mean)
                    / (1 - eccentricity * Math.cos(eccentric));
        }
        return eccentric;
    }

    static double maximumSolarElevation(LocalDate date, ZoneId zone, double latitude, double longitude) {
        long start = date.atStartOfDay(zone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
        double minimumZenith = at(Instant.ofEpochSecond(start), latitude, longitude, false).zenith;
        // Each six-hour interval is short enough to isolate the daily extrema.
        for (long left = start; left < end; left += 21600) {
            long low = left;
            long high = Math.min(left + 21600, end);
            minimumZenith = Math.min(minimumZenith, at(Instant.ofEpochSecond(high), latitude, longitude, false).zenith);
            while (high - low > 2) {
                long third = (high - low) / 3;
                long first = low + third;
                long second = high - third;
                double firstAngle = at(Instant.ofEpochSecond(first), latitude, longitude, false).zenith;
                double secondAngle = at(Instant.ofEpochSecond(second), latitude, longitude, false).zenith;
                if (firstAngle > secondAngle) low = first;
                else high = second;
            }
            minimumZenith = Math.min(minimumZenith,
                    at(Instant.ofEpochSecond((low + high) / 2), latitude, longitude, false).zenith);
        }
        return 90 - minimumZenith;
    }

    private static double normalize(double degrees) {
        return degrees - 360 * Math.floor(degrees / 360);
    }
}
