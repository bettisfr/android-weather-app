package it.meteo.locale;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class EclipseCatalog {
    private static final String ENDPOINT = "https://opale.imcce.fr/api/v1/phenomena/eclipses/";
    private static final double REFRACTION_DEGREES = .57;

    static final class Event {
        final String type;
        final Instant start;
        final Instant maximum;
        final Instant end;
        final boolean horizonLimited;
        final boolean lunar;
        final boolean penumbral;
        final boolean estimatedPeak;
        final double obscuration;
        final double shadowX;
        final double shadowY;
        final double shadowRadius;
        final LocalDate catalogDate;

        Event(String type, long start, long bestVisible, long end, boolean horizonLimited,
              boolean lunar, boolean penumbral, boolean estimatedPeak, double obscuration,
              double shadowX, double shadowY, double shadowRadius, LocalDate catalogDate) {
            this.type = type;
            this.start = Instant.ofEpochSecond(start);
            this.maximum = Instant.ofEpochSecond(bestVisible);
            this.end = Instant.ofEpochSecond(end);
            this.horizonLimited = horizonLimited;
            this.lunar = lunar;
            this.penumbral = penumbral;
            this.estimatedPeak = estimatedPeak;
            this.obscuration = obscuration;
            this.shadowX = shadowX;
            this.shadowY = shadowY;
            this.shadowRadius = shadowRadius;
            this.catalogDate = catalogDate;
        }

        String detailsUrl() {
            String date = catalogDate.format(DateTimeFormatter.ofPattern("uuuu-MMMM-d", Locale.US))
                    .toLowerCase(Locale.ROOT);
            return "https://www.timeanddate.com/eclipse/" + (lunar ? "lunar/" : "solar/") + date;
        }
    }

    private static final class Contact {
        final long time;
        final double elevation;
        final double longitude;
        final double latitude;
        final double horizon;
        final double separationX;
        final double separationY;
        final double moonRadius;
        final double sunRadius;

        Contact(long time, double elevation, double longitude, double latitude, double horizon,
                double separationX, double separationY, double moonRadius, double sunRadius) {
            this.time = time;
            this.elevation = elevation;
            this.longitude = longitude;
            this.latitude = latitude;
            this.horizon = horizon;
            this.separationX = separationX;
            this.separationY = separationY;
            this.moonRadius = moonRadius;
            this.sunRadius = sunRadius;
        }
    }

    private EclipseCatalog() {}

    static int yearsPerRequest(boolean lunar) {
        return lunar ? 3 : 10;
    }

    static List<Event> upcoming(boolean lunar, int year, double latitude, double longitude, Instant now) throws Exception {
        String path = (lunar ? "301/" : "10/") + year + "?nbd=" + yearsPerRequest(lunar);
        if (!lunar) path += String.format(Locale.US, "&observer=%.5f,%.5f", latitude, longitude);
        JSONArray rows = read(ENDPOINT + path).getJSONObject("response")
                .getJSONArray(lunar ? "lunareclipse" : "data");
        List<Event> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            Event event = visibleEvent(rows.getJSONObject(i), lunar, latitude, longitude);
            if (event != null && !event.end.isBefore(now)) result.add(event);
        }
        result.sort(Comparator.comparing(event -> event.start));
        return result;
    }

    private static Event visibleEvent(JSONObject row, boolean lunar, double latitude, double longitude) throws org.json.JSONException {
        JSONObject events = row.optJSONObject("events");
        if (events == null) return null;
        Contact start = contact(events.optJSONObject("P1"), lunar, latitude, longitude);
        Contact end = contact(events.optJSONObject(lunar ? "P2" : "P4"), lunar, latitude, longitude);
        Contact peak = contact(events.optJSONObject("greatest"), lunar, latitude, longitude);
        if (start == null || end == null || peak == null) return null;

        List<Contact> contacts = new ArrayList<>();
        contacts.add(start);
        contacts.add(peak);
        contacts.add(end);
        for (String key : new String[]{"U1", "U2", "U3", "U4"}) {
            Contact contact = contact(events.optJSONObject(key), lunar, latitude, longitude);
            if (contact != null && contact.time > start.time && contact.time < end.time) contacts.add(contact);
        }
        contacts.sort(Comparator.comparingLong(contact -> contact.time));
        long visibleStart = -1;
        long visibleEnd = -1;
        int segment = 0;
        long previousTime = start.time;
        boolean previousVisible = altitude(start, start, previousTime, lunar, latitude, longitude) >= 0;
        if (previousVisible) visibleStart = start.time;
        for (long time = Math.min(start.time + 60, end.time); time > previousTime; time = Math.min(time + 60, end.time)) {
            while (segment + 1 < contacts.size() - 1 && contacts.get(segment + 1).time <= time) segment++;
            boolean visible = altitude(contacts.get(segment), contacts.get(segment + 1), time, lunar, latitude, longitude) >= 0;
            if (visible != previousVisible) {
                long crossing = crossing(contacts, segment, previousTime, time, lunar, latitude, longitude, visible);
                if (visible) {
                    if (visibleStart < 0) visibleStart = crossing;
                } else {
                    visibleEnd = crossing;
                }
            }
            if (visible && time == end.time) visibleEnd = end.time;
            previousVisible = visible;
            previousTime = time;
        }
        if (visibleStart < 0 || visibleEnd <= visibleStart) return null;

        long bestVisible = Math.max(visibleStart, Math.min(peak.time, visibleEnd));
        boolean estimatedPeak = bestVisible != peak.time;
        String type = eclipseType(row.optString("type"), lunar, !estimatedPeak);
        Contact before = contacts.get(0);
        Contact after = contacts.get(1);
        for (int i = 1; i < contacts.size() - 1 && after.time < bestVisible; i++) {
            before = after;
            after = contacts.get(i + 1);
        }
        double fraction = after.time == before.time ? 0 : (double) (bestVisible - before.time) / (after.time - before.time);
        double x = interpolate(before.separationX, after.separationX, fraction);
        double y = interpolate(before.separationY, after.separationY, fraction);
        double separation = Math.hypot(x, y);
        double moonRadius = interpolate(before.moonRadius, after.moonRadius, fraction);
        double diskRadius = lunar ? moonRadius : interpolate(before.sunRadius, after.sunRadius, fraction);
        double shadowRadius = moonRadius;
        boolean penumbral = false;
        double obscuration;
        if (lunar) {
            JSONObject radius = row.optJSONObject("radius");
            double umbra = radius == null ? Double.NaN : radius.optDouble("umbra");
            double penumbra = radius == null ? Double.NaN : radius.optDouble("penumbra");
            shadowRadius = umbra;
            obscuration = diskCoverage(diskRadius, shadowRadius, separation);
            penumbral = type.startsWith("Penumbral") || obscuration == 0;
            if (penumbral) {
                shadowRadius = penumbra;
                obscuration = diskCoverage(diskRadius, shadowRadius, separation);
                type = "Penumbral lunar eclipse";
            } else if (estimatedPeak && obscuration < 99.95) {
                type = "Partial lunar eclipse";
            }
        } else {
            obscuration = row.optDouble("obscuration");
            if (estimatedPeak || !Double.isFinite(obscuration)) {
                obscuration = diskCoverage(diskRadius, shadowRadius, separation);
            }
        }
        String date = row.optString("calendarDate");
        LocalDate catalogDate = date.isEmpty()
                ? Instant.ofEpochSecond(peak.time).atOffset(ZoneOffset.UTC).toLocalDate() : LocalDate.parse(date);
        return new Event(type, visibleStart, bestVisible, visibleEnd, visibleStart > start.time || visibleEnd < end.time,
                lunar, penumbral, estimatedPeak, obscuration, x / diskRadius, y / diskRadius,
                shadowRadius / diskRadius, catalogDate);
    }

    private static Contact contact(JSONObject event, boolean lunar, double latitude, double longitude) throws org.json.JSONException {
        if (event == null || event.optString("date").isEmpty()) return null;
        long time = LocalDateTime.parse(event.optString("date")).toEpochSecond(ZoneOffset.UTC);
        JSONObject moon = event.optJSONObject("Moon");
        if (!lunar) {
            JSONObject sun = event.optJSONObject("Sun");
            if (sun == null || !Double.isFinite(sun.optDouble("elevation"))) return null;
            double sunRadius = sun.optDouble("radius", .266);
            double elevation = sun.optDouble("elevation");
            double moonElevation = moon == null ? Double.NaN : moon.optDouble("elevation");
            double azimuthDifference = moon == null ? Double.NaN : moon.optDouble("azimuth") - sun.optDouble("azimuth");
            double x = azimuthOffset(azimuthDifference, elevation, moonElevation);
            return new Contact(time, elevation, 0, 0, -REFRACTION_DEGREES - sunRadius,
                    x, moonElevation - elevation, moon == null ? Double.NaN : moon.optDouble("radius"), sunRadius);
        }
        JSONObject zenith = event.optJSONObject("zenith");
        JSONObject geometry = zenith == null ? null : zenith.optJSONObject("geometry");
        JSONArray point = geometry == null ? null : geometry.optJSONArray("coordinates");
        if (moon == null || point == null || !Double.isFinite(point.optDouble(0)) || !Double.isFinite(point.optDouble(1))) return null;
        double moonRadius = moon.optDouble("radius", .26);
        double horizon = moon.optDouble("parallax", .95) - moonRadius - REFRACTION_DEGREES;
        double moonLongitude = point.getDouble(0);
        double moonLatitude = point.getDouble(1);
        double axis = event.optDouble("axis");
        JSONObject sun = event.optJSONObject("Sun");
        double x = Double.NaN;
        double y = Double.NaN;
        if (sun != null) {
            double shadowLongitude = moonLongitude + Math.IEEEremainder((sun.optDouble("RA") - moon.optDouble("RA")) * 15 + 180, 360);
            double[] moonAngles = horizontal(moonLatitude, moonLongitude, latitude, longitude);
            double[] shadowAngles = horizontal(-sun.optDouble("DEC"), shadowLongitude, latitude, longitude);
            x = azimuthOffset(shadowAngles[0] - moonAngles[0], moonAngles[1], shadowAngles[1]);
            y = shadowAngles[1] - moonAngles[1];
            double projected = Math.hypot(x, y);
            if (projected > 0) {
                x *= axis / projected;
                y *= axis / projected;
            }
        }
        // Preserve the shadow separation if the position angle is unavailable.
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            x = axis;
            y = 0;
        }
        return new Contact(time, 0, moonLongitude, moonLatitude, horizon, x, y, moonRadius, Double.NaN);
    }

    private static double azimuthOffset(double deltaAzimuth, double firstElevation, double secondElevation) {
        return Math.IEEEremainder(deltaAzimuth, 360) * Math.cos(Math.toRadians((firstElevation + secondElevation) / 2));
    }

    private static double[] horizontal(double targetLatitude, double targetLongitude, double latitude, double longitude) {
        double declination = Math.toRadians(targetLatitude);
        double observer = Math.toRadians(latitude);
        double offset = Math.toRadians(targetLongitude - longitude);
        double east = Math.cos(declination) * Math.sin(offset);
        double north = Math.cos(observer) * Math.sin(declination) - Math.sin(observer) * Math.cos(declination) * Math.cos(offset);
        double up = Math.sin(observer) * Math.sin(declination) + Math.cos(observer) * Math.cos(declination) * Math.cos(offset);
        return new double[]{Math.toDegrees(Math.atan2(east, north)), Math.toDegrees(Math.atan2(up, Math.hypot(east, north)))};
    }

    private static double diskCoverage(double disk, double shadow, double distance) {
        if (!Double.isFinite(disk) || !Double.isFinite(shadow) || !Double.isFinite(distance) || disk <= 0 || shadow <= 0) return Double.NaN;
        if (distance >= disk + shadow) return 0;
        if (distance <= Math.abs(disk - shadow)) return 100 * Math.pow(Math.min(disk, shadow) / disk, 2);
        double d2 = distance * distance;
        double a2 = disk * disk;
        double b2 = shadow * shadow;
        double sectorA = Math.acos(Math.max(-1, Math.min(1, (d2 + a2 - b2) / (2 * distance * disk))));
        double sectorB = Math.acos(Math.max(-1, Math.min(1, (d2 + b2 - a2) / (2 * distance * shadow))));
        double area = a2 * (sectorA - Math.sin(2 * sectorA) / 2)
                + b2 * (sectorB - Math.sin(2 * sectorB) / 2);
        return Math.max(0, Math.min(100, 100 * area / (Math.PI * a2)));
    }

    private static long crossing(List<Contact> contacts, int segment, long low, long high,
                                 boolean lunar, double latitude, double longitude, boolean rises) {
        while (high - low > 1) {
            long middle = (low + high) / 2;
            while (segment > 0 && contacts.get(segment).time > middle) segment--;
            while (segment + 1 < contacts.size() - 1 && contacts.get(segment + 1).time <= middle) segment++;
            boolean above = altitude(contacts.get(segment), contacts.get(segment + 1), middle, lunar, latitude, longitude) >= 0;
            if (above == rises) high = middle;
            else low = middle;
        }
        return high;
    }

    private static double altitude(Contact first, Contact last, long time, boolean lunar, double latitude, double longitude) {
        double progress = last.time == first.time ? 0 : (double) (time - first.time) / (last.time - first.time);
        double horizon = interpolate(first.horizon, last.horizon, progress);
        if (!lunar) return interpolate(first.elevation, last.elevation, progress) - horizon;

        // Interpolate the sublunar point through the published eclipse contacts.
        double lon = first.longitude + Math.IEEEremainder(last.longitude - first.longitude, 360) * progress;
        double declination = Math.toRadians(interpolate(first.latitude, last.latitude, progress));
        double observer = Math.toRadians(latitude);
        double hourAngle = Math.toRadians(longitude - lon);
        double sine = Math.sin(observer) * Math.sin(declination)
                + Math.cos(observer) * Math.cos(declination) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sine)))) - horizon;
    }

    private static double interpolate(double first, double last, double progress) {
        return first + (last - first) * progress;
    }

    private static String eclipseType(String type, boolean lunar, boolean visibleMaximum) {
        String code = type.toLowerCase(Locale.ROOT);
        String kind;
        if (code.contains("penumbral")) kind = "Penumbral";
        else if (!lunar && !visibleMaximum) kind = "Partial";
        else if (code.contains("annular")) kind = "Annular";
        else if (code.contains("hybrid")) kind = "Hybrid";
        else if (code.contains("total")) kind = "Total";
        else kind = "Partial";
        return kind + (lunar ? " lunar eclipse" : " solar eclipse");
    }

    private static JSONObject read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        try (InputStream stream = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            JSONObject data = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            if (!data.has("response")) throw new IOException("Eclipse catalog unavailable");
            return data;
        } finally {
            connection.disconnect();
        }
    }
}
