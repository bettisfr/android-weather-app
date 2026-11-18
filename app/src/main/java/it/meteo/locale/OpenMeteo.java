package it.meteo.locale;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class OpenMeteo {
    private static final String CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,is_day";
    private static final String HOURLY = "temperature_2m,apparent_temperature,weather_code,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,dew_point_2m,uv_index,is_day";
    private static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,moonrise,moonset,moon_phase,uv_index_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,relative_humidity_2m_mean";

    static JSONObject forecast(double latitude, double longitude) throws Exception {
        // The regional model covers Italy and surrounding land, and only provides three days.
        boolean nearItaly = latitude >= 35.4 && latitude <= 47.2 && longitude >= 6.5 && longitude <= 19.0;
        CompletableFuture<JSONObject> best = request(latitude, longitude, "best_match", 11);
        CompletableFuture<JSONObject> ecmwf = request(latitude, longitude, "ecmwf_ifs", 11);
        CompletableFuture<JSONObject> regional = nearItaly
                ? request(latitude, longitude, "italia_meteo_arpae_icon_2i", 3)
                : CompletableFuture.completedFuture(null);
        JSONObject bestMatch = best.join();
        JSONObject global = ecmwf.join();
        JSONObject local = regional.join();
        if (bestMatch == null && global == null) throw new IOException("Open-Meteo forecast unavailable");
        JSONObject forecast = bestMatch != null ? bestMatch : global;
        if (global != null && bestMatch != null) {
            String firstExtendedDay = forecast.getJSONObject("daily").getJSONArray("time").optString(3);
            if (!firstExtendedDay.isEmpty()) {
                overlay(forecast.getJSONObject("hourly"), global.optJSONObject("hourly"), firstExtendedDay);
                overlay(forecast.getJSONObject("daily"), global.optJSONObject("daily"), firstExtendedDay);
            }
        }
        if (local != null) {
            JSONObject current = local.optJSONObject("current");
            if (current != null && !current.isNull("temperature_2m") && !current.isNull("weather_code")) {
                forecast.put("current", current);
            }
            overlay(forecast.getJSONObject("hourly"), local.optJSONObject("hourly"), "");
            overlay(forecast.getJSONObject("daily"), local.optJSONObject("daily"), "");
        }
        return forecast;
    }

    private static CompletableFuture<JSONObject> request(double latitude, double longitude, String model, int days) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject forecast = read("https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                        + "&longitude=" + longitude + "&timezone=auto&forecast_days=" + days
                        + "&models=" + model + "&current=" + CURRENT + "&hourly=" + HOURLY + "&daily=" + DAILY);
                if (forecast.has("current") && forecast.has("hourly") && forecast.has("daily")) return forecast;
            } catch (Exception error) {
                android.util.Log.w("MeteoLocale", "Forecast model unavailable: " + model, error);
            }
            return null;
        });
    }

    private static void overlay(JSONObject target, JSONObject source, String from) throws org.json.JSONException {
        if (source == null) return;
        JSONArray sourceTimes = source.getJSONArray("time");
        JSONArray targetTimes = target.getJSONArray("time");
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < targetTimes.length(); i++) positions.put(targetTimes.getString(i), i);
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equals("time")) continue;
            JSONArray sourceValues = source.optJSONArray(key);
            JSONArray targetValues = target.optJSONArray(key);
            if (sourceValues == null || targetValues == null) continue;
            for (int i = 0; i < sourceTimes.length(); i++) {
                String time = sourceTimes.getString(i);
                Integer targetIndex = positions.get(time);
                if (targetIndex != null && time.compareTo(from) >= 0 && !sourceValues.isNull(i)) {
                    targetValues.put(targetIndex, sourceValues.get(i));
                }
            }
        }
    }

    static JSONObject search(String query, String language) throws Exception {
        return read("https://geocoding-api.open-meteo.com/v1/search?count=12&language=" + language + "&name="
                + URLEncoder.encode(query, "UTF-8"));
    }

    private static JSONObject read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream stream = connection.getInputStream()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }
}
