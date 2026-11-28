package it.skynow.app.notifications;

import it.skynow.app.astronomy.SkyPosition;
import java.time.Instant;

/**
 * When to alert about aurora: the same test as the Astronomy tab's "Now" badge — a current NOAA
 * forecast whose oval covers the place at night — once the sky is dark enough to see it. Clouds
 * are left to the person outside.
 */
final class AuroraAlertRule {
    /** The Sun at least 6° below the horizon; brighter twilight shows as "too light" in the app. */
    static final double DARK_SUN_ZENITH = 96;
    /** No second alert within this time, so a flickering oval edge does not repeat it. */
    static final long QUIET_MILLIS = 6 * 60 * 60 * 1000L;

    private AuroraAlertRule() {}

    static boolean darkEnough(Instant now, double latitude, double longitude) {
        return SkyPosition.at(now, latitude, longitude, false).zenith >= DARK_SUN_ZENITH;
    }

    /** Alerts when the place newly comes under the oval, at most once per quiet period. */
    static boolean shouldNotify(boolean covered, boolean wasCovered, long nowMillis, long lastAlertMillis) {
        return covered && !wasCovered && nowMillis - lastAlertMillis >= QUIET_MILLIS;
    }
}
