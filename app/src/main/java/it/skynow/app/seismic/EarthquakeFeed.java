package it.skynow.app.seismic;

import it.skynow.app.ui.SignalColors;
import it.skynow.app.net.HttpText;

import android.os.SystemClock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class EarthquakeFeed {
    public static final String SOURCE = "https://terremoti.ingv.it/";
    public static final int DEFAULT_RADIUS = 50;
    public static final int MAX_RADIUS = 500;
    public static final int RADIUS_STEP_KM = 10;

    public static int alertRadius(android.content.SharedPreferences preferences) {
        int saved = preferences.getInt("earthquakeRadius", DEFAULT_RADIUS);
        int rounded = Math.round((float) saved / RADIUS_STEP_KM) * RADIUS_STEP_KM;
        return Math.max(RADIUS_STEP_KM, Math.min(rounded, MAX_RADIUS));
    }

    public static Event fromAlert(java.util.Map<String, String> fields) {
        String id = fields.get("id");
        if (id == null || !id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid event ID");
        String[] record = {id, fields.get("time"), fields.get("latitude"), fields.get("longitude"),
                fields.get("depth"), "", "", "", "", fields.get("magnitudeType"), fields.get("magnitude"),
                "", fields.get("region")};
        Event event = new Event(record);
        if (!Double.isFinite(event.latitude) || Math.abs(event.latitude) > 90
                || !Double.isFinite(event.longitude) || Math.abs(event.longitude) > 180
                || !Double.isFinite(event.magnitude) || !Double.isFinite(event.depth)
                || event.zone == null || event.zone.isEmpty() || event.magnitudeType == null) {
            throw new IllegalArgumentException("Invalid INGV event coordinates or readings");
        }
        return event;
    }

    private static final String API = "https://webservices.ingv.it/fdsnws/event/1/query";
    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static double cachedLatitude, cachedLongitude;
    private static int cachedRadius;
    private static long cacheTime;
    private static List<Event> cachedEvents;
    private static final MagnitudeBand[] BANDS = {
            new MagnitudeBand(2, "< 2", 4, SignalColors.GREEN),
            new MagnitudeBand(3, "2–<3", 6, SignalColors.LIME),
            new MagnitudeBand(4, "3–<4", 8, SignalColors.YELLOW),
            new MagnitudeBand(5, "4–<5", 10, SignalColors.ORANGE),
            new MagnitudeBand(Double.POSITIVE_INFINITY, "≥ 5", 12, SignalColors.RED)
    };

    public static int magnitudeColor(double magnitude) {
        for (MagnitudeBand band : BANDS) if (magnitude < band.limit) return band.color;
        return BANDS[BANDS.length - 1].color;
    }

    public static JSONArray magnitudeBands() throws JSONException {
        JSONArray bands = new JSONArray();
        for (MagnitudeBand band : BANDS) {
            JSONObject item = new JSONObject().put("label", band.label).put("radius", band.radius)
                    .put("color", String.format(Locale.ROOT, "#%06x", band.color & 0x00ffffff));
            if (Double.isFinite(band.limit)) item.put("limit", band.limit);
            bands.put(item);
        }
        return bands;
    }

    private static final class MagnitudeBand {
        final double limit;
        final String label;
        final int radius, color;

        MagnitudeBand(double limit, String label, int radius, int color) {
            this.limit = limit;
            this.label = label;
            this.radius = radius;
            this.color = color;
        }
    }

    public static synchronized List<Event> recent(double lat, double lon, int radius) throws Exception {
        if (cachedEvents != null && cachedLatitude == lat && cachedLongitude == lon && cachedRadius == radius
                && SystemClock.elapsedRealtime() - cacheTime < 5 * 60_000) return cachedEvents;

        List<Event> events = fetch(lat, lon, radius, 7 * 24);
        cachedLatitude = lat;
        cachedLongitude = lon;
        cachedRadius = radius;
        cacheTime = SystemClock.elapsedRealtime();
        cachedEvents = events;
        return cachedEvents;
    }

    public static List<Event> fetch(double lat, double lon, int radius, int windowHours) throws Exception {
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String query = API + "?format=text&orderby=time&starttime=" + API_TIME.format(end.minus(windowHours, ChronoUnit.HOURS))
                + "&endtime=" + API_TIME.format(end) + "&latitude=" + lat + "&longitude=" + lon
                + "&maxradiuskm=" + radius + "&limit=" + PAGE_SIZE;
        List<Event> events = new ArrayList<>();
        for (int offset = 1; ; offset += PAGE_SIZE) {
            String body = HttpText.read(query + "&offset=" + offset);
            int rows = 0;
            for (String line : body.split("\\r?\\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                rows++;
                String[] fields = line.split("\\|", -1);
                if (fields.length < 13 || !fields[0].matches("[0-9]+")) continue;
                if (fields.length > 13 && !"earthquake".equalsIgnoreCase(fields[13])) continue;
                try {
                    Event event = new Event(fields);
                    if (Double.isFinite(event.latitude) && Double.isFinite(event.longitude)
                            && Double.isFinite(event.magnitude) && Double.isFinite(event.depth)) events.add(event);
                } catch (DateTimeException | NumberFormatException invalidRecord) {
                    android.util.Log.w("SkyHere", "Invalid INGV event " + fields[0], invalidRecord);
                }
            }
            if (rows < PAGE_SIZE) break;
        }
        return events;
    }

    public static final class Event {
        public final String id, zone, magnitudeType;
        public final Instant time;
        public final double latitude, longitude, depth, magnitude;

        Event(String[] fields) {
            id = fields[0];
            time = Instant.parse(fields[1].endsWith("Z") ? fields[1] : fields[1] + "Z");
            latitude = Double.parseDouble(fields[2]);
            longitude = Double.parseDouble(fields[3]);
            depth = Double.parseDouble(fields[4]);
            magnitudeType = fields[9];
            magnitude = Double.parseDouble(fields[10]);
            zone = fields[12];
        }

        public String url() { return SOURCE + "event/" + id; }

        public boolean matchesMagnitude(int minimum) { return minimum == 0 || magnitude >= minimum; }

        public JSONObject mapData() throws JSONException {
            return new JSONObject().put("time", time.toEpochMilli()).put("lat", latitude).put("lon", longitude)
                    .put("depth", depth).put("magnitude", magnitude).put("magnitudeType", magnitudeType)
                    .put("zone", zone).put("url", url());
        }
    }

    private EarthquakeFeed() {}
}
