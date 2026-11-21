package it.skynow.app.weather;

import it.skynow.app.net.HttpText;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class OpenMeteo {
    public static final int UPCOMING_ASTRONOMY_DAYS = 15;
    private static final String CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,is_day";
    private static final String HOURLY = "temperature_2m,apparent_temperature,weather_code,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,dew_point_2m,uv_index,is_day,cloud_cover";
    private static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,moonrise,moonset,moon_phase,uv_index_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,relative_humidity_2m_mean";
    private static final DateTimeFormatter WEATHER_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm", java.util.Locale.ROOT);

    public static JSONObject forecast(double latitude, double longitude) throws Exception {
        // The regional model covers Italy and surrounding land, and only provides three days.
        boolean nearItaly = latitude >= 35.4 && latitude <= 47.2 && longitude >= 6.5 && longitude <= 19.0;
        CompletableFuture<JSONObject> best = request(latitude, longitude, "best_match", UPCOMING_ASTRONOMY_DAYS + 1);
        CompletableFuture<JSONObject> ecmwf = request(latitude, longitude, "ecmwf_ifs", UPCOMING_ASTRONOMY_DAYS + 1);
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
                        + "&longitude=" + longitude + "&timezone=auto&timeformat=unixtime&forecast_days=" + days
                        + "&models=" + model + "&current=" + CURRENT + "&hourly=" + HOURLY + "&daily=" + DAILY);
                if (forecast.has("current") && forecast.has("hourly") && forecast.has("daily")) {
                    formatForecastTimes(forecast);
                    return forecast;
                }
            } catch (Exception error) {
                android.util.Log.w("MeteoLocale", "Forecast model unavailable: " + model, error);
            }
            return null;
        });
    }

    private static void formatForecastTimes(JSONObject forecast) throws org.json.JSONException {
        // Match the API's ISO date/hour boundaries while retaining event seconds.
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(forecast.getInt("utc_offset_seconds"));
        JSONObject current = forecast.getJSONObject("current");
        current.put("time", Instant.ofEpochSecond(current.getLong("time")).atOffset(offset).format(WEATHER_TIME));
        formatTimes(forecast.getJSONObject("hourly").getJSONArray("time"), offset, WEATHER_TIME);
        JSONObject daily = forecast.getJSONObject("daily");
        formatTimes(daily.getJSONArray("time"), offset, DateTimeFormatter.ISO_LOCAL_DATE);
        for (String event : new String[]{"sunrise", "sunset", "moonrise", "moonset"}) {
            JSONArray times = daily.optJSONArray(event);
            if (times != null) formatTimes(times, offset, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    private static void formatTimes(JSONArray times, ZoneOffset offset, DateTimeFormatter format) throws org.json.JSONException {
        for (int i = 0; i < times.length(); i++) {
            if (times.isNull(i)) continue;
            OffsetDateTime time = Instant.ofEpochSecond(times.getLong(i)).atOffset(offset);
            times.put(i, time.format(format));
        }
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

    public static JSONObject search(String query, String language) throws Exception {
        return read("https://geocoding-api.open-meteo.com/v1/search?count=12&language=" + language + "&name="
                + URLEncoder.encode(query, "UTF-8"));
    }

    private static final String AIR_QUALITY = "https://air-quality-api.open-meteo.com/v1/air-quality?";

    public static JSONObject airQuality(double latitude, double longitude) throws Exception {
        return read(AIR_QUALITY + "latitude=" + latitude + "&longitude=" + longitude
                + "&current=european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide").getJSONObject("current");
    }

    private static JSONObject read(String address) throws Exception {
        return new JSONObject(HttpText.read(address));
    }
}
