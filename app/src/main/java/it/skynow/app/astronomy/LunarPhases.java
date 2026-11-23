package it.skynow.app.astronomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class LunarPhases {
    private static final double NEW_MOON_EPOCH = 2451550.09766;
    private static final double SYNODIC_MONTH = 29.530588861;
    private static final double UNIX_JULIAN_DATE = 2440587.5;
    private static final double SECONDS_PER_DAY = 86400;
    private static final int TT_TO_UTC_SECONDS = 69;

    private LunarPhases() {}

    public static double apparentMagnitude(SkyPosition sun, SkyPosition moon) {
        double sunZenith = Math.toRadians(sun.zenith);
        double moonZenith = Math.toRadians(moon.zenith);
        double azimuthDifference = Math.toRadians(sun.azimuth - moon.azimuth);
        double cosine = Math.cos(sunZenith) * Math.cos(moonZenith)
                + Math.sin(sunZenith) * Math.sin(moonZenith) * Math.cos(azimuthDifference);
        double sunMoonAu = Math.sqrt(sun.distanceAu * sun.distanceAu + moon.distanceAu * moon.distanceAu
                - 2 * sun.distanceAu * moon.distanceAu * cosine);
        double phaseAngle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                (moon.distanceAu - sun.distanceAu * cosine) / sunMoonAu))));
        // Approximate phase law from Krisciunas and Schaefer (1991), eq. 9.
        double moonDistanceRatio = moon.distanceAu * SkyPosition.AU_KM / 384400;
        return -12.73 + .026 * phaseAngle + 4e-9 * Math.pow(phaseAngle, 4)
                + 5 * Math.log10(moonDistanceRatio * sunMoonAu);
    }

    public static LocalDate nextDate(LocalDate from, ZoneId zone, boolean fullMoon) {
        return nextDate(from, zone, fullMoon ? 2 : 0);
    }

    public static LocalDate nextDate(LocalDate from, ZoneId zone, int quarter) {
        double start = UNIX_JULIAN_DATE + from.atStartOfDay(zone).toEpochSecond() / SECONDS_PER_DAY;
        double phase = quarter / 4.0;
        double cycle = Math.floor((start - NEW_MOON_EPOCH) / SYNODIC_MONTH - phase) + phase;
        LocalDate date = dateAtPhase(cycle, zone, quarter);
        while (date.isBefore(from)) {
            date = dateAtPhase(++cycle, zone, quarter);
        }
        return date;
    }

    // Meeus, Astronomical Algorithms, chapter 49. The leading periodic terms
    // are sufficient for the approximate local calendar date shown in the app.
    private static LocalDate dateAtPhase(double cycle, ZoneId zone, int quarter) {
        double centuries = cycle / 1236.85;
        double t2 = centuries * centuries;
        double jd = NEW_MOON_EPOCH + SYNODIC_MONTH * cycle + .00015437 * t2;
        double eccentricity = 1 - .002516 * centuries - .0000074 * t2;
        double sun = Math.toRadians(2.5534 + 29.10535670 * cycle - .0000014 * t2);
        double moon = Math.toRadians(201.5643 + 385.81693528 * cycle + .0107582 * t2);
        double latitude = Math.toRadians(160.7108 + 390.67050284 * cycle - .0016118 * t2);
        double node = Math.toRadians(124.7746 - 1.56375588 * cycle + .0020672 * t2);

        if (quarter == 1 || quarter == 3) {
            double correction = .00306 - .00038 * eccentricity * Math.cos(sun)
                    + .00026 * Math.cos(moon) - .00002 * (Math.cos(moon - sun) - Math.cos(moon + sun) - Math.cos(2 * latitude));
            jd += -.62801 * Math.sin(moon) + .17172 * eccentricity * Math.sin(sun)
                    - .01183 * eccentricity * Math.sin(moon + sun) + .00862 * Math.sin(2 * moon)
                    + .00804 * Math.sin(2 * latitude) + .00454 * eccentricity * Math.sin(moon - sun)
                    + .00204 * eccentricity * eccentricity * Math.sin(2 * sun)
                    - .00180 * Math.sin(moon - 2 * latitude) - .00070 * Math.sin(moon + 2 * latitude)
                    - .00040 * Math.sin(3 * moon) - .00034 * eccentricity * Math.sin(2 * moon - sun)
                    + .00032 * eccentricity * (Math.sin(sun + 2 * latitude) + Math.sin(sun - 2 * latitude))
                    - .00028 * eccentricity * eccentricity * Math.sin(moon + 2 * sun)
                    + .00027 * eccentricity * Math.sin(2 * moon + sun) - .00017 * Math.sin(node)
                    + (quarter == 1 ? correction : -correction);
        } else {
            boolean fullMoon = quarter == 2;
            jd += (fullMoon ? -.40614 : -.40720) * Math.sin(moon)
                + (fullMoon ? .17302 : .17241) * eccentricity * Math.sin(sun)
                + (fullMoon ? .01614 : .01608) * Math.sin(2 * moon)
                + (fullMoon ? .01043 : .01039) * Math.sin(2 * latitude)
                + (fullMoon ? .00734 : .00739) * eccentricity * Math.sin(moon - sun)
                - (fullMoon ? .00515 : .00514) * eccentricity * Math.sin(moon + sun)
                + (fullMoon ? .00209 : .00208) * eccentricity * eccentricity * Math.sin(2 * sun)
                - .00111 * Math.sin(moon - 2 * latitude)
                - .00057 * Math.sin(moon + 2 * latitude)
                + .00056 * eccentricity * Math.sin(2 * moon + sun)
                - .00042 * Math.sin(3 * moon)
                + .00042 * eccentricity * Math.sin(sun + 2 * latitude)
                + .00038 * eccentricity * Math.sin(sun - 2 * latitude)
                - .00024 * eccentricity * Math.sin(2 * moon - sun)
                - .00017 * Math.sin(node);
        }

        long utcSecond = Math.round((jd - UNIX_JULIAN_DATE) * SECONDS_PER_DAY) - TT_TO_UTC_SECONDS;
        return Instant.ofEpochSecond(utcSecond).atZone(zone).toLocalDate();
    }
}
