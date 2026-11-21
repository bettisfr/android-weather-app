package it.skynow.app.astronomy;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import it.skynow.app.net.HttpText;
import sgp4.TLE;

final class IssOrbitalElements {
    private static final String SOURCE = "https://www.amsat.org/tle/current/nasabare.txt";
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(6);
    private static final Duration RETRY_INTERVAL = Duration.ofMinutes(15);
    private static final Duration MAX_EPOCH_AGE = Duration.ofDays(3);

    private IssOrbitalElements() {}

    static synchronized TLE get(Context context, Instant now) throws IOException {
        SharedPreferences cache = context.getSharedPreferences("iss_orbital_elements", Context.MODE_PRIVATE);
        TLE saved = parse(cache.getString("elements", ""), now);
        long nowMillis = now.toEpochMilli();
        if (saved != null && recent(cache.getLong("validatedAt", 0), nowMillis, REFRESH_INTERVAL)) return saved;
        if (recent(cache.getLong("lastAttempt", 0), nowMillis, RETRY_INTERVAL)) {
            if (saved != null) return saved;
            throw new IOException("ISS orbital data refresh pending");
        }
        cache.edit().putLong("lastAttempt", nowMillis).apply();
        try {
            String elements = HttpText.read(SOURCE);
            TLE downloaded = parse(elements, now);
            if (downloaded == null) throw new IOException("AMSAT ISS orbital elements are invalid or stale");
            TLE newest = saved != null && saved.getEpoch().after(downloaded.getEpoch()) ? saved : downloaded;
            cache.edit().putString("elements", newest.getLine1() + "\n" + newest.getLine2())
                    .putLong("validatedAt", nowMillis).apply();
            return newest;
        } catch (IOException error) {
            if (saved != null) return saved;
            throw error;
        }
    }

    private static boolean recent(long timestamp, long now, Duration interval) {
        return timestamp > 0 && timestamp <= now && now - timestamp < interval.toMillis();
    }

    private static TLE parse(String text, Instant now) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            String first = lines[i];
            String second = lines[i + 1];
            if (first.length() < 69 || second.length() < 69
                    || !first.startsWith("1 25544") || !second.startsWith("2 25544")) continue;
            TLE orbit = new TLE(first, second);
            if (orbit.getParseErrors() == null && orbit.getEpoch() != null
                    && Duration.between(orbit.getEpoch().toInstant(), now).abs().compareTo(MAX_EPOCH_AGE) <= 0) {
                return orbit;
            }
        }
        return null;
    }
}
