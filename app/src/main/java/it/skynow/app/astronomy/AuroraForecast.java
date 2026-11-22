package it.skynow.app.astronomy;

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

public final class AuroraForecast {
    public static final String MAP = "https://www.spaceweather.gov/products/aurora-30-minute-forecast";
    public static final int MAP_MIN_ACTIVITY = 5;
    private static final String FEED = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    private static final String CURRENT_KP = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json";
    private static final String KP_FEED = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json";
    private static final double EARTH_RADIUS_KM = 6371;
    private static final double SEARCH_RADIUS_KM = 1000;

    public final Instant observationTime;
    public final Instant forecastTime;
    private final JSONArray grid;
    private final byte[] mapCells = new byte[360 * 181];

    private AuroraForecast(JSONObject response) throws Exception {
        observationTime = Instant.parse(response.getString("Observation Time"));
        forecastTime = Instant.parse(response.getString("Forecast Time"));
        grid = response.getJSONArray("coordinates");
        populateMapCells();
    }

    public static AuroraForecast load() throws Exception {
        return new AuroraForecast(new JSONObject(read(FEED)));
    }

    public static KpReading loadCurrentKp() throws Exception {
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

    public static final class KpReading {
        public final Instant time;
        public final double kp;

        KpReading(Instant time, double kp) {
            this.time = time;
            this.kp = kp;
        }
    }

    public static List<KpPeriod> loadKp() throws Exception {
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

    public static final class KpPeriod {
        public final Instant start;
        public final double kp;

        KpPeriod(Instant start, double kp) {
            this.start = start;
            this.kp = kp;
        }

        public Instant end() {
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

    private void populateMapCells() {
        for (int i = 0; i < grid.length(); i++) {
            JSONArray point = grid.optJSONArray(i);
            if (point == null) continue;
            int longitude = point.optInt(0, -1);
            int latitude = point.optInt(1, -91);
            int activity = point.optInt(2);
            if (longitude < 0 || longitude >= 360 || latitude < -90 || latitude > 90
                    || activity < 0 || activity > 100) continue;
            int column = (longitude + 180) % 360;
            mapCells[(90 - latitude) * 360 + column] = (byte) activity;
        }
    }

    public String mapGrid() {
        return android.util.Base64.encodeToString(mapCells, android.util.Base64.NO_WRAP);
    }

    public boolean isCurrentAt(Instant now) {
        return !now.isAfter(forecastTime.plusSeconds(1800))
                && !now.isAfter(observationTime.plusSeconds(7200));
    }

    public boolean coversOnMap(double latitude, double longitude) {
        if (!(Math.abs(latitude) <= 85.05112878) || !Double.isFinite(longitude)) return false;
        int row = 90 - (int) Math.round(latitude);
        int column = Math.floorMod((int) Math.round(longitude) + 180, 360);
        return mapCells[row * 360 + column] >= MAP_MIN_ACTIVITY;
    }

    public double estimatedDirection(double latitude, double longitude) {
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
