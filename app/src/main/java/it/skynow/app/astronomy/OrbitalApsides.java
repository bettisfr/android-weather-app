package it.skynow.app.astronomy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class OrbitalApsides {
    public static final String SOURCE = "https://ssd.jpl.nasa.gov/planets/eph_export.html";

    private OrbitalApsides() {}

    public static final class Event {
        public final Instant time;
        public final long distanceKm;

        private Event(Instant time, long distanceKm) {
            this.time = time;
            this.distanceKm = distanceKm;
        }
    }

    /** Returns the next nearest and farthest points, respectively. */
    public static Event[] upcoming(Context context, Instant now, boolean moon) throws IOException {
        Event[] result = new Event[2];
        String body = moon ? "moon" : "earth";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("orbital-apsides-utc.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((result[0] == null || result[1] == null) && (line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split(" ");
                if (!body.equals(fields[1])) continue;
                int index = "near".equals(fields[2]) ? 0 : 1;
                if (result[index] != null) continue;
                Instant time = Instant.parse(fields[0]);
                if (!time.isBefore(now)) result[index] = new Event(time, Long.parseLong(fields[3]));
            }
        }
        return result;
    }
}
