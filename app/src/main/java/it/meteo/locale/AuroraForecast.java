package it.meteo.locale;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class AuroraForecast {
    static final String MAP = "https://www.spaceweather.gov/products/aurora-30-minute-forecast";
    private static final String FEED = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    private static final String CURRENT_KP = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json";
    private static final String KP_FEED = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json";
    private static final double EARTH_RADIUS_KM = 6371;
    private static final double SEARCH_RADIUS_KM = 1000;

    final Instant observationTime;
    final Instant forecastTime;
    private final JSONArray grid;

    private AuroraForecast(JSONObject response) throws Exception {
        observationTime = Instant.parse(response.getString("Observation Time"));
        forecastTime = Instant.parse(response.getString("Forecast Time"));
        grid = response.getJSONArray("coordinates");
    }

    static AuroraForecast load() throws Exception {
        return new AuroraForecast(new JSONObject(read(FEED)));
    }

    static KpReading loadCurrentKp() throws Exception {
        JSONArray rows = new JSONArray(read(CURRENT_KP));
        KpReading latest = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            double kp = row.optDouble("estimated_kp");
            if (!(kp >= 0 && kp <= 9)) continue;
            Instant time = LocalDateTime.parse(row.getString("time_tag")).toInstant(ZoneOffset.UTC);
            if (latest == null || time.isAfter(latest.time)) latest = new KpReading(time, kp);
        }
        return latest;
    }

    static final class KpReading {
        final Instant time;
        final double kp;

        KpReading(Instant time, double kp) {
            this.time = time;
            this.kp = kp;
        }
    }

    static List<KpPeriod> loadKp() throws Exception {
        JSONArray rows = new JSONArray(read(KP_FEED));
        List<KpPeriod> periods = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !"predicted".equals(row.optString("observed"))) continue;
            double kp = row.optDouble("kp");
            if (!(kp >= 0 && kp <= 9)) continue;
            Instant start = LocalDateTime.parse(row.getString("time_tag")).toInstant(ZoneOffset.UTC);
            periods.add(new KpPeriod(start, kp));
        }
        return periods;
    }

    static final class KpPeriod {
        final Instant start;
        final double kp;

        KpPeriod(Instant start, double kp) {
            this.start = start;
            this.kp = kp;
        }

        Instant end() {
            return start.plusSeconds(10800);
        }
    }

    private static String read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) body.append(buffer, 0, count);
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    String mapGrid() {
        byte[] cells = new byte[360 * 181];
        for (int i = 0; i < grid.length(); i++) {
            JSONArray point = grid.optJSONArray(i);
            if (point == null) continue;
            int longitude = point.optInt(0, -1);
            int latitude = point.optInt(1, -91);
            int activity = point.optInt(2);
            if (longitude < 0 || longitude >= 360 || latitude < -90 || latitude > 90
                    || activity < 0 || activity > 100) continue;
            int column = (longitude + 180) % 360;
            cells[(90 - latitude) * 360 + column] = (byte) activity;
        }
        return android.util.Base64.encodeToString(cells, android.util.Base64.NO_WRAP);
    }

    double estimatedDirection(double latitude, double longitude) {
        double origin = Math.toRadians(latitude);
        double bestScore = 0;
        double direction = Double.NaN;
        for (int i = 0; i < grid.length(); i++) {
            JSONArray point = grid.optJSONArray(i);
            if (point == null) continue;
            double pointLat = point.optDouble(1);
            double activity = point.optDouble(2);
            if (!(activity >= 10) || Math.abs(pointLat - latitude) > 10) continue;
            double destination = Math.toRadians(pointLat);
            double longitudeDelta = Math.toRadians(point.optDouble(0) - longitude);
            double cosDistance = Math.sin(origin) * Math.sin(destination)
                    + Math.cos(origin) * Math.cos(destination) * Math.cos(longitudeDelta);
            double distance = EARTH_RADIUS_KM * Math.acos(Math.max(-1, Math.min(1, cosDistance)));
            // Favor brighter, closer parts of the oval. The score is a display heuristic.
            double score = activity * (1 - distance / SEARCH_RADIUS_KM);
            if (score < 10 || score <= bestScore) continue;
            bestScore = score;
            direction = distance < 100 ? -1 : (Math.toDegrees(Math.atan2(
                    Math.sin(longitudeDelta) * Math.cos(destination),
                    Math.cos(origin) * Math.sin(destination)
                            - Math.sin(origin) * Math.cos(destination) * Math.cos(longitudeDelta))) + 360) % 360;
        }
        return direction;
    }
}
